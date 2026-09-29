#!/usr/bin/env python3
"""Cut a fetched area into one pack per USGS 7.5-minute quadrangle.

    build_cells.py <srcdir> <cellsdir> [<quad name> ...]

<srcdir> is what fetch.py produced (it includes quads.geojson). For each quad fully inside
the fetched area, writes <cellsdir>/<id>/ holding <id>.map, <id>-dem.zip and cell.json
(display name and bounds, read by publish.py). Name quads to build only those.

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
ATTRIBUTION = "OpenStreetMap contributors (ODbL); USDA Forest Service; USGS 3DEP; NC OneMap / NC county parcels"


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

    mapping = os.path.join(cells_dir, "tag-mapping.xml")
    tag_mapping(mapping)

    # Parse the OSM XML once into PBF; every cell then reads the fast format.
    pbf = os.path.join(src, "osm.osm.pbf")
    if not os.path.exists(pbf):
        run(OSMOSIS, "-q", "--rx", os.path.join(src, "osm.osm"), "--sort", "--wb", pbf)

    quads = json.load(open(os.path.join(src, "quads.geojson")))["features"]
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
            f"tag-conf-file={mapping}", "type=ram", "threads=4", f"comment={ATTRIBUTION}")
        os.replace(f"{out}/{cid}.map.part", f"{out}/{cid}.map")
        run(sys.executable, os.path.join(HERE, "dem_pack.py"), src, out, w, s, e, n)
        os.remove(os.path.join(out, "extra.osm"))
        json.dump({"name": name, "bounds": [w, s, e, n], "state": q["properties"].get("STATE_ALPHA")},
                  open(os.path.join(out, "cell.json"), "w"))
        print(f"   {cid}.map {os.path.getsize(f'{out}/{cid}.map') / 1e6:.1f} MB, "
              f"{cid}-dem.zip {os.path.getsize(f'{out}/{cid}-dem.zip') / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
