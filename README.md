# SvregSky

Ghibli-style multi-layer 3D clouds for Minecraft 26.2 (Fabric), without shaders.

Download: **Releases → SvregSky (latest build)** → the `.jar` file. Put it in your `mods` folder next to Fabric API.

Set **Clouds** to **Fancy** in Video Settings.

## Custom clouds from a resource pack

The clouds are fully defined by resource packs. A pack overrides these files:

```
assets/svregsky/clouds/layers.json   -> list of cloud layers
assets/svregsky/clouds/*.png         -> density maps (1 pixel = one 12x12 block cloud cell, grey = thickness)
```

`layers.json` fields per layer: `texture`, `height` (blocks above vanilla cloud height), `slices` (thresholds for stacked
4-block slices, bottom to top) or `threshold` (single flat layer), `slice_spacing`, `speed`, `time_offset`, `haze` (0-1),
`opacity` (0-1), `color` (hex, multiplies the vanilla cloud colour). Density = brightness x alpha, so a vanilla
white-on-transparent `clouds.png` works with `"threshold": 0.5`. Press F3+T to reload after editing a pack.
