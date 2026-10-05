#!/usr/bin/env bash
# Build a region pack: one Mapsforge .map holding OSM, Forest Service land/roads/trails and
# 20 ft contours. Run fetch.py first.
#
#   build.sh <workdir> <west> <south> <east> <north>
#
# Needs osmosis with the mapsforge-map-writer plugin, and python3 with numpy, shapely,
# rasterio and contourpy (PYTHONPATH is honoured). Set OSMOSIS to override the binary.
set -euo pipefail

work=$1; w=$2; s=$3; e=$4; n=$5
here=$(cd "$(dirname "$0")" && pwd)
osmosis=${OSMOSIS:-osmosis}
name=$(basename "$work")

[ -f "$work/extra.osm" ] || python3 "$here/to_osm.py" "$work"

# Mapsforge's own mapping, plus ours before the closing tag
jar=$(ls ~/.openstreetmap/osmosis/plugins/mapsforge-map-writer*.jar | head -1)
unzip -p "$jar" tag-mapping.xml | sed '/<\/tag-mapping>/d' > "$work/tag-mapping.xml"
cat "$here/tag-mapping-extra.xml" >> "$work/tag-mapping.xml"
echo "</tag-mapping>" >> "$work/tag-mapping.xml"

# The writer wants each input sorted; extra.osm is written in creation order.
"$osmosis" -q \
    --rx "$work/osm.osm" --sort \
    --rx "$work/extra.osm" --sort \
    --merge \
    --mapfile-writer file="$work/$name.map.part" bbox="$s,$w,$n,$e" \
        tag-conf-file="$work/tag-mapping.xml" type=hd threads=4 \
        comment="OpenStreetMap contributors (ODbL); USDA Forest Service; USGS 3DEP"
mv "$work/$name.map.part" "$work/$name.map"
ls -la "$work/$name.map"
