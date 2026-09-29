#!/usr/bin/env python3
"""Lay built region packs out as a static pack server directory and write its packs.json.

    publish.py <site_dir> <workdir> [<workdir> ...]    (name each with NAME=<display name>)

Each <workdir> holds <id>.map and optionally <id>-dem.zip, where <id> is the directory name.
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
        packs[pid] = {
            "id": pid,
            "name": name or pid.replace("-", " ").title(),
            "updated": datetime.date.today().isoformat(),
            "map": entry(site, pid, map_file),
            "elevation": entry(site, pid, dem_file) if os.path.exists(dem_file) else None,
        }
        print(f"{pid}: {packs[pid]['name']}")

    tmp = index_path + ".part"
    json.dump({"packs": sorted(packs.values(), key=lambda p: p["name"])}, open(tmp, "w"), indent=1)
    os.replace(tmp, index_path)


if __name__ == "__main__":
    main()
