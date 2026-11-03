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
- **Raymarch Ghibli Puffs (beta, `3d_crumble`)**: same renderer, shader `cirrus_crumble.fsh`, atlas `sky/CrumbleNoise.java` (puff detail R/G + wisps B; weather map sharp + pre-blurred). Shape = weather-map dome bodies, puffs must clear a bar that drops as the body thickens (cauliflower outline). Detail sizes fade by pixel footprint (ColorModulator.x = buffer height) so nothing under ~3 cloud pixels is drawn (no blinking crumbs). About 3-4x cheaper than `3d_raymarch` in the offline preview.
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
- Offline preview harness used for this work: a Playwright page that renders the march shader into a 1/4 buffer with the baked atlas, plus flicker metrics (frame diff under tiny camera turns, loose-crumb count). Rebuild it from these notes if needed (render at ~1640x680 so pixel footprint is phone-like).
- Open ideas: polish airship/lake further, crepuscular rays, cloud shadows on the sky.
