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
- **Fruit and nut trees, while the Forage layer is on.** The layer is off until you add it.
  While it is on, Topo asks for the fruit and nut trees and shrubs in the part of the map on
  the screen, once the map has been still for a moment: from OpenStreetMap through the
  Overpass API (`overpass-api.de`, or, only when that server is busy or does not answer, the
  public Overpass server run by Private.coffee, `overpass.private.coffee`, which is sent the
  same rounded map area), and, in builds with a Falling Fruit key, from Falling Fruit
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

## Permissions, and what each is for

Android asks you for the first four before they are used; the rest it grants on install.

- **Location** (precise and approximate): to put you on the map, record tracks and navigate.
  It stays on the phone.
- **Background location**: Topo never asks for it. If you allow it yourself in Android's
  settings, a track that was recording when the phone restarted picks up again on its own.
- **Camera**: only while you use it, for the sighting compass (a bearing taken through the
  camera, on the Navigation tab and when entering a bearing) and for reading a beacon from a QR
  code. No picture is saved or sent.
- **Physical activity**: to count steps, for pace on the Navigation tab when you choose it.
- **Notifications**: for the notice Android requires while a track is recording or steps are
  being counted.
- **Internet**: for maps, elevation, the Forage layer and region packs, as above.
- **Foreground service** (location and health): to keep recording a track, or counting steps,
  with the screen off.
- **Start at boot**: to carry on recording a track after the phone restarts.
- **Vibrate**: a short buzz when a QR code is read or a dial clicks round.
- **Keep awake**: for Android's background-work library, so its tidy-ups of old map data
  finish; Topo itself never holds the phone awake.

## What stays on the phone

- Tiles you have looked at, in a cache of up to 100 MB that the phone may clear, kept as long
  as each server says.
- Saved areas, until you delete them under Settings > Map > Saved areas.
- Elevation fetched while looking at the map, up to about 150 tiles; the oldest go first.
- Fruit and nut trees the Forage layer fetched, for a month before they are asked for again, and
  thrown out after four months unseen; those kept with a saved area stay.
