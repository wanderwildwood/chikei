# Privacy

Topo keeps your location, tracks and marks on the phone. There is no account, no analytics and
no crash reporting, and nothing you record is sent anywhere unless you share it yourself (a GPX
file through Android's share sheet, for instance).

## When it uses the network

Only for maps and the Forage layer, and only while you are using them:

- **Map tiles and elevation, while the map is open.** The map fetches the tiles for what is on
  the screen: USGS topo from The National Map (`basemap.nationalmap.gov`) in the US,
  OpenTopoMap (`tile.opentopomap.org`) elsewhere, and elevation tiles from the Terrain Tiles on
  AWS (`s3.amazonaws.com/elevation-tiles-prod`) for contour lines and the elevation readout.
  Nothing is fetched for places you are not looking at, and the home-screen widget never
  fetches anything.
- **When you save an area.** "Save this area" downloads the USGS tiles (US only) and the
  elevation for what is on the screen, once, after showing you the size.
- **Edible plants, while the Forage layer is on.** The layer is off until you add it. While it
  is on, Topo asks for the edible plants in the part of the map on the screen, once the map
  has been still for a moment: from OpenStreetMap through the Overpass API
  (`overpass-api.de`), and, in builds with a Falling Fruit key, from Falling Fruit
  (`fallingfruit.org`). All either is told is the corners of the map squares on the screen,
  rounded to about 100 m, plus, when you open a Falling Fruit spot, that spot's number.
  What comes back is kept on the phone for a month, so the same place is not asked for again.
- **When you download region packs** from a server you set yourself, and only when you ask.

Each request says it comes from Topo, with a link to this repository, as the map services ask.
Like any web request it reaches the server with your IP address and the tile asked for, which
shows roughly what area you are looking at. None of these services is sent your GPS position,
tracks or marks.

Choose **My region packs only** under Settings > Map > Map and Topo does not use the network for
maps at all.

## What stays on the phone

- Tiles you have looked at, in a cache of up to 100 MB that the phone may clear, kept as long
  as each server says.
- Saved areas, until you delete them under Settings > Map > Saved areas.
- Elevation fetched while looking at the map, up to about 150 tiles; the oldest go first.
- Edible plants the Forage layer fetched, for a month before they are asked for again, and
  thrown out after four months unseen; those kept with a saved area stay.
