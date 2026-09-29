#!/usr/bin/env python3
"""Turn the fetched layers into OSM XML the Mapsforge writer can merge with the OSM extract.

    to_osm.py <workdir>

Writes <workdir>/extra.osm holding:
- USFS land:     closed ways / multipolygons tagged  ownership=usfs, name=<forest>
- USFS roads:    ways tagged  fs_road=<maintenance level 1-5>, ref="FS <id>", name
- USFS trails:   ways tagged  fs_trail=yes, name, national=<yes|no>
- contours:      ways tagged  contour=elevation, ele=<feet>, contour_ext=elevation_{major,minor}

Every new object gets a negative id, so nothing collides with real OSM ids.
Contours are in feet, 40 ft apart, with every 200 ft a major (labelled) line, like a USGS quad.
"""
import json
import os
import sys
from xml.sax.saxutils import quoteattr

import numpy as np
import rasterio
from shapely.geometry import shape, LineString, MultiPolygon
from shapely.ops import unary_union
import contourpy

MINOR_FT, MAJOR_FT = 40, 200
SIMPLIFY_DEG = 0.00003  # ~3 m; the panel cannot show finer and it keeps the file small


class Writer:
    def __init__(self, path):
        self.f = open(path, "w")
        self.next_id = -1
        self.f.write("<?xml version='1.0' encoding='UTF-8'?>\n<osm version='0.6' generator='chikei'>\n")

    def _id(self):
        i = self.next_id
        self.next_id -= 1
        return i

    def way(self, coords, tags, closed=False):
        coords = list(coords)
        if closed and coords[0] == coords[-1]:
            coords = coords[:-1]
        if len(coords) < 2:
            return None
        ids = []
        for lon, lat in coords:
            nid = self._id()
            self.f.write(f"<node id='{nid}' version='1' timestamp='1970-01-01T00:00:00Z' lat='{lat:.7f}' lon='{lon:.7f}'/>\n")
            ids.append(nid)
        if closed:
            ids.append(ids[0])
        wid = self._id()
        self.f.write(f"<way id='{wid}' version='1' timestamp='1970-01-01T00:00:00Z'>")
        self.f.write("".join(f"<nd ref='{i}'/>" for i in ids))
        self.f.write(self._tags(tags) + "</way>\n")
        return wid

    def multipolygon(self, poly, tags):
        polys = poly.geoms if isinstance(poly, MultiPolygon) else [poly]
        for p in polys:
            p = p.simplify(SIMPLIFY_DEG)
            if p.is_empty or p.geom_type != "Polygon":
                continue
            if not p.interiors:
                self.way(p.exterior.coords, tags, closed=True)
                continue
            outer = self.way(p.exterior.coords, {}, closed=True)
            inners = [self.way(r.coords, {}, closed=True) for r in p.interiors]
            rid = self._id()
            members = f"<member type='way' ref='{outer}' role='outer'/>"
            members += "".join(f"<member type='way' ref='{i}' role='inner'/>" for i in inners if i)
            self.f.write(f"<relation id='{rid}' version='1' timestamp='1970-01-01T00:00:00Z'>{members}"
                         + self._tags({"type": "multipolygon", **tags}) + "</relation>\n")

    @staticmethod
    def _tags(tags):
        return "".join(f"<tag k={quoteattr(k)} v={quoteattr(str(v))}/>" for k, v in tags.items() if v not in (None, ""))

    def close(self):
        self.f.write("</osm>\n")
        self.f.close()


def lines_of(geom):
    if geom.geom_type == "LineString":
        return [geom]
    if geom.geom_type == "MultiLineString":
        return list(geom.geoms)
    return []


def main():
    work = sys.argv[1]
    w = Writer(os.path.join(work, "extra.osm.part"))

    # --- ownership: union parcels per forest, so the pack holds outlines not a parcel quilt
    feats = json.load(open(os.path.join(work, "ownership.geojson")))["features"]
    usfs = [f for f in feats if f["properties"].get("ownerclassification") == "USDA FOREST SERVICE"]
    forests = {}
    for f in usfs:
        forests.setdefault(f["properties"].get("nfslandunitname") or "National Forest", []).append(
            shape(f["geometry"]).buffer(0))
    for name, parts in forests.items():
        # close the hairline gaps between neighbouring parcels before dissolving
        u = unary_union(parts).buffer(0.00002).buffer(-0.00002)
        w.multipolygon(u, {"ownership": "usfs", "name": name})
        print(f"   ownership: {name}: {len(parts)} parcels")

    # --- Forest Service roads
    n = 0
    for f in json.load(open(os.path.join(work, "roads.geojson")))["features"]:
        p = f["properties"]
        if (p.get("route_status") or "").startswith("DE"):  # decommissioned
            continue
        ml = (p.get("oper_maint_level") or "")[:1]
        for g in lines_of(shape(f["geometry"]).simplify(SIMPLIFY_DEG)):
            w.way(g.coords, {"fs_road": ml or "yes", "ref": f"FS {p['id']}" if p.get("id") else None,
                             "name": (p.get("name") or "").title()})
            n += 1
    print(f"   roads: {n} ways")

    # --- Forest Service trails
    n = 0
    for f in json.load(open(os.path.join(work, "trails.geojson")))["features"]:
        p = f["properties"]
        for g in lines_of(shape(f["geometry"]).simplify(SIMPLIFY_DEG)):
            w.way(g.coords, {"fs_trail": "yes", "name": (p.get("trail_name") or "").title(),
                             "ref": p.get("trail_no"),
                             "national": "yes" if p.get("national_trail_designation") not in (None, 0, "0") else None})
            n += 1
    print(f"   trails: {n} ways")

    # --- contours
    with rasterio.open(os.path.join(work, "dem.tif")) as src:
        z = src.read(1).astype(np.float64) * 3.28084
        z[z < -1000] = np.nan
        t = src.transform
        lon = t.c + t.a * (np.arange(src.width) + 0.5)
        lat = t.f + t.e * (np.arange(src.height) + 0.5)
    gen = contourpy.contour_generator(lon, lat, z, name="serial", line_type=contourpy.LineType.Separate)
    lo = int(np.nanmin(z) // MINOR_FT * MINOR_FT) + MINOR_FT
    hi = int(np.nanmax(z) // MINOR_FT * MINOR_FT)
    n = 0
    for level in range(lo, hi + 1, MINOR_FT):
        kind = "elevation_major" if level % MAJOR_FT == 0 else "elevation_minor"
        for seg in gen.lines(level):
            if len(seg) < 3:
                continue
            simp = LineString(seg).simplify(SIMPLIFY_DEG)
            if simp.length < 0.0005:  # < ~50 m: noise rings on flat ground
                continue
            # Mapsforge keeps a way's name but not arbitrary numeric tags, so the label rides on name.
            w.way(simp.coords, {"contour": "elevation", "contour_ext": kind,
                                "name": str(level) if kind == "elevation_major" else None})
            n += 1
    print(f"   contours: {n} ways, {lo}-{hi} ft")

    w.close()
    os.replace(os.path.join(work, "extra.osm.part"), os.path.join(work, "extra.osm"))


if __name__ == "__main__":
    main()
