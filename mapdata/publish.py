#!/usr/bin/env python3
"""Lay built region packs out as a static pack server directory and write its packs.json.

    publish.py <site_dir> <workdir> [<workdir> ...]    (name each with NAME=<display name>)

Each <workdir> holds <id>.map and optionally <id>-dem.zip and <id>-land.json.gz, where <id> is the directory name,
and, for a quad cell from build_cells.py, cell.json with its name and bounds [W, S, E, N].
A display name can be given as  path/to/linville-falls="Linville Falls".  Serve <site_dir> with any
static file server, e.g. `tailscale serve --https=443 <site_dir>` or `python3 -m http.server`.
"""
import datetime
import hashlib
import json
import os
import shutil
import sys


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def entry(site, pid, src):
    dest_dir = os.path.join(site, pid)
    os.makedirs(dest_dir, exist_ok=True)
    dest = os.path.join(dest_dir, os.path.basename(src))
    shutil.copy2(src, dest)
    return {"path": f"{pid}/{os.path.basename(src)}", "bytes": os.path.getsize(dest), "sha256": sha256(dest)}


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    site = sys.argv[1]
    os.makedirs(site, exist_ok=True)
    index_path = os.path.join(site, "packs.json")
    packs = {p["id"]: p for p in json.load(open(index_path))["packs"]} if os.path.exists(index_path) else {}

    for arg in sys.argv[2:]:
        work, _, name = arg.partition("=")
        pid = os.path.basename(os.path.normpath(work))
        map_file = os.path.join(work, f"{pid}.map")
        if not os.path.exists(map_file):
            raise SystemExit(f"{map_file}: not built")
        dem_file = os.path.join(work, f"{pid}-dem.zip")
        land_file = os.path.join(work, f"{pid}-land.json.gz")
        cell = {}
        cell_json = os.path.join(work, "cell.json")
        if os.path.exists(cell_json):
            cell = json.load(open(cell_json))
        new = {
            "id": pid,
            "name": name or cell.get("name") or pid.replace("-", " ").title(),
            "bounds": cell.get("bounds"),
            "map": entry(site, pid, map_file),
            "elevation": entry(site, pid, dem_file) if os.path.exists(dem_file) else None,
            "land": entry(site, pid, land_file) if os.path.exists(land_file) else None,
            "attribution": cell.get("attribution"),
        }
        # The app offers a pack again when "updated" changes, so it changes exactly when a file
        # does: to the minute, since a pack can be rebuilt twice in a day
        old = packs.get(pid, {})
        same = all((old.get(k) or {}).get("sha256") == (new[k] or {}).get("sha256") for k in ("map", "elevation", "land"))
        new["updated"] = old.get("updated") if same and old.get("updated") else \
            datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%MZ")
        packs[pid] = new
        print(f"{pid}: {packs[pid]['name']}")

    tmp = index_path + ".part"
    json.dump({"packs": sorted(packs.values(), key=lambda p: p["name"])}, open(tmp, "w"), indent=1)
    os.replace(tmp, index_path)


if __name__ == "__main__":
    main()
