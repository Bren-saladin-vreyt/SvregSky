# SvregSky

All-in-one Ghibli sky for Minecraft 26.2 (Fabric), without shaders (no Iris needed):

- Cirrus gradient sky: day, golden hour, magenta → yellow sunset, pink sunrise, deep blue night
- sun glow and sun rays
- raymarched Ghibli puff clouds (low-res march, toon light, anti-flicker) that grow through the day and follow the weather
- soft twinkling stars, milky way, animated aurora, shooting stars
- distance fog that takes the sky's horizon colour

Download: **Releases → SvregSky (latest build)** → the `.jar` file. Put it in your `mods` folder next to Fabric API.
Remove Nuit (or any other sky mod/pack) — SvregSky draws the whole sky itself.

## In-game settings

Options → **☁ SvregSky...** (top-left corner). Everything below can be changed there; the menu saves to
`config/svregsky.json`, which wins over the resource pack's `sky.json`.

Extra effects (all toggleable): fireflies over grass at night, falling leaves and cherry petals, sun glints on water,
drifting cloud shadows on the land (Sodium), light shafts through cloud gaps at golden hour, a rainbow after rain,
wind lines, meadow wind. Clouds: Ghibli puff clouds or Off.

## Settings (resource pack)

Everything is set from a resource pack file, `assets/svregsky/sky.json` (press F3+T after editing):

| key | default | meaning |
|---|---|---|
| `cirrus_sky` | `true` | Ghibli gradient sky (`false` = vanilla sky colours) |
| `painted_clouds` | `true` | clouds on / off |
| `cloud_3d_distance` | `40` | how far the puff clouds reach |
| `cloud_layers` | `3` | 1–3, fewer = faster |
| `cloud_coverage` | `0.0` | -0.2 (fewer clouds) … 0.2 (more clouds) |
| `cloud_speed` | `1.0` | drift speed, 0 = still |
| `cloud_texture` | `svregsky:textures/sky/clouds.png` | baked cloud shapes (see below) |
| `stars` | `true` | |
| `milky_way` | `1.0` | strength, 0 = off |
| `aurora` | `1.0` | strength, 0 = off |
| `shooting_stars` | `true` | |
| `shooting_star_period` | `12` | seconds between chances |
| `sun_rays` | `true` | |
| `sunset_style` | `auto` | `auto` = orange golden hour, magenta at the horizon; `orange`; `magenta` |
| `fog_tint` | `0.75` | 0–1, how much the distance fog takes the horizon colour |
| `toon_items` | `true` | cartoon look (brighter colours, bold outline) for every 16x item texture |
| `wind_strength` | `1.0` | grass, flowers and leaves sway (needs Sodium); 0 off … 4 storm |
| `wind_speed` | `1.0` | |
| `far_grass_tint` | `0.8` | distant grass and leaves fade toward a soft sage green |
| `water_color` | `#4FD6E3` | one colour for all water; `""` = vanilla biome colours (F3+A after changing) |

### Cloud shapes

`textures/sky/clouds.png` is a tileable map: red = cloud density (about 30% of it above 0.545 gives the default
coverage), green/blue = the big cluster shape as a 16-bit value (green = high byte) used for the light and shadow bands.
One texture tile covers 40 sky units; any power-of-two size works.
