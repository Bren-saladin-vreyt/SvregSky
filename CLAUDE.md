# SvregSky: notes for Claude sessions

Read this first. It replaces re-explaining the project in every chat.

## The user
- Writes casual Indonesian ("bro"); answer the same way.
- Plays on a **Poco X6 Pro** (Mali-G615, Zalith Launcher, MobileGlues over ANGLE/Vulkan), sometimes PC. Phone FPS matters.
- Wants every feature behind an **on/off toggle** in the SvregSky menu.
- Likes a **preview image before big visual work**; keep previews to what really needs eyes (they cost tokens).
- Visual target for clouds: the **CazToon** shader (reference photos/video were shared in the old chat; not in the repo).

## Project
- Fabric client mod for **Minecraft 26.2** + **Sodium 0.9.2**. Java 25. Package `com.svreg.sky`.
- Companion resource pack **CirrusPack** (built outside the repo; resource format 88).
- Gradle can't download in the Claude workspace: **build and test only through GitHub CI** (repo `Bren-saladin-vreyt/SvregSky`).

## CI (`.github/workflows/build.yml`)
- Every push to `main` builds and republishes the jar:
  https://github.com/Bren-saladin-vreyt/SvregSky/releases/download/latest/svregsky-0.1.0.jar
- Put **`[shots]`** in the commit message to also run the client gametest (`src/gametest/java/com/svreg/sky/test/SkyScreenshots.java`); screenshots + `test.log` land on the **`ci-screens`** branch.
  Fetch: `git fetch origin ci-screens:refs/remotes/origin/ci-screens -f && git archive refs/remotes/origin/ci-screens | tar -x -C <dir>`
- CI renders on CPU (llvmpipe): looks are right, FPS numbers are meaningless. The test world is superflat at Y -60.
- `gh` GraphQL is blocked here: use `gh run list/watch/view` and `gh api` (REST).
- Commit trailer:
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and `Claude-Session: <session url>`.

## Code map
- `sky/SkySettings.java` settings + `FLAGS` (string toggles, `SkySettings.on(key)`); `config/SkyConfig.java` saves; `config/SkyScreen.java` menu (`flag()`, `choice()`, `slider()`); lang in `assets/svregsky/lang/{id_id,en_us}.json`.
- `mixin/CloudRendererMixin.java` picks the cloud renderer by `SkySettings.cloudMode`:
  `painted`, `3d_ghibli`, `3d_ghibli_mix`, `3d_raymarch`, `3d`, `3d_full`.
- `sky/CirrusSky.java` + `shaders/core/cirrus_sky.fsh`: sky, painted clouds, sun/moon, **painted horizon** (hills, village, train, birds, airship, lake, waterfall, far shower, pines, lone tree, power poles, meadow, torii, giant moon). Toggles reach the shader as flag bits and, for later features, packed into the rainbow slot (`horizonCode()` / `rbCode` in the shader).
- **3D Ghibli voxel clouds**: `sky/GhibliClouds.java` (mesh, LOD "Halus Dekat", greedy face merge `cloud_merge`), generators `sky/CumulusGen.java` (style `anime`, default) and `sky/GhibliGen.java` (older styles), shader `cirrus_ghibli.fsh`.
- **Raymarch Ghibli (beta)**: `sky/RaymarchClouds.java` (low-res buffer 1/2..1/8, default 1/4, `raymarch_scale`), `sky/RaymarchNoise.java` (baked Worley bubble noise atlas), shaders `cirrus_raymarch.fsh` (march + toon light) and `cirrus_rmcomp.fsh` (sharp upscale + depth write).
- **Raymarch Ghibli Puffs (beta, `3d_crumble`)**: same renderer, shader `cirrus_crumble.fsh`, atlas `sky/CrumbleNoise.java` (puff detail R/G + wisps B; weather map sharp + pre-blurred). Shape = weather-map dome bodies, puffs must clear a bar that drops as the body thickens (cauliflower outline). Detail sizes fade by pixel footprint (ColorModulator.x = buffer height) so nothing under ~3 cloud pixels is drawn (no blinking crumbs). About 3-4x cheaper than `3d_raymarch` in the offline preview. Bodies are upside-down eggs (short round belly hanging from the thick core, tall dome above), leaning slightly downwind (SHEAR); the belly is lit by bounce light (pale blue, seam lines between puffs). A height-turning curl was tried for tails but doubled the cost, so it was dropped. Speed: ~75% of march steps used to be 3-block steps near clouds outside any body; bodyAt now reports how far the body surely is (gGapV: above the dome / below the belly of this column; gGapH: cover too thin, bounded by the map's max slope ~0.02/block) and the march jumps that far (capped at the open-sky stride). Light uses 3 one-sided normal samples and 2 shadow samples. Keep 7 bisection steps: 5 made edges flicker more. The user plays at 1/3 (CazToon's resolution).
- **Anti-flicker (`cloud_steady`, default on)**: `cirrus_rmtemporal.fsh` between march and composite. Two raw march buffers + two results, alternating. A cloud pixel only changes when the change shows two frames in a row; history is followed through camera motion using the stored distance (sky pixels: rotation only). Offline: blinking pixels ~40-70x fewer while turning/walking; a 4°/frame turn leaves ~2% of pixels one frame late (scattered edge dots, no trails). History resets on resize, mode switch, or a frame gap > 0.2 s.
- **Living clouds (`living_clouds`, default on)**: `RaymarchClouds.lifeCycle` scales amount and layer height by day time (small morning puffs, afternoon towers x1.35, thinner night). Puffs churn upward 0.25 blocks/s (`churn`, wraps at the 64-block detail tile). **Afterglow on clouds** (follows the existing `blue_hour` toggle via `Twilight.blueHour()`): peach caps toward the sunset, violet shade. Packed in TextureMat[3].y = 10000 x bits + 100 x afterglow(0..99) + churn; bits: 1 painted hills, 2 lake, 4 pines, 8 horizon bank.
- **Horizon clouds (`horizon_bank`, default on, puff mode)**: not marched -- a 2D cloud wall around the horizon in `cirrus_crumble.fsh` (`bankField`): masses from a weather-map row (u/3 so the 7680-block ring wraps seamlessly), a second row picks tall towers, same puffs/palette (`paint()`), foot rides up with the hill line. With it on, overhead clouds thin out toward their reach. **Clouds hide behind the painted hills**: the hills' silhouette is baked once per toggle change by `cirrus_hillmask.fsh` (copy of landFar/landMid/landNear from cirrus_sky.fsh -- keep in sync) into a 1024x1 strip, read as Sampler1. Putting the hill math directly in the cloud shader made it ~2x slower even unused (shader size), hence the strip. Cost of the bank: ~+18% offline. Rare giant thunderheads with flat anvils (third weather row, u/6) replace the painted `cloud_towers` / `cloud_mega` sprites, which CirrusSky hides while puff mode + horizon bank are on.
- **Distance fade (`aerial_fade` slider 0..200%, default 150%)**: far, low clouds press their tones together and melt into the haze (cirrus_crumble, strength in the fraction of ColorModulator.z), and the composite (`cirrus_rmcomp`, now TRANSLUCENT) draws them see-through.
- **Only the puff clouds are offered now** (user's choice): the menu's cloud mode is puff/off, and every saved older mode loads as `3d_crumble`. The old renderers' code is still there (gametest uses them) but nothing in the menu reaches them.
- **Clouds follow the weather (`cloud_weather`)**: `RaymarchClouds.storm` (0..1) follows rain/thunder and, in single player, the weather timer (gathers over the last 3 minutes before rain); stormy = amount x(1+1.1s), height x(1+0.25s), palette greys (rain slot >= 0.7s); breaks up over ~90 s after.
- **Puff cloud shadows + light shafts**: `sky/PuffShadow.java` computes the puff cover (same weather map + rule as the shader) on the CPU, 256x256 at 4 blocks around the camera in cloud space, every 0.4 s. The terrain shader reads it on texture unit 6 (`svregsky_PuffSh`/`svregsky_PuffTex`, replaces the painted noise shadows); the sky's footprint texture (Sampler2, `rays3d`) takes its cores for light shafts when `cloud_god_rays` is on.
- **Cozy home (`cozy_home`)**: `look/Cozy.java` (client tick) detects inside a house (solid roof within 24 above, walls in >=5 of 8 directions within 7, no sky) and warm fire nearby (lit campfire, fire, lantern, any lit block in 15x7x15). Ambience uses it for roof rain (also in windowless houses) and muffled outside rain; PostLook adds a warm indoor grade + flickering firelight (via the tint) and misted view edges with sliding drops (`cirrus_post`, TextureMat[3].w = mist).
- **Living wind (`living_wind`)**: `wind/WindState.java` = one multiplier (0.35..2.2) from two slow sine swells (~3.4 and ~7.6 min) + 0.9 x cloud storm, smoothed. Grass waves (svregsky_Wind.y/z), wave phase speed, leaf rustle + wind sound, falling leaves/petals (Nature, Effects), wind lines and the puff cloud drift all read `WindState.strength()/speed()/cloudSpeed()` instead of the raw settings.
- Terrain effects (wind, water, snow, shadows): `wind/Wind.java`, `wind/HeightShadows.java`. Post look: `look/PostLook.java`.

## Phone (GLES) gotchas
- Depth textures read as 0 under MobileGlues/ANGLE: no screen-space depth effects on the phone (use the hardware depth test instead).
- `isZZeroToOne()` is false on the phone.
- `sin()` of big arguments is imprecise on mobile: wrap animation phases with `mod(t * k, TAU)`; shader time wraps every 3600 s.

## Workflow tips
- Validate shaders before pushing: `node tools/glsl/check.js <file.vsh> <file.fsh>` (needs Playwright; prints OK or the compile errors).
- Preview shaders offline by rendering a fullscreen quad with Playwright's Chromium (`--use-gl=swiftshader`).

## Status (Nov 2026)
- Raymarch clouds: CazToon uses a low-res raymarch too (about 1/4). The user found the bubble shape (`3d_raymarch`) too tidy and measured 40 FPS on the phone; the new `3d_crumble` mode is the answer (irregular Ghibli puffs, lighter). Toon shading shared by both: smooth-body light (no per-pixel speckle), lavender/gold golden hour, moonlit slate night.
- Offline cloud preview lives in **`tools/preview/`** (see its README): `bake.sh` bakes the noise atlases from the mod's Java, `render.js` renders a march shader like the mod (timing, speckle, flicker), `render_temporal.js` measures anti-flicker blink, `hills.js` + `composite.py` put clouds over the painted-hill silhouette. Render at 1640x680, scale 3, to match the phone. Send the user a before/after sheet from it before visual pushes.
- Open ideas: polish airship/lake further, crepuscular rays, cloud shadows on the sky.
