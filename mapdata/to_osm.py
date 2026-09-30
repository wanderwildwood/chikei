#!/usr/bin/env python3
"""Turn the fetched layers into OSM XML the Mapsforge writer can merge with the OSM extract.

    to_osm.py <srcdir> [<outdir> [<west> <south> <east> <north>]]

Reads what fetch.py left in <srcdir>. Writes <outdir>/extra.osm (default <srcdir>), clipped
to the given cell when one is given, holding:
- USFS land:     closed ways / multipolygons tagged  ownership=usfs, name=<forest> (fill),
                 and its outline as ways tagged  ownership_edge=usfs
- USFS roads:    ways tagged  fs_road=<maintenance level 1-5>, ref="FS <id>", name
- USFS trails:   ways tagged  fs_trail=yes, name, national=<yes|no>
- lot lines:     ways tagged  parcel=line   (North Carolina county parcels)
- lots:          closed ways tagged  parcel=lot, name=<owner of record>; not drawn, read by
                 the app to say whose land a point is on
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
from shapely.geometry import shape, box, LineString, MultiLineString, MultiPolygon
from shapely.ops import linemerge, unary_union
from rasterio.windows import from_bounds
import contourpy

MINOR_FT, MAJOR_FT = 40, 200
CLIP_MARGIN = 0.003  # ~300 m
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
    """Every LineString in geom; clipping can hand back points and collections too."""
    if geom.is_empty:
        return []
    if geom.geom_type == "LineString":
        return [geom]
    if geom.geom_type in ("MultiLineString", "GeometryCollection"):
        return [g for part in geom.geoms for g in lines_of(part)]
    return []


def main():
    if len(sys.argv) not in (2, 3, 7):
        raise SystemExit(__doc__)
    src = sys.argv[1]
    out = sys.argv[2] if len(sys.argv) > 2 else src
    # A cell build clips everything to the cell, plus a margin so lines run on past its
    # edge; the Mapsforge writer then cuts cleanly at the cell boundary itself.
    clip = cell = None
    if len(sys.argv) == 7:
        cw, cs, ce, cn = (float(v) for v in sys.argv[3:7])
        clip = box(cw - CLIP_MARGIN, cs - CLIP_MARGIN, ce + CLIP_MARGIN, cn + CLIP_MARGIN)
        cell = box(cw, cs, ce, cn)
    os.makedirs(out, exist_ok=True)
    w = Writer(os.path.join(out, "extra.osm.part"))

    def clipped(geom):
        return geom if clip is None else geom.intersection(clip)

    def features(name):
        path = os.path.join(src, f"{name}.geojson")
        if not os.path.exists(path):
            return []
        feats = json.load(open(path))["features"]
        if clip is None:
            return feats
        return [f for f in feats if f.get("geometry") and shape(f["geometry"]).intersects(clip)]

    # --- ownership: union parcels per forest, so the pack holds outlines not a parcel quilt
    usfs = [f for f in features("ownership") if f["properties"].get("ownerclassification") == "USDA FOREST SERVICE"]
    forests = {}
    for f in usfs:
        forests.setdefault(f["properties"].get("nfslandunitname") or "National Forest", []).append(
            shape(f["geometry"]).buffer(0))
    for name, parts in forests.items():
        # close the hairline gaps between neighbouring parcels before dissolving
        whole = unary_union(parts).buffer(0.00002).buffer(-0.00002)
        # The tint is cut exactly at the cell edge, so neighbouring cells meet without
        # overlapping. The boundary is drawn from the forest's real outline, as lines, so the
        # cut itself never shows up as a boundary.
        area = whole if cell is None else whole.intersection(cell)
        if not area.is_empty:
            w.multipolygon(area, {"ownership": "usfs", "name": name})
        edge = whole.boundary if clip is None else whole.boundary.intersection(clip)
        for g in lines_of(edge):
            g = g.simplify(SIMPLIFY_DEG)
            if g.length > 0:
                w.way(g.coords, {"ownership_edge": "usfs"})
        print(f"   ownership: {name}: {len(parts)} parcels")

    # --- county parcels: every lot line once. Neighbouring lots share an edge, so the
    # boundaries are merged into one set of lines rather than drawn per parcel.
    lots = [(shape(f["geometry"]).buffer(0), (f["properties"].get("ownname") or "").strip())
            for f in features("parcels")]
    parcels = [p for p, _ in lots]
    # each lot once more as an area carrying its owner of record, only for those in the cell
    n_lots = 0
    for p, owner in lots:
        if owner and not p.is_empty and (clip is None or p.intersects(clip)):
            w.multipolygon(p, {"parcel": "lot", "name": owner})
            n_lots += 1
    if lots:
        print(f"   lots with owners: {n_lots}")
    if parcels:
        edges = unary_union([p.boundary for p in parcels if not p.is_empty])
        n = 0
        pieces = lines_of(clipped(edges))
        merged = linemerge(MultiLineString(pieces)) if pieces else None
        for g in lines_of(merged) if merged is not None else []:
            g = g.simplify(SIMPLIFY_DEG)
            if g.length > 0:
                w.way(g.coords, {"parcel": "line"})
                n += 1
        print(f"   parcels: {len(parcels)} lots, {n} lines")

    # --- Forest Service roads
    n = 0
    for f in features("roads"):
        p = f["properties"]
        if (p.get("route_status") or "").startswith("DE"):  # decommissioned
            continue
        ml = (p.get("oper_maint_level") or "")[:1]
        for g in lines_of(clipped(shape(f["geometry"])).simplify(SIMPLIFY_DEG)):
            w.way(g.coords, {"fs_road": ml or "yes", "ref": f"FS {p['id']}" if p.get("id") else None,
                             "name": (p.get("name") or "").title()})
            n += 1
    print(f"   roads: {n} ways")

    # --- Forest Service trails
    n = 0
    for f in features("trails"):
        p = f["properties"]
        for g in lines_of(clipped(shape(f["geometry"])).simplify(SIMPLIFY_DEG)):
            w.way(g.coords, {"fs_trail": "yes", "name": (p.get("trail_name") or "").title(),
                             "ref": p.get("trail_no"),
                             "national": "yes" if p.get("national_trail_designation") not in (None, 0, "0") else None})
            n += 1
    print(f"   trails: {n} ways")

    # --- contours, from only the part of the DEM the cell needs
    with rasterio.open(os.path.join(src, "dem.tif")) as ds:
        window = None
        if clip is not None:
            window = from_bounds(*clip.bounds, transform=ds.transform).round_offsets().round_lengths()
        z = ds.read(1, window=window).astype(np.float64) * 3.28084
        t = ds.window_transform(window) if window is not None else ds.transform
    z[z < -1000] = np.nan
    lon = t.c + t.a * (np.arange(z.shape[1]) + 0.5)
    lat = t.f + t.e * (np.arange(z.shape[0]) + 0.5)
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
    os.replace(os.path.join(out, "extra.osm.part"), os.path.join(out, "extra.osm"))


if __name__ == "__main__":
    main()
