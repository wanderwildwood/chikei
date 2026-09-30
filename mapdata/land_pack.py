#!/usr/bin/env python3
"""Write a pack's land file: whose land each part of the cell is, from whatever sources were
fetched for it.

    land_pack.py <srcdir> <outdir> <west> <south> <east> <north>

Writes <outdir>/<id>-land.json.gz, which the app reads when a finger is held on the map. It
holds a list of areas, each a polygon with what is known of it:

    name        the area's own name ("Blue Ridge Parkway", "Qualla Boundary"), or none for a lot
    kind        fee          land held outright by an owner
                lot          a county lot, with the owner of record
                easement     land whose use is limited by a conservation easement
                designation  a status laid over land: wilderness, national monument, a nature
                             reserve, a protected area from WDPA
    owner       who holds it, written out ("Bureau of Land Management", not "BLM")
    owner_type  federal, state, local, joint, tribal, ngo, private or unknown
    manager     who looks after it, where that is someone else or all that is known
    type        what sort of place it is ("National Park", "Wilderness Area", "Nature Reserve")
    access      open, restricted or closed, where the source says
    holder      an easement's holder

The app decides what to show; nothing here is specific to one country. A field a source does
not have is left out.
"""
import gzip
import json
import os
import sys

from shapely.geometry import shape, box, mapping, MultiPolygon, Polygon
from shapely.ops import unary_union

SIMPLIFY_DEG = 0.00003  # ~3 m
DECIMALS = 5            # ~1 m

OWNER_TYPES = {"FED": "federal", "STAT": "state", "LOC": "local", "DIST": "local", "JNT": "joint",
               "TRIB": "tribal", "NGO": "ngo", "PVT": "private", "TERR": "state", "UNK": "unknown"}
ACCESS = {"OA": "open", "RA": "restricted", "XA": "closed"}
NOT_REPORTED = {"", "Not Reported", "Not Applicable", "Unknown", "UNK", None}


def clean(v):
    v = (v or "").strip() if isinstance(v, str) else v
    return None if v in NOT_REPORTED else v


def spelled(local, national):
    """PAD-US has each owner, manager and designation twice: the national list's words and the
    local agency's own. The local one is better when it is a name ("City of Moab") and worse
    when it is a code ("NPS", "UTY01000", "LP")."""
    local, national = clean(local), clean(national)
    if local and " " in local and not local.isupper():
        return local
    return national or local


def features(src, name, cell):
    path = os.path.join(src, f"{name}.geojson")
    if not os.path.exists(path):
        return []
    out = []
    for f in json.load(open(path))["features"]:
        if not f.get("geometry"):
            continue
        g = shape(f["geometry"]).buffer(0)
        if g.intersects(cell):
            out.append((g, f["properties"]))
    return out


def region(src):
    """What fetch.py recorded of the region; a work directory from before it did is taken to be
    a US one with the Forest Service's layers."""
    path = os.path.join(src, "region.json")
    if os.path.exists(path):
        return json.load(open(path))
    return {"us": True, "sources": ["usfs", "parcels"], "units": "feet"}


def areas(src, cell, sources=None):
    """Every area in the cell as (geometry, fields), cut to the cell, pieces of one area
    dissolved together."""
    sources = sources if sources is not None else region(src).get("sources", [])
    groups = {}

    def add(g, **fields):
        fields = {k: v for k, v in fields.items() if v not in (None, "")}
        key = tuple(sorted(fields.items()))
        groups.setdefault(key, []).append(g)

    # Forest Service surface ownership: the national forests, by name
    for g, p in features(src, "ownership", cell):
        if p.get("ownerclassification") == "USDA FOREST SERVICE":
            add(g, kind="fee", name=p.get("nfslandunitname") or "National Forest",
                owner="USDA Forest Service", owner_type="federal", type="National Forest", access="open")

    # PAD-US: everything public or protected, and the easements on private land
    have_usfs = "usfs" in sources
    for g, p in features(src, "padus", cell):
        cat = p.get("Category")
        owner = spelled(p.get("Loc_Own"), p.get("Own_Name_desc"))
        manager = spelled(p.get("Loc_Mang"), p.get("Mang_Name_desc"))
        kind_of = clean(p.get("Des_Tp_desc")) or clean(p.get("Loc_Ds"))
        # PAD-US's catch-all kinds ("State Other or Unknown") say nothing
        if kind_of and "Other or Unknown" in kind_of:
            kind_of = None
        name = clean(p.get("Unit_Nm"))
        # "The State of Utah School and Institutional Trust Lands Administration 1918" is a
        # record number, not a name: the kind of land says more
        if name and name.rstrip("0123456789 ") in {manager, owner, clean(p.get("Loc_Mang")), clean(p.get("Loc_Own"))}:
            name = kind_of
        # a record named only by its catch-all kind: the body that holds the land is its name
        if name and "Other or Unknown" in name:
            name = manager or owner
        common = dict(name=name, type=kind_of, access=ACCESS.get(p.get("Pub_Access")))
        if cat == "Fee":
            # The Forest Service's own ownership layer is exact to the inholding; PAD-US's copy
            # of it is not needed when that was fetched
            if have_usfs and p.get("Own_Name") == "USFS":
                continue
            add(g, kind="fee", owner=owner, owner_type=OWNER_TYPES.get(p.get("Own_Type"), "unknown"),
                manager=manager if manager != owner else None, **common)
        elif cat == "Easement":
            holder = clean(p.get("EsmtHldr")) or manager
            if holder and (holder.startswith("Unknown") or holder == "UNK"):
                holder = None
            add(g, kind="easement", holder=holder, **common)
        else:
            add(g, kind="designation", manager=manager, **common)

    # Tribal land, from the Census: reservations, trust land, home lands
    for g, p in features(src, "tribal", cell):
        add(g, kind="fee", name=clean(p.get("NAME")), owner_type="tribal")

    # County lots with the owner of record. Older fetches kept the county's own field name.
    for g, p in features(src, "parcels", cell):
        add(g, kind="lot", owner=clean(p.get("owner")) or clean(p.get("ownname")))

    # WDPA: protected areas worldwide
    for g, p in features(src, "wdpa", cell):
        own = clean(p.get("own_type"))
        add(g, kind="designation", name=clean(p.get("name_eng")) or clean(p.get("name")),
            type=clean(p.get("desig_eng")), manager=clean(p.get("mang_auth")),
            owner_type={"State": "state", "Private": "private", "Community": "local",
                        "Individual landowners": "private", "Non-profit organisations": "ngo",
                        "Joint ownership": "joint", "Multiple ownership": "joint",
                        "Indigenous Peoples": "tribal"}.get(own) if own else None)

    out = []
    for key, parts in groups.items():
        fields = dict(key)
        # Lots stay separate: two lots with the same owner are still two lots
        pieces = parts if fields.get("kind") == "lot" else [unary_union(parts)]
        for g in pieces:
            g = g.intersection(cell).simplify(SIMPLIFY_DEG)
            if g.is_empty or g.area == 0:
                continue
            out.append((g, fields))
    return out


def rings(g):
    polys = g.geoms if isinstance(g, MultiPolygon) else [g] if isinstance(g, Polygon) else \
        [p for p in getattr(g, "geoms", []) if isinstance(p, Polygon)]
    out = []
    for p in polys:
        # Each polygon as its outer ring then its holes; the app tests them even-odd
        poly = [[[round(x, DECIMALS), round(y, DECIMALS)] for x, y in p.exterior.coords]]
        poly += [[[round(x, DECIMALS), round(y, DECIMALS)] for x, y in r.coords] for r in p.interiors]
        out.append(poly)
    return out


def main():
    if len(sys.argv) != 7:
        raise SystemExit(__doc__)
    src, out_dir = sys.argv[1], sys.argv[2]
    w, s, e, n = (float(v) for v in sys.argv[3:7])
    cell = box(w, s, e, n)
    pid = os.path.basename(os.path.normpath(out_dir))
    info = region(src)
    found = areas(src, cell, info.get("sources", []))
    doc = {
        "version": 1,
        "bounds": [w, s, e, n],
        "sources": info.get("sources", []),
        "areas": [dict(fields, polygons=rings(g)) for g, fields in found],
    }
    path = os.path.join(out_dir, f"{pid}-land.json.gz")
    with gzip.open(path + ".part", "wt", encoding="utf-8") as f:
        json.dump(doc, f, separators=(",", ":"), ensure_ascii=False)
    os.replace(path + ".part", path)
    kinds = {}
    for _, fields in found:
        kinds[fields["kind"]] = kinds.get(fields["kind"], 0) + 1
    print(f"   land: {', '.join(f'{v} {k}' for k, v in sorted(kinds.items())) or 'nothing'}, "
          f"{os.path.getsize(path) / 1e6:.2f} MB")


if __name__ == "__main__":
    main()
