# Offline cloud preview

Renders the raymarch cloud shaders the way the mod does (low-res march buffer, sharp upscale, optional
anti-flicker and distance fade) in Playwright's Chromium with SwiftShader, so cloud looks can be checked and
measured before pushing. Built while tuning the puff clouds (Nov 2026).

## Setup (once per session)
    ./tools/preview/bake.sh            # bakes out/crumble.png from the mod's Java
Needs node + Playwright (preinstalled in the Claude workspace), java, python3 with Pillow, numpy, scipy.
Big renders need `export NODE_OPTIONS=--max-old-space-size=8000`.

## render.js -- one view, or a few frames
    node tools/preview/render.js <shader.fsh> <outPrefix> '<json options>'
Writes `<outPrefix>_0.png` (and more frames with `allPng`). Prints `marchMs` (time of the march pass only),
`speckle%` (lone cloud pixels) and, with several frames, `flicker` (frame-to-frame change).

Options (defaults in brackets):
- `w`,`h` [820,340] screen size. Use **1640x680** for a phone-like pixel footprint.
- `scale` [4] cloud buffer = 1/scale. The user plays at **3**.
- `bufH` set to 1 for the puff shader (it needs the buffer height in ColorModulator.x).
- `steps` [80] march budget; for the puff shader add fade/4 as the fraction (`80.375` = 150% fade).
- `noise` [crumble.png] atlas from out/.
- `yaw`,`pitch` (degrees), `cam` [0,-110,0] camera in cloud space (y < 0 = under the layer; [0,240,0] = above).
- `tod` day | gold | night | blue (after sunset), `sun` direction, `org` (orange sky 0..1).
- `t3y` the packed TextureMat[3].y: 10000*bits + 100*afterglow(0..99) + churn.
  bits: 1 painted hills (needs the hill strip, baked automatically), 2 lake, 4 pines, 8 horizon bank, 128 big clouds.
  e.g. 120020 = bank on, hills off; 130020 = bank + hills.
- `layer` [125] layer height (living clouds scale it), `amount` [1].
- `code` toon clouds: 1 toon, +2 four bands, +4 cream day, +8 gold sunset, +16 slate night, +32 round bellies; `rim` [1] rim light strength 0..2.
- `fade` distance-fade strength used by the composite (match the shader's).
- `frames`, `yawStep`, `move` [dx,dy,dz per frame] for motion tests; `reps` repeats the march for timing.
- `skyKey` draw the sky magenta (for compositing over a painted background).

## render_temporal.js -- anti-flicker check
Same options plus `temporal: true|false`; runs cirrus_rmtemporal between frames and prints `blink%`
(pixels that change and change back = visible flicker). Compare temporal false vs true with
`frames:5, yawStep:0.05` (turning) or `move:[0,0,-0.3]` (walking). (Its composite has no distance fade.)

## Painted hills behind the clouds
    node tools/preview/hills.js bg.png '{"yaw":120,"pitch":8,"show":1}'   # show 2 = flat sea instead
    node tools/preview/render.js <fsh> c1 '{...}'                          # sky drawn normally
    node tools/preview/render.js <fsh> c2 '{..., "skyKey":true}'           # sky magenta
    python3 tools/preview/composite.py bg.png c1_0.png c2_0.png out.png day   # day | gold
hillsbg.fsh is a simple 3-layer toon version of the painted hills, using the same silhouette math as
cirrus_hillmask.fsh, so occlusion lines up exactly.

## Other helpers
- `crumbs.py a.png b.png ...` counts loose cloud crumbs (blobs <= 3 low-res pixels; assumes scale 4 sampling).
- `sheet.py out.png a.png b.png ...` stacks images vertically.

## Notes learned the hard way
- swiftshader timings: compare shaders only within one session and alternate runs; absolute ms mean nothing,
  ratios are a decent guide (a 3-4x gap held up on the phone).
- Putting large function blocks into the cloud shader can slow it ~2x even when unused (shader size):
  measure after adding code, not only after changing math.
- Bash `IFS=: read` breaks on JSON (colons); use `|` as the field separator in loops.
