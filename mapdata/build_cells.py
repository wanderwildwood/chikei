#!/usr/bin/env python3
"""Cut a fetched area into one pack per USGS 7.5-minute quadrangle.

    build_cells.py <srcdir> <cellsdir> [<quad name> ...]

<srcdir> is what fetch.py produced (it includes quads.geojson: USGS quadrangles in the US, or
1/8-degree squares elsewhere). For each cell fully inside the fetched area, writes
<cellsdir>/<id>/ holding <id>.map, <id>-dem.zip, <id>-land.json.gz and cell.json (display name
and bounds, read by publish.py). Name cells to build only those. A square has no name of its
own, so it takes the largest place inside it, or else its coordinates.

Needs osmosis with the mapsforge-map-writer plugin (OSMOSIS overrides the binary).
"""
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OSMOSIS = os.environ.get("OSMOSIS", "osmosis")
MARGIN = 0.003  # OSM read a little past the cell, so ways crossing its edge stay whole
ATTRIBUTION = {
    "osm": "OpenStreetMap contributors (ODbL)",
    "usfs": "USDA Forest Service",
    "padus": "USGS PAD-US",
    "tribal": "US Census Bureau TIGER",
    "parcels": "county parcel records",
    "wdpa": "UNEP-WCMC and IUCN, Protected Planet (WDPA)",
    "dem-us": "USGS 3DEP",
    "dem-world": "Copernicus DEM GLO-30, (c) DLR e.V. 2010-2014 and (c) Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA",
}
PLACE_RANK = {"city": 0, "town": 1, "village": 2, "hamlet": 3, "locality": 4}


def slug(name):
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")


def run(*cmd):
    print("   $", " ".join(str(c) for c in cmd[:4]), "...")
    subprocess.run([str(c) for c in cmd], check=True)


def tag_mapping(path):
    jar = next(
        os.path.join(d, f)
        for d in [os.path.expanduser("~/.openstreetmap/osmosis/plugins")]
        for f in os.listdir(d) if f.startswith("mapsforge-map-writer")
    )
    base = subprocess.run(["unzip", "-p", jar, "tag-mapping.xml"], check=True, capture_output=True, text=True).stdout
    extra = open(os.path.join(HERE, "tag-mapping-extra.xml")).read()
    open(path, "w").write(base.replace("</tag-mapping>", extra + "</tag-mapping>"))


def place_names(pbf, work):
    """Every named place in the area, for naming squares: (lon, lat, rank, population, name)."""
    xml = os.path.join(work, "places.osm")
    if not os.path.exists(xml):
        run(OSMOSIS, "-q", "--rb", pbf, "--tf", "accept-nodes", "place=city,town,village,hamlet,locality",
            "--tf", "reject-ways", "--tf", "reject-relations", "--wx", xml)
    import xml.etree.ElementTree as ET
    out = []
    for node in ET.parse(xml).getroot().iter("node"):
        tags = {t.get("k"): t.get("v") for t in node.iter("tag")}
        name = tags.get("name:en") or tags.get("name")
        if not name or tags.get("place") not in PLACE_RANK:
            continue
        try:
            pop = int((tags.get("population") or "0").replace(",", "").split()[0])
        except ValueError:
            pop = 0
        out.append((float(node.get("lon")), float(node.get("lat")), PLACE_RANK[tags["place"]], pop, name))
    return out


def square_name(places, w, s, e, n):
    inside = [p for p in places if w <= p[0] < e and s <= p[1] < n]
    if inside:
        return min(inside, key=lambda p: (p[2], -p[3], p[4]))[4]
    lat, lon = (s + n) / 2, (w + e) / 2
    return f"{abs(lat):.2f}°{'N' if lat >= 0 else 'S'} {abs(lon):.2f}°{'E' if lon >= 0 else 'W'}"


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    src, cells_dir = sys.argv[1], sys.argv[2]
    only = set(sys.argv[3:])
    os.makedirs(cells_dir, exist_ok=True)

    # The area actually fetched, from the DEM's extent
    import rasterio
    with rasterio.open(os.path.join(src, "dem.tif")) as ds:
        aw, as_, ae, an = ds.bounds
    if os.path.exists(os.path.join(src, "region.json")):
        # the box asked for; a DEM's own edge can sit half a pixel inside it
        aw, as_, ae, an = json.load(open(os.path.join(src, "region.json")))["bbox"]

    mapping = os.path.join(cells_dir, "tag-mapping.xml")
    tag_mapping(mapping)

    # Parse the OSM XML once into PBF; every cell then reads the fast format.
    pbf = os.path.join(src, "osm.osm.pbf")
    if not os.path.exists(pbf):
        run(OSMOSIS, "-q", "--rx", os.path.join(src, "osm.osm"), "--sort", "--wb", pbf)

    region = {"us": True, "sources": ["usfs", "parcels"]}
    if os.path.exists(os.path.join(src, "region.json")):
        region = json.load(open(os.path.join(src, "region.json")))
    used = [k for k in ("osm", "usfs", "padus", "tribal", "parcels", "wdpa") if k == "osm" or k in region["sources"]]
    attribution = "; ".join([ATTRIBUTION[k] for k in used] + [ATTRIBUTION["dem-us" if region["us"] else "dem-world"]])

    quads = json.load(open(os.path.join(src, "quads.geojson")))["features"]
    places = None
    for q in quads:
        if not q["properties"].get("CELL_NAME"):
            places = places if places is not None else place_names(pbf, src)
            ring = q["geometry"]["coordinates"][0]
            lons, lats = [p[0] for p in ring], [p[1] for p in ring]
            q["properties"]["CELL_NAME"] = square_name(places, min(lons), min(lats), max(lons), max(lats))
    names = [q["properties"]["CELL_NAME"] for q in quads]
    for q in quads:
        # Two squares can share a largest place; the second is told apart by its corner
        if names.count(q["properties"]["CELL_NAME"]) > 1 and not q["properties"].get("STATE_ALPHA"):
            ring = q["geometry"]["coordinates"][0]
            q["properties"]["CELL_NAME"] += f" ({min(p[1] for p in ring):.3f}, {min(p[0] for p in ring):.3f})"
    for q in sorted(quads, key=lambda f: f["properties"]["CELL_NAME"]):
        name = q["properties"]["CELL_NAME"]
        ring = q["geometry"]["coordinates"][0]
        # 7.5-minute quads sit on an exact 1/8-degree grid; the index stores the corners a few
        # millionths of a degree off it, which would push edge quads just outside the area.
        w, e = (round(f(p[0] for p in ring) * 8) / 8 for f in (min, max))
        s, n = (round(f(p[1] for p in ring) * 8) / 8 for f in (min, max))
        if only and name not in only:
            continue
        if w < aw - 1e-6 or e > ae + 1e-6 or s < as_ - 1e-6 or n > an + 1e-6:
            print(f"-- {name}: outside the fetched area, skipped")
            continue
        cid = slug(name)
        out = os.path.join(cells_dir, cid)
        os.makedirs(out, exist_ok=True)
        print(f"-- {name} ({cid}) {w},{s},{e},{n}")

        run(sys.executable, os.path.join(HERE, "to_osm.py"), src, out, w, s, e, n)
        run(OSMOSIS, "-q",
            "--rb", pbf, "--bounding-box",
            f"left={w - MARGIN}", f"right={e + MARGIN}", f"bottom={s - MARGIN}", f"top={n + MARGIN}",
            "completeWays=yes", "--sort",
            "--rx", os.path.join(out, "extra.osm"), "--sort",
            "--merge",
            "--mapfile-writer", f"file={out}/{cid}.map.part", f"bbox={s},{w},{n},{e}",
            f"tag-conf-file={mapping}", "type=ram", "threads=4", f"comment={attribution}")
        os.replace(f"{out}/{cid}.map.part", f"{out}/{cid}.map")
        run(sys.executable, os.path.join(HERE, "dem_pack.py"), src, out, w, s, e, n)
        run(sys.executable, os.path.join(HERE, "land_pack.py"), src, out, w, s, e, n)
        os.remove(os.path.join(out, "extra.osm"))
        json.dump({"name": name, "bounds": [w, s, e, n], "state": q["properties"].get("STATE_ALPHA"),
                   "attribution": attribution},
                  open(os.path.join(out, "cell.json"), "w"))
        print(f"   {cid}.map {os.path.getsize(f'{out}/{cid}.map') / 1e6:.1f} MB, "
              f"{cid}-dem.zip {os.path.getsize(f'{out}/{cid}-dem.zip') / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
