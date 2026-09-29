#!/usr/bin/env python3
"""Pack the region's 3DEP elevation into the zip the app imports under Settings > Altimeter.

    dem_pack.py <srcdir> [<outdir> [<west> <south> <east> <north>]]

Reads <srcdir>/dem.tif and writes <outdir>/<name>-dem.zip (name = outdir's name), cut to the
cell when one is given: an index.json plus lossless PNG tiles in the app's 16-bit
layout, elevation in metres = (green << 8 | red) / A - B. Row 0 is the north edge.
"""
import io
import json
import os
import sys
import zipfile

import numpy as np
import rasterio
from rasterio.windows import from_bounds
from PIL import Image

A = 10.0   # decimetres: finer than the data is honest about, coarse enough to fit 16 bits
B = 100.0  # offset so ground a little below sea level still encodes
TILE = 1620


def main():
    src_dir = sys.argv[1]
    work = sys.argv[2] if len(sys.argv) > 2 else src_dir
    name = os.path.basename(os.path.normpath(work))
    os.makedirs(work, exist_ok=True)
    with rasterio.open(os.path.join(src_dir, "dem.tif")) as src:
        window = None
        if len(sys.argv) == 7:
            window = from_bounds(*(float(v) for v in sys.argv[3:7]), transform=src.transform)
            window = window.round_offsets().round_lengths()
        z = src.read(1, window=window).astype(np.float64)
        t = src.window_transform(window) if window is not None else src.transform
        nodata = src.nodata
    bad = ~np.isfinite(z) | (z < -1000)
    if nodata is not None:
        bad |= z == nodata
    z[bad] = 0.0
    v = np.clip(np.round((z + B) * A), 0, 65535).astype(np.uint32)

    h, w = v.shape
    files = []
    out = os.path.join(work, f"{name}-dem.zip")
    with zipfile.ZipFile(out + ".part", "w", zipfile.ZIP_DEFLATED) as zf:
        for row in range(0, h, TILE):
            for col in range(0, w, TILE):
                block = v[row:row + TILE, col:col + TILE]
                bh, bw = block.shape
                rgba = np.zeros((bh, bw, 4), dtype=np.uint8)
                rgba[..., 0] = block & 0xFF
                rgba[..., 1] = (block >> 8) & 0xFF
                rgba[..., 3] = 255  # opaque, so nothing premultiplies the data away
                buf = io.BytesIO()
                Image.fromarray(rgba, "RGBA").save(buf, "PNG", optimize=True)
                fname = f"{len(files)}.png"
                zf.writestr(fname, buf.getvalue())
                files.append({
                    "filename": fname, "width": bw, "height": bh, "a": A, "b": B,
                    "latitude_start": t.f + t.e * row,            # north edge
                    "latitude_end": t.f + t.e * (row + bh),       # south edge
                    "longitude_start": t.c + t.a * col,           # west edge
                    "longitude_end": t.c + t.a * (col + bw),      # east edge
                })
        index = {
            # The app stores this as whole arc-seconds; 3DEP here is 1/3", so 1 is the
            # finest it can say, and only sets how finely derived layers sample.
            "resolution_arc_seconds": 1,
            "compression_method": "16-bit",
            "version": f"chikei-3dep-{name}",
            "files": files,
            "has_water_mask": False,
        }
        zf.writestr("index.json", json.dumps(index))
    os.replace(out + ".part", out)
    print(f"{out}: {len(files)} tiles, {os.path.getsize(out) / 1e6:.1f} MB, "
          f"{np.nanmin(z):.0f}-{np.nanmax(z):.0f} m")


if __name__ == "__main__":
    main()
