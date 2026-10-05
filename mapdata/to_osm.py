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
- other public land (from land_pack.areas: federal, state and local land other than the
                 national forests):  land=public, name=<area> (the tint), and its outline as
                 land_edge=public
- tribal land:   land=tribal, name=<area>, and its outline as land_edge=tribal (no tint)
- contours:      ways tagged  contour=elevation, contour_ext=elevation_{major,minor,fine},
                 contour_index=yes on the index lines, and name=<level> on those

Every new object gets a negative id, so nothing collides with real OSM ids.
Contours are in the region's units (fetch.py records them). In feet they are 20 ft apart:
every 40 ft is elevation_minor and every 200 ft elevation_major, which is what the map draws
when zoomed out (and all an older app knows); the 20 ft between are elevation_fine, drawn only
close in, where every 100 ft is the index line, as on a 20 ft USGS quad. In metres the same
steps are 10, 20, 100 and 50 m.

They come from dem_fine.tif (3DEP 1/9", mostly lidar) when fetch.py got it, smoothed a little
so a 20 ft line follows the ground rather than every rock and root; otherwise from dem.tif.

Who owns what is written in full to the pack's land file by land_pack.py; the map only carries
what is drawn.
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

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import land_pack  # noqa: E402

# factor from metres, then the fine, minor, index and major intervals in those units
UNITS = {"feet": (3.28084, 20, 40, 100, 200), "metres": (1.0, 10, 20, 50, 100)}
SMOOTH_M = 6.0  # Gaussian sigma for the fine DEM; lidar's roughness is finer than a 20 ft line
CLIP_MARGIN = 0.003  # ~300 m
SIMPLIFY_DEG = 0.00003  # ~3 m; the panel cannot show finer and it keeps the file small
CONTOUR_SIMPLIFY_DEG = 0.000012  # ~1.2 m, a pixel when zoomed all the way in


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


def smoothed(z, sigma_px):
    """Gaussian blur that leaves holes as holes: blur the data and its mask, then divide."""
    r = int(3 * sigma_px)
    k = np.exp(-0.5 * (np.arange(-r, r + 1) / sigma_px) ** 2)
    k /= k.sum()
    ok = np.isfinite(z)
    num, den = np.where(ok, z, 0.0), ok.astype(np.float64)
    for axis in (0, 1):
        num = np.apply_along_axis(np.convolve, axis, num, k, mode="same")
        den = np.apply_along_axis(np.convolve, axis, den, k, mode="same")
    with np.errstate(invalid="ignore", divide="ignore"):
        out = num / den
    out[~ok] = np.nan
    return out


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

    # --- other public land and tribal land, from the same areas as the land file. The tint is
    # cut at the cell edge like the forests'; the outline is drawn from the area's own edge.
    region = land_pack.region(src)
    whole_box = clip if clip is not None else box(-180, -90, 180, 90)
    n = 0
    for g, fields in land_pack.areas(src, whole_box, region.get("sources", [])):
        if fields.get("kind") != "fee" or fields.get("owner") == "USDA Forest Service":
            continue
        kind = {"federal": "public", "state": "public", "local": "public", "joint": "public",
                "tribal": "tribal"}.get(fields.get("owner_type"))
        if kind is None:
            continue
        inner = g if cell is None else g.intersection(cell)
        if not inner.is_empty:
            w.multipolygon(inner, {"land": kind, "name": fields.get("name")})
        for e in lines_of(g.boundary):
            e = e.simplify(SIMPLIFY_DEG)
            if e.length > 0:
                w.way(e.coords, {"land_edge": kind})
        n += 1
    print(f"   public and tribal land: {n} areas")

    # --- county parcels: every lot line once. Neighbouring lots share an edge, so the
    # boundaries are merged into one set of lines rather than drawn per parcel.
    parcels = [shape(f["geometry"]).buffer(0) for f in features("parcels")]
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
    fine_dem = os.path.join(src, "dem_fine.tif")
    dem_path = fine_dem if os.path.exists(fine_dem) else os.path.join(src, "dem.tif")
    with rasterio.open(dem_path) as ds:
        window = None
        if clip is not None:
            window = from_bounds(*clip.bounds, transform=ds.transform).round_offsets().round_lengths()
        factor, fine, minor, index, major = UNITS[region.get("units", "feet")]
        z = ds.read(1, window=window).astype(np.float64) * factor
        t = ds.window_transform(window) if window is not None else ds.transform
    z[z < -1000 * factor] = np.nan
    if dem_path == fine_dem:
        z = smoothed(z, SMOOTH_M / (abs(t.e) * 111_320))
    lon = t.c + t.a * (np.arange(z.shape[1]) + 0.5)
    lat = t.f + t.e * (np.arange(z.shape[0]) + 0.5)
    gen = contourpy.contour_generator(lon, lat, z, name="serial", line_type=contourpy.LineType.Separate)
    lo = int(np.nanmin(z) // fine * fine) + fine
    hi = int(np.nanmax(z) // fine * fine)
    n = 0
    for level in range(lo, hi + 1, fine):
        kind = ("elevation_major" if level % major == 0 else
                "elevation_minor" if level % minor == 0 else "elevation_fine")
        is_index = level % index == 0
        for seg in gen.lines(level):
            if len(seg) < 3:
                continue
            simp = LineString(seg).simplify(CONTOUR_SIMPLIFY_DEG)
            if simp.length < 0.0005:  # < ~50 m: noise rings on flat ground
                continue
            # Mapsforge keeps a way's name but not arbitrary numeric tags, so the label rides on name.
            w.way(simp.coords, {"contour": "elevation", "contour_ext": kind,
                                "contour_index": "yes" if is_index else None,
                                "name": str(level) if is_index or kind == "elevation_major" else None})
            n += 1
    print(f"   contours: {n} ways from {os.path.basename(dem_path)}, {lo}-{hi} {region.get('units', 'feet')}")

    w.close()
    os.replace(os.path.join(out, "extra.osm.part"), os.path.join(out, "extra.osm"))


if __name__ == "__main__":
    main()
