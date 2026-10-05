#!/usr/bin/env python3
"""Download the public data a region pack is built from.

    fetch.py <west> <south> <east> <north> <workdir> [--sources a,b,...] [--units feet|metres]

Each kind of data is a source, and a region takes the sources that exist for it. Without
--sources, a box in the United States takes the US ones and a box anywhere else takes the
world ones:

  usfs      Forest Service surface ownership, system roads and trails (USFS EDW)          US
  padus     public and protected land: federal, state, local, land trusts, easements,
            wilderness and other designations, with owner, manager and public access
            (USGS PAD-US)                                                                 US
  tribal    reservations and off-reservation trust land (Census TIGER)                     US
  parcels   county lots with the owner of record, from every service in PARCELS whose
            area meets the box; add your own state or county there                        US
  wdpa      protected areas worldwide: national parks, reserves and the like, with the
            designation and managing body (UNEP-WCMC / IUCN, Protected Planet)           world
  quads     the USGS 7.5-minute quadrangles the packs are cut along                        US
  grid      1/8-degree squares instead, for anywhere without quadrangles                world
  osm       OpenStreetMap, cut from Geofabrik's regional extracts                         both
  dem       elevation: USGS 3DEP 10 m in the US, Copernicus GLO-30 elsewhere             both
  dem_fine  3DEP at 1/9 arc-second (~3 m, lidar where the service has it), which only
            the contours are drawn from; the pack's elevation stays at 10 m               US

Everything is free to fetch and needs no key. WDPA's terms allow personal and
non-commercial use; a server offering packs built with it to the public should read them
first (see the README). Everything lands in <workdir>; re-running skips what is there.
"""
import json
import math
import os
import subprocess
import sys
import time
import urllib.parse
import urllib.request

UA = "chikei-mapdata/0.2 (+https://github.com/wanderwildwood/chikei)"
EDW = "https://apps.fs.usda.gov/arcx/rest/services/EDW"
PADUS = "https://services.arcgis.com/v01gqwM5QqNysAAi/arcgis/rest/services/Manager_Name_PADUS/FeatureServer/0"
TIGER = "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/tigerWMS_Current/MapServer"
WDPA = "https://data-gis.unep-wcmc.org/server/rest/services/ProtectedSites/The_World_Database_of_Protected_Areas/MapServer/1"
QUADS = "https://carto.nationalmap.gov/arcgis/rest/services/map_indices/MapServer/10"

# County and state parcel services that publish the owner of record. Each entry is an ArcGIS
# feature layer, the field holding the owner, and the box it covers [W, S, E, N]. Only the
# outline and the owner are ever requested: never mailing addresses or values. Add a service
# for your own county here; one whose box meets the region is used.
PARCELS = [
    {"name": "North Carolina (NC OneMap)",
     "url": "https://services.nconemap.gov/secure/rest/services/NC1Map_Parcels/FeatureServer/1",
     "owner": "ownname", "bounds": [-84.33, 33.84, -75.46, 36.59]},
]

# Tribal areas in TIGERweb: federal reservations, off-reservation trust land, state
# reservations, Hawaiian home lands, joint-use areas
TIGER_LAYERS = [36, 38, 40, 42, 52]

US_SOURCES = ["usfs", "padus", "tribal", "parcels", "quads", "osm", "dem", "dem_fine"]
WORLD_SOURCES = ["wdpa", "grid", "osm", "dem"]


def get(url, data=None, tries=4):
    for i in range(tries):
        try:
            req = urllib.request.Request(url, data=data, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=300) as r:
                return r.read()
        except Exception as e:  # network hiccups are common on these services
            if i == tries - 1:
                raise
            print(f"   retry {i + 1}: {e}")
            time.sleep(5 * (i + 1))


def query(layer, bbox, fields, page=1000, where="1=1", label=""):
    """Every feature of an ArcGIS layer meeting the box, as GeoJSON features in WGS84."""
    feats, offset = [], 0
    while True:
        q = urllib.parse.urlencode({
            "where": where, "geometry": ",".join(map(str, bbox)), "geometryType": "esriGeometryEnvelope",
            "inSR": 4326, "spatialRel": "esriSpatialRelIntersects", "outFields": fields,
            "outSR": 4326, "f": "geojson", "resultOffset": offset, "resultRecordCount": page,
        })
        for attempt in range(6):
            page_json = json.loads(get(f"{layer}/query?{q}"))
            err = page_json.get("error") or {}
            # PAD-US's host allows sixty large requests a minute; wait the minute out
            if err.get("code") == 429 and attempt < 5:
                print(f"   {label}: rate limited, waiting a minute")
                time.sleep(65)
                continue
            break
        if "error" in page_json:
            raise SystemExit(f"{label or layer}: {page_json['error']}")
        got = page_json.get("features", [])
        feats += got
        print(f"   {label}: {len(feats)}")
        more = page_json.get("exceededTransferLimit") or page_json.get("properties", {}).get("exceededTransferLimit")
        if not got or (not more and len(got) < page):
            break
        offset += len(got)
    return feats


def save(feats, out):
    json.dump({"type": "FeatureCollection", "features": feats}, open(out, "w"))


def domains(layer):
    """Each field's coded values, so PAD-US's "BLM" is written out as Bureau of Land Management."""
    info = json.loads(get(f"{layer}?f=json"))
    return {f["name"]: {str(c["code"]): c["name"] for c in f["domain"]["codedValues"]}
            for f in info.get("fields", []) if (f.get("domain") or {}).get("codedValues")}


# ---------------------------------------------------------------- sources

def fetch_usfs(bbox, work):
    for name, path, fields in [
        # SurfaceOwnership also carries the names of the people land was bought from; they
        # are never requested, so they never reach the pack.
        ("ownership", f"{EDW}/EDW_SurfaceOwnership_01/MapServer/0", "ownerclassification,nfslandunitname"),
        ("roads", f"{EDW}/EDW_RoadBasic_01/MapServer/0", "id,name,oper_maint_level,route_status"),
        ("trails", f"{EDW}/EDW_TrailNFSPublish_01/MapServer/0", "trail_name,trail_no,trail_type,national_trail_designation"),
    ]:
        once(work, f"{name}.geojson", lambda o: save(query(path, bbox, fields, label=name), o))


def fetch_padus(bbox, work):
    def run(out):
        feats = query(PADUS, bbox, "Category,Own_Type,Own_Name,Loc_Own,Mang_Type,Mang_Name,Loc_Mang,"
                      "Des_Tp,Loc_Ds,Unit_Nm,Pub_Access,EsmtHldr,GIS_Acres", page=500, label="padus")
        coded = domains(PADUS)
        for f in feats:
            p = f["properties"]
            for k, v in list(p.items()):
                if k in coded and v is not None and str(v) in coded[k]:
                    p[k + "_desc"] = coded[k][str(v)]
        save(feats, out)
    once(work, "padus.geojson", run)


def fetch_tribal(bbox, work):
    def run(out):
        feats = []
        for layer in TIGER_LAYERS:
            got = query(f"{TIGER}/{layer}", bbox, "NAME,BASENAME", label=f"tribal {layer}")
            for f in got:
                f["properties"]["layer"] = layer
            feats += got
        save(feats, out)
    once(work, "tribal.geojson", run)


def fetch_parcels(bbox, work):
    def run(out):
        feats = []
        w, s, e, n = bbox
        for src in PARCELS:
            bw, bs, be, bn = src["bounds"]
            if be < w or bw > e or bn < s or bs > n:
                continue
            got = query(src["url"], bbox, src["owner"], page=2000, label=f"parcels ({src['name']})")
            for f in got:
                f["properties"] = {"owner": (f["properties"].get(src["owner"]) or "").strip()}
            feats += got
        save(feats, out)
    once(work, "parcels.geojson", run)


def fetch_wdpa(bbox, work):
    once(work, "wdpa.geojson", lambda o: save(query(
        WDPA, bbox, "name,name_eng,desig_eng,iucn_cat,own_type,mang_auth,gov_type,status",
        page=500, where="status<>'Proposed'", label="wdpa"), o))


def fetch_quads(bbox, work):
    once(work, "quads.geojson", lambda o: save(query(QUADS, bbox, "CELL_NAME,STATE_ALPHA", label="quads"), o))


def fetch_grid(bbox, work):
    """1/8-degree squares, named later from the largest place in each (build_cells.py)."""
    def run(out):
        w, s, e, n = bbox
        feats = []
        lat = math.floor(s * 8) / 8
        while lat < n - 1e-9:
            lon = math.floor(w * 8) / 8
            while lon < e - 1e-9:
                ring = [[lon, lat], [lon + .125, lat], [lon + .125, lat + .125], [lon, lat + .125], [lon, lat]]
                feats.append({"type": "Feature", "properties": {"CELL_NAME": None},
                              "geometry": {"type": "Polygon", "coordinates": [ring]}})
                lon += .125
            lat += .125
        save(feats, out)
        print(f"   grid: {len(feats)} squares")
    once(work, "quads.geojson", run)


def geofabrik_regions(bbox):
    """The smallest Geofabrik extracts that together cover the box: a US state, an English
    county, a German Regierungsbezirk. Smallest first, each taken only if it covers some of
    the box the ones before it did not."""
    from shapely.geometry import box, shape
    cache = os.path.join(os.environ.get("OSM_EXTRACT_CACHE", "."), "geofabrik-index-v1.json")
    if not os.path.exists(cache) or time.time() - os.path.getmtime(cache) > 30 * 86400:
        open(cache, "wb").write(get("https://download.geofabrik.de/index-v1.json"))
    target = box(*bbox)
    meeting = []
    for f in json.load(open(cache))["features"]:
        if not f.get("geometry"):
            continue
        g = shape(f["geometry"])
        if g.intersects(target):
            meeting.append((g.area, g, f["properties"]))
    chosen, left = [], target
    for _, g, p in sorted(meeting, key=lambda m: m[0]):
        if left.is_empty or left.area < 1e-9:
            break
        if g.intersects(left) and g.intersection(left).area > 1e-9:
            chosen.append((p["id"], p["urls"]["pbf"]))
            left = left.difference(g)
    if not chosen:
        raise SystemExit("osm: no Geofabrik extract covers this box")
    return chosen


def resolve(url):
    """Geofabrik's -latest links redirect, by way of a trailing slash, to the dated file with a
    slash on the end, which then loops. Follow the chain by hand and take the dated file."""
    import http.client
    for _ in range(6):
        u = urllib.parse.urlparse(url)
        conn = http.client.HTTPSConnection(u.netloc, timeout=60)
        conn.request("HEAD", u.path, headers={"User-Agent": UA})
        r = conn.getresponse()
        loc = r.getheader("Location")
        conn.close()
        if r.status not in (301, 302, 303, 307, 308) or not loc:
            break
        nxt = urllib.parse.urljoin(url, loc)
        if nxt.rstrip("/") != url.rstrip("/") and "-latest" not in nxt:
            url = nxt  # the dated file: stop before its slash sends it round again
            break
        url = nxt
    return url[:-1] if url.endswith(".pbf/") else url


def is_pbf(path):
    """An OSM PBF file opens with a length-prefixed OSMHeader block."""
    with open(path, "rb") as f:
        return b"OSMHeader" in f.read(64)


def fetch_osm(bbox, work):
    """OpenStreetMap for the box, as PBF: the regions' Geofabrik extracts, cut down with
    osmosis. Overpass times out on an area this size."""
    def run(out):
        osmosis = os.environ.get("OSMOSIS", "osmosis")
        cache = os.environ.get("OSM_EXTRACT_CACHE", os.path.join(os.path.dirname(os.path.abspath(work)), "osm-extracts"))
        os.environ["OSM_EXTRACT_CACHE"] = cache
        os.makedirs(cache, exist_ok=True)
        w, s, e, n = bbox
        cmd = [osmosis, "-q"]
        regions = geofabrik_regions(bbox)
        for i, (rid, url) in enumerate(regions):
            pbf = os.path.join(cache, os.path.basename(url))
            for attempt in range(4):
                if os.path.exists(pbf) and is_pbf(pbf):
                    break
                print(f"   downloading {rid} extract")
                subprocess.run(["curl", "-sSf", "-A", UA, "-o", pbf + ".part", resolve(url)], check=True)
                if not is_pbf(pbf + ".part"):
                    # a redirect page, not the extract: ask again
                    os.remove(pbf + ".part")
                    time.sleep(5 * (attempt + 1))
                    continue
                os.replace(pbf + ".part", pbf)
            else:
                raise SystemExit(f"osm: could not download the {rid} extract")
            cmd += ["--rb", pbf, "--bounding-box", f"left={w}", f"right={e}", f"bottom={s}", f"top={n}",
                    "completeWays=yes"]
            if i > 0:
                cmd += ["--merge"]
        cmd += ["--wb", out]
        subprocess.run(cmd, check=True)
        print(f"   osm: {', '.join(r for r, _ in regions)}, {os.path.getsize(out) / 1e6:.1f} MB")
    once(work, "osm.osm.pbf", run)


def fetch_dem(bbox, work, us):
    once(work, "dem.tif", lambda o: (dem_3dep if us else dem_copernicus)(bbox, o))


def fetch_dem_fine(bbox, work):
    once(work, "dem_fine.tif", lambda o: dem_3dep(bbox, o, per_degree=32400))


def write_mosaic(tiles, out):
    import rasterio
    from rasterio.merge import merge
    srcs = [rasterio.open(t) for t in tiles]
    mosaic, transform = merge(srcs)
    profile = srcs[0].profile
    profile.update(height=mosaic.shape[1], width=mosaic.shape[2], transform=transform, driver="GTiff",
                   dtype="float32", count=1, compress="deflate")
    for src in srcs:
        src.close()
    with rasterio.open(out, "w", **profile) as dst:
        dst.write(mosaic.astype("float32"))
    print(f"   dem: {mosaic.shape[2]}x{mosaic.shape[1]}")


def dem_3dep(bbox, out, per_degree=10800):
    """3DEP at ~10 m (1/3 arc-second), or finer with per_degree=32400 (1/9"). The service
    refuses big images, so ask in tiles of ~1620 px and stitch them."""
    w, s, e, n = bbox
    step = 1620 / per_degree  # degrees; well inside what the service renders
    tiles = []
    lat = s
    while lat < n - 1e-9:
        lon = w
        while lon < e - 1e-9:
            tb = (lon, lat, min(lon + step, e), min(lat + step, n))
            px_w, px_h = round((tb[2] - tb[0]) * per_degree), round((tb[3] - tb[1]) * per_degree)
            q = urllib.parse.urlencode({
                "bbox": ",".join(f"{v:.6f}" for v in tb), "bboxSR": 4326, "imageSR": 4326,
                "size": f"{px_w},{px_h}", "format": "tiff", "pixelType": "F32",
                "interpolation": "RSP_BilinearInterpolation", "f": "image",
            })
            data = get("https://elevation.nationalmap.gov/arcgis/rest/services/3DEPElevation/ImageServer/exportImage?" + q)
            if not (data.startswith(b"II*") or data.startswith(b"MM")):
                raise SystemExit("dem: service did not return a TIFF: " + data[:200].decode(errors="replace"))
            path = f"{out}.{len(tiles)}.tif"
            open(path, "wb").write(data)
            tiles.append(path)
            print(f"   dem tile {len(tiles)}: {px_w}x{px_h}")
            lon += step
        lat += step
    write_mosaic(tiles, out)
    for t in tiles:
        os.remove(t)


def dem_copernicus(bbox, out):
    """Copernicus GLO-30, from the open bucket on AWS: one-degree cloud-optimised GeoTIFFs,
    of which only the part inside the box is read. It is a surface model, so forest canopy
    and buildings are in it; contours in woodland come out a little rougher than 3DEP's."""
    import rasterio
    from rasterio.windows import from_bounds
    w, s, e, n = bbox
    tiles = []
    for lat in range(math.floor(s), math.ceil(n)):
        for lon in range(math.floor(w), math.ceil(e)):
            ns = f"{'N' if lat >= 0 else 'S'}{abs(lat):02d}_00"
            ew = f"{'E' if lon >= 0 else 'W'}{abs(lon):03d}_00"
            key = f"Copernicus_DSM_COG_10_{ns}_{ew}_DEM"
            url = f"/vsicurl/https://copernicus-dem-30m.s3.amazonaws.com/{key}/{key}.tif"
            try:
                src = rasterio.open(url)
            except rasterio.errors.RasterioIOError:
                print(f"   dem: no tile {key} (open sea)")
                continue
            with src:
                cw, cs, ce, cn = max(w, src.bounds.left), max(s, src.bounds.bottom), min(e, src.bounds.right), min(n, src.bounds.top)
                win = from_bounds(cw, cs, ce, cn, transform=src.transform).round_offsets().round_lengths()
                data = src.read(1, window=win)
                profile = src.profile
                profile.update(height=data.shape[0], width=data.shape[1], transform=src.window_transform(win),
                               driver="GTiff", blockxsize=None, blockysize=None, tiled=False)
                path = f"{out}.{len(tiles)}.tif"
                with rasterio.open(path, "w", **{k: v for k, v in profile.items() if v is not None}) as dst:
                    dst.write(data, 1)
                tiles.append(path)
                print(f"   dem tile {key}: {data.shape[1]}x{data.shape[0]}")
    if not tiles:
        raise SystemExit("dem: no Copernicus tiles for this box")
    write_mosaic(tiles, out)
    for t in tiles:
        os.remove(t)


# ---------------------------------------------------------------- main

def once(work, fname, job):
    out = os.path.join(work, fname)
    if os.path.exists(out) and os.path.getsize(out) > 0:
        print(f"-- {fname}: have it")
        return
    print(f"-- {fname}")
    job(out + ".part")
    os.replace(out + ".part", out)


def in_us(bbox):
    """Whether the box's centre is in the United States, by asking the quadrangle index."""
    w, s, e, n = bbox
    c = ((w + e) / 2, (s + n) / 2)
    q = urllib.parse.urlencode({"geometry": f"{c[0]},{c[1]}", "geometryType": "esriGeometryPoint",
                                "inSR": 4326, "spatialRel": "esriSpatialRelIntersects",
                                "returnCountOnly": "true", "f": "json"})
    return json.loads(get(f"{QUADS}/query?{q}")).get("count", 0) > 0


def main():
    args = sys.argv[1:]
    opts = {}
    while len(args) >= 2 and args[-2].startswith("--"):
        opts[args[-2][2:]] = args[-1]
        args = args[:-2]
    if len(args) != 5:
        raise SystemExit(__doc__)
    bbox = tuple(float(v) for v in args[:4])
    work = args[4]
    os.makedirs(work, exist_ok=True)

    us = in_us(bbox)
    sources = opts["sources"].split(",") if "sources" in opts else (US_SOURCES if us else WORLD_SOURCES)
    units = opts.get("units") or ("feet" if us else "metres")
    print(f"-- {'United States' if us else 'outside the US'}: {', '.join(sources)}; contours in {units}")
    json.dump({"bbox": bbox, "us": us, "sources": sources, "units": units},
              open(os.path.join(work, "region.json"), "w"))

    runners = {
        "usfs": lambda: fetch_usfs(bbox, work),
        "padus": lambda: fetch_padus(bbox, work),
        "tribal": lambda: fetch_tribal(bbox, work),
        "parcels": lambda: fetch_parcels(bbox, work),
        "wdpa": lambda: fetch_wdpa(bbox, work),
        "quads": lambda: fetch_quads(bbox, work),
        "grid": lambda: fetch_grid(bbox, work),
        "osm": lambda: fetch_osm(bbox, work),
        "dem": lambda: fetch_dem(bbox, work, us),
        "dem_fine": lambda: fetch_dem_fine(bbox, work),
    }
    for name in sources:
        if name not in runners:
            raise SystemExit(f"unknown source {name}; known: {', '.join(runners)}")
        runners[name]()


if __name__ == "__main__":
    main()
