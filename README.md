# SvregSky

All-in-one Ghibli sky for Minecraft 26.2 (Fabric), without shaders (no Iris needed):

- Cirrus gradient sky: day, golden hour, magenta → yellow sunset, pink sunrise, deep blue night
- sun glow and sun rays
- painted pixel-cumulus clouds in 3 layers (near, mid, far haze), drifting, with parallax as you walk
- soft twinkling stars, milky way, animated aurora, shooting stars
- distance fog that takes the sky's horizon colour

Download: **Releases → SvregSky (latest build)** → the `.jar` file. Put it in your `mods` folder next to Fabric API.
Remove Nuit (or any other sky mod/pack) — SvregSky draws the whole sky itself.

## Settings (resource pack)

Everything is set from a resource pack file, `assets/svregsky/sky.json` (press F3+T after editing):

| key | default | meaning |
|---|---|---|
| `cirrus_sky` | `true` | Ghibli gradient sky (`false` = vanilla sky colours) |
| `painted_clouds` | `true` | painted pixel-cumulus clouds |
| `cloud_layers` | `3` | 1–3, fewer = faster |
| `cloud_coverage` | `0.0` | -0.2 (fewer clouds) … 0.2 (more clouds) |
| `cloud_speed` | `1.0` | drift speed, 0 = still |
| `cloud_texture` | `svregsky:textures/sky/clouds.png` | baked cloud shapes (see below) |
| `blocky_clouds` | `false` | vanilla-style 3D block clouds (the layers below) |
| `stars` | `true` | |
| `milky_way` | `1.0` | strength, 0 = off |
| `aurora` | `1.0` | strength, 0 = off |
| `shooting_stars` | `true` | |
| `shooting_star_period` | `12` | seconds between chances |
| `sun_rays` | `true` | |
| `sunset_style` | `auto` | `auto` = orange golden hour, magenta at the horizon; `orange`; `magenta` |
| `fog_tint` | `0.75` | 0–1, how much the distance fog takes the horizon colour |
| `toon_items` | `true` | cartoon look (brighter colours, bold outline) for every 16x item texture |

### Cloud shapes

`textures/sky/clouds.png` is a tileable map: red = cloud density (about 30% of it above 0.545 gives the default
coverage), green/blue = the big cluster shape as a 16-bit value (green = high byte) used for the light and shadow bands.
One texture tile covers 40 sky units; any power-of-two size works.

## Block clouds (optional)

With `"blocky_clouds": true` the vanilla clouds are replaced by stacked 3D cloud layers, also defined by resource packs:

```
assets/svregsky/clouds/layers.json   -> list of cloud layers
assets/svregsky/clouds/*.png         -> density maps (1 pixel = one 12x12 block cloud cell, grey = thickness)
```

`layers.json` fields per layer: `texture`, `height` (blocks above vanilla cloud height), `slices` (thresholds for stacked
4-block slices, bottom to top) or `threshold` (single flat layer), `slice_spacing`, `speed`, `time_offset`, `haze` (0-1),
`opacity` (0-1), `color` (hex, multiplies the vanilla cloud colour). Density = brightness x alpha, so a vanilla
white-on-transparent `clouds.png` works with `"threshold": 0.5`. Set **Clouds** to **Fancy** for these.
