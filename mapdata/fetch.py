#!/usr/bin/env python3
"""Download the public data a region pack is built from.

    fetch.py <west> <south> <east> <north> <workdir>

- Forest Service surface ownership parcels, system roads and trails (USFS EDW services)
- OpenStreetMap for the same box (Overpass)
- USGS 3DEP 1/3 arc-second elevation, as one GeoTIFF

Nothing here needs a key. Everything lands in <workdir>; re-running skips what is there.
"""
import json
import os
import sys
import time
import urllib.parse
import urllib.request

UA = "chikei-mapdata/0.1 (+https://github.com/wanderwildwood)"
EDW = "https://apps.fs.usda.gov/arcx/rest/services/EDW"
LAYERS = {
    "ownership": "EDW_SurfaceOwnership_01/MapServer/0",
    "roads": "EDW_RoadBasic_01/MapServer/0",
    "trails": "EDW_TrailNFSPublish_01/MapServer/0",
}
# Only the fields the build reads. SurfaceOwnership also carries the names of the people
# land was bought from; they are never requested, so they never reach the pack.
FIELDS = {
    "ownership": "ownerclassification,nfslandunitname",
    "roads": "id,name,oper_maint_level,route_status",
    "trails": "trail_name,trail_no,trail_type,national_trail_designation",
}


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


def fetch_layer(name, path, bbox, out):
    feats, offset = [], 0
    while True:
        q = urllib.parse.urlencode({
            "geometry": ",".join(map(str, bbox)), "geometryType": "esriGeometryEnvelope",
            "inSR": 4326, "spatialRel": "esriSpatialRelIntersects", "outFields": FIELDS[name],
            "outSR": 4326, "f": "geojson", "resultOffset": offset, "resultRecordCount": 1000,
        })
        page = json.loads(get(f"{EDW}/{path}/query?{q}"))
        if "error" in page:
            raise SystemExit(f"{name}: {page['error']}")
        feats += page["features"]
        print(f"   {name}: {len(feats)}")
        if not page.get("exceededTransferLimit") and len(page["features"]) < 1000:
            break
        offset += len(page["features"])
    json.dump({"type": "FeatureCollection", "features": feats}, open(out, "w"))


def fetch_osm(bbox, out):
    w, s, e, n = bbox
    q = f"[out:xml][timeout:600][maxsize:1073741824];(nwr({s},{w},{n},{e}););(._;>;);out meta;"
    body = urllib.parse.urlencode({"data": q}).encode()
    open(out, "wb").write(get("https://overpass-api.de/api/interpreter", data=body))


def fetch_dem(bbox, out):
    """3DEP at ~10 m. The service refuses big images, so ask in tiles and stitch them."""
    import numpy as np
    import rasterio
    from rasterio.merge import merge
    w, s, e, n = bbox
    step = 0.15  # degrees; ~1620 px at 1/3 arc-second, well inside what the service renders
    tiles = []
    lat = s
    while lat < n - 1e-9:
        lon = w
        while lon < e - 1e-9:
            tb = (lon, lat, min(lon + step, e), min(lat + step, n))
            px_w, px_h = round((tb[2] - tb[0]) * 10800), round((tb[3] - tb[1]) * 10800)
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
    srcs = [rasterio.open(t) for t in tiles]
    mosaic, transform = merge(srcs)
    profile = srcs[0].profile
    profile.update(height=mosaic.shape[1], width=mosaic.shape[2], transform=transform, driver="GTiff")
    for src in srcs:
        src.close()
    with rasterio.open(out, "w", **profile) as dst:
        dst.write(mosaic)
    for t in tiles:
        os.remove(t)
    print(f"   dem: {mosaic.shape[2]}x{mosaic.shape[1]}")


def main():
    if len(sys.argv) != 6:
        raise SystemExit(__doc__)
    bbox = tuple(float(v) for v in sys.argv[1:5])
    work = sys.argv[5]
    os.makedirs(work, exist_ok=True)
    jobs = [(f"{n}.geojson", lambda o, n=n, p=p: fetch_layer(n, p, bbox, o)) for n, p in LAYERS.items()]
    jobs += [("osm.osm", lambda o: fetch_osm(bbox, o)), ("dem.tif", lambda o: fetch_dem(bbox, o))]
    for fname, job in jobs:
        out = os.path.join(work, fname)
        if os.path.exists(out) and os.path.getsize(out) > 0:
            print(f"-- {fname}: have it")
            continue
        print(f"-- {fname}")
        job(out + ".part")
        os.replace(out + ".part", out)


if __name__ == "__main__":
    main()
