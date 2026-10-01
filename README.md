# Topo

地形 *chikei*

Topographic maps for the trail on an E Ink phone: contours, public land, forest roads,
trails and lot lines, drawn in black and white like a paper quadrangle, and there with no
signal.

*Chikei* is the lie of the land: the ordinary word for the shape of the ground a topo map
sets out to show.

Built for the [Mudita Kompakt](https://mudita.com/products/kompakt/), whose 4.3" panel has
sixteen greys, a slow redraw, and is read on a ridge as often as at a desk.

## Screenshots

| | | | |
|---|---|---|---|
| ![Linville Gorge on the map](screenshots/01-map.png) | ![Choosing regions to download](screenshots/02-regions.png) | ![Mark here, or record the track](screenshots/03-here.png) | ![A recorded track](screenshots/04-track.png) |

## What it shows

- **Contours** from the region's elevation model, in feet in the US (every 40, labelled every
  200, like a USGS quad) and in metres elsewhere (every 20, labelled every 100)
- **Public land** as a light grey tint with its boundary: national forests, BLM land, national
  and state parks, wildlife areas, state trust land and local parks. Private land stays white;
  tribal land is outlined but not tinted, since it is not public land
- **Lot lines** on private land, where a state or county publishes them
- **Forest roads** with their numbers, and **Forest Service trails** by name
- Everything OpenStreetMap knows: roads, tracks, paths, water, peaks, places, and the
  boundaries of parks and reserves anywhere in the world

## What it does

- **Download region** shows the regions on the server as squares on a map: USGS 7.5-minute
  quadrangles in the US, and elsewhere 1/8-degree squares named after the largest place in
  each.
  Tap the squares you need; each comes with its own elevation, and neighbours join without
  a seam.
- **Here**, on the map: mark where you are standing, or start and stop recording your track.
- **Hold a finger on the map** to see whose land it is, as far as the region's records go:
  the park, forest or reserve and who holds and manages it; the owner of record of a private
  lot; a conservation easement and its holder; wilderness and other designations laid over
  it; and whether the public may enter. Where nothing is recorded it says so. Beneath that,
  how steep the ground is, which way it faces, and its UTM coordinate.
- **Track detail** sets how closely a recording follows you: a point a minute, or every 30,
  10 or 5 seconds.
- **Share GPX** hands a recorded track to CalTopo, or to anything else that takes GPX.
- Navigate to a marked place, with distance, bearing and elevation.

## What it needs

- **Location**, to put you on the map and record tracks. It stays on the phone.
- **Internet**, only to download regions from the map server you set, and only when you ask.
- **A map server.** Regions are plain files built by the scripts in [`mapdata/`](mapdata)
  from public data and served by anything that serves files: `tailscale serve`, nginx, a
  folder on a web host. There is no central server; you point Topo at yours.

## What it does not do

- It does not route. It shows the ground, and you choose the way.
- It does not replace a paper map and a compass.
- It does not send your location anywhere. There is no account and no analytics.

## On a Mudita Kompakt

DuraSpeed, a MediaTek service on the Kompakt, closes installed apps a few minutes after the
screen goes dark and keeps them closed until they are opened again. For Topo that means a track
recorded with the screen off can stop partway. Mudita's own apps are on its allow list; this one
has to be added, once.

Kompakt's Settings has no way in to DuraSpeed: no menu entry, and no search box to look for it
in. Its own screen will not open for another app either, but its App info page will. Messaging
and Whereabouts each have a button that goes there; without either, from a computer with `adb`:

    adb shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:com.mediatek.duraspeed

Then, on the phone:

1. Tap **Open** on DuraSpeed's App info page.
2. Switch **Topo** on in the list. **On means allowed** to run in the background, which is
   easy to read the wrong way round. Switching DuraSpeed off at the top works too, for every app.

## Getting it, and keeping it

Download <https://github.com/wanderwildwood/chikei/releases/latest/download/chikei.apk> and
sideload it. That address always points at the newest release, and every release publishes a
`.sha256` beside the APK if you would rather check than trust.

For updates without doing this by hand, add this repository to
[Obtainium](https://github.com/ImranR98/Obtainium):

    https://github.com/wanderwildwood/chikei

**The application id is settled**: updates install over what you have, keeping your maps,
tracks and marks.

## Building

```
./gradlew assembleRelease
```

A release is signed by a keystore in `signing/`, which is not in this repository. Without
it the release APK builds **unsigned** and will not install anywhere; there is no fallback
key by design.

### Building regions

```
mapdata/fetch.py <west> <south> <east> <north> <area> [--sources a,b,...]   # data for an area
mapdata/build_cells.py <area> <cells>                                       # one pack per cell
mapdata/publish.py <site> <cells>/*/                                        # a static site with packs.json
```

They need Python 3 with numpy, shapely, rasterio and contourpy, and
[osmosis](https://github.com/openstreetmap/osmosis) with the
[Mapsforge map writer](https://github.com/mapsforge/mapsforge) plugin. Nothing needs an
account or a key.

Each kind of data is a **source**. An area in the United States takes the US sources, and
anywhere else takes the world ones; `--sources` picks your own.

| Source | Where | What it adds |
|---|---|---|
| `usfs` | US | national forests' own ownership, roads and trails ([USDA Forest Service](https://data.fs.usda.gov/geodata/edw/)) |
| `padus` | US | all public and protected land, with owner, manager, easements, wilderness and other designations, and public access ([USGS PAD-US](https://www.usgs.gov/programs/gap-analysis-project/science/pad-us-data-overview)) |
| `tribal` | US | reservations, off-reservation trust land and Hawaiian home lands ([Census TIGER](https://tigerweb.geo.census.gov/)) |
| `parcels` | US | county lots with the owner of record, from each service listed in `PARCELS` in `fetch.py` whose area meets yours |
| `wdpa` | world | national parks, reserves and other protected areas, with their designation and managing body ([Protected Planet](https://www.protectedplanet.net/)) |
| `quads` / `grid` | US / world | how the area is cut into packs |
| `osm` | both | OpenStreetMap, from the smallest [Geofabrik](https://download.geofabrik.de/) extracts that cover the area |
| `dem` | both | elevation: [USGS 3DEP](https://www.usgs.gov/3d-elevation-program) 10 m in the US, [Copernicus GLO-30](https://spacedata.copernicus.eu/collections/copernicus-digital-elevation-model) elsewhere |

**Parcels** are published county by county and state by state, and many do not publish
owners at all. North Carolina's statewide service is listed; to add your own county, put its
ArcGIS parcel layer, the name of the field holding the owner, and its bounds in `PARCELS`. Only
the outline and the owner are ever fetched: never mailing addresses or values.

**Terms.** PAD-US, TIGER, 3DEP and the Forest Service's data are public domain;
OpenStreetMap is ODbL. Copernicus DEM is free to use with its credit, which each pack carries.
WDPA is free for personal and non-commercial use; before serving packs built with it to the
public, read [its terms](https://www.protectedplanet.net/en/legal).

Each pack is three files: the Mapsforge map, the elevation, and a small land file saying
whose land each part of the cell is (see `mapdata/land_pack.py`). The app knows nothing of
any one country's agencies; it says what the land file says.

## Credit

After [Trail Sense](https://github.com/kylecorry31/Trail-Sense) by Kyle Corry, which this
is: its sensors, paths, beacons, navigation and Mapsforge rendering are Trail Sense's, cut
down to the map and what you do with it. Trail Sense is under the MIT License, kept in
[`LICENSES/MIT-Trail-Sense.txt`](LICENSES/MIT-Trail-Sense.txt).

Map data © OpenStreetMap contributors (ODbL). In the US: public and protected land from USGS
PAD-US, national forests, forest roads and trails from the USDA Forest Service, tribal land
from the US Census Bureau, elevation and quadrangles from the USGS, and parcels from the
states and counties that publish them. Elsewhere: protected areas from UNEP-WCMC and IUCN,
Protected Planet (WDPA), and elevation from Copernicus DEM GLO-30, © DLR e.V. 2010-2014 and
© Airbus Defence and Space GmbH 2014-2018, provided under COPERNICUS by the European Union
and ESA.

## Licence

GPL-3.0-only. See [LICENSE](LICENSE).

Copyright (C) 2026 wander wildwood

This program is free software: you can redistribute it and/or modify it under the terms of
the GNU General Public License as published by the Free Software Foundation, version 3.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
See the GNU General Public License for more details.

You should have received a copy of the GNU General Public License along with this program.
If not, see <https://www.gnu.org/licenses/>.

A map you carry into the woods should stay free to fix and share, so the changes made here
are copyleft. Trail Sense's own code keeps its MIT licence.
