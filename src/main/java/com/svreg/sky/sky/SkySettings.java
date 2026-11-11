package com.svreg.sky.sky;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.svreg.sky.SvregSky;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * Sky settings, read from resource packs at every resource reload.
 *
 * <pre>
 * assets/svregsky/sky.json
 * {
 *   "cirrus_sky": true,          // Ghibli gradient sky (false = vanilla sky colours)
 *   "painted_clouds": true,      // pixel-cumulus clouds painted on the sky
 *   "cloud_mode": "3d_crumble",  // the raymarched puff clouds (the only cloud renderer left; "painted" = sky-painted clouds only, used by the gametest)
 *   "cloud_3d_distance": 40,     // how far the puff clouds reach
 *   "wind_strength": 1.0,        // grass/leaves wind (needs Sodium): 0 = off, 1 = breeze, 3 = storm
 *   "wind_speed": 1.0,
 *   "wind_lines": true,          // white wind streaks flying past
 *   "wind_line_amount": 1.0,     // 0..3
 *   "far_grass_tint": 0.8,       // distant grass shifts toward a soft Ghibli sage green (0 = off)
 *   "water_color": "#4FD6E3",    // water tint everywhere ("" = vanilla biome colours)
 *   "cloud_layers": 3,           // 1..3 (fewer = faster)
 *   "cloud_coverage": 0.0,       // -0.1 (fewer clouds) .. 0.1 (more clouds)
 *   "cloud_speed": 1.0,          // drift speed, 0 = still
 *   "cloud_texture": "svregsky:textures/sky/clouds.png",
 *   "blocky_clouds": false,      // vanilla-style 3D block clouds (the SvregSky cloud layers)
 *   "stars": true,
 *   "milky_way": 1.0,            // 0 = off
 *   "aurora": 1.0,               // 0 = off
 *   "shooting_stars": true,
 *   "shooting_star_period": 12,  // seconds between shooting star chances
 *   "sun_rays": true,
 *   "sunset_style": "auto",      // auto = amber/orange while the sun is up, magenta at the horizon; or "orange" / "magenta"
 *   "fog_tint": 0.75             // 0..1: how much the distance fog takes the sky's horizon colour
 * }
 * </pre>
 */
public final class SkySettings {
	public static final Identifier CONFIG = Identifier.fromNamespaceAndPath(SvregSky.MOD_ID, "sky.json");

	public static boolean cirrusSky = true;
	public static boolean paintedClouds = true;
	public static int cloudLayers = 3;
	public static float cloudCoverage = 0.0F;
	public static float cloudSpeed = 1.0F;
	/** raymarch puff clouds: how strongly far, low clouds melt into the sky (0..2, 1.5 = default) */
	public static float aerialFade = 1.5F;
	/** painterly filter: how strong the brush look is (0..1) */
	public static float painterlyStrength = 0.6F;
	/** anime look: coloured shadow strength, outline width (px) / strength / colour, dream glow strength */
	public static float animeShadow = 0.7F, outlineStrength = 0.9F, dreamGlow = 0.6F, layeredHaze = 0.85F;
	public static int outlineWidth = 2;
	/** pixel art: pixel size (at 720p), brightness bands, dithering 0..1 */
	public static int pixelSize = 3, pixelBands = 9;
	public static float pixelDither = 0.6F;
	public static String outlineColor = "auto";
	/** language of the SvregSky menu: id_id, en_us, or auto (the game's language) */
	public static String modLanguage = "id_id";
	/** puff cloud colours: normal, mongolia (painted-cumulus palette, smooth), mongolia_pixel (flat steps + dots) */
	public static String cloudPalette = "normal";
	public static Identifier cloudTexture = Identifier.fromNamespaceAndPath(SvregSky.MOD_ID, "textures/sky/clouds.png");
	public static String cloudMode = "3d_crumble";
	public static int cloud3dDistance = 40;
	public static float windStrength = 1.0F;
	public static float windSpeed = 1.0F;
	public static float farGrassTint = 0.8F;
	public static boolean windLines = true;
	public static boolean fireflies = true;
	public static boolean fallingLeaves = true;
	public static boolean waterSparkles = true;
	public static float cloudShadows = 0.6F;
	public static boolean rainbow = true;
	public static boolean cloudGodRays = true;
	public static boolean horizonMountains = true;
	public static float colorGrading = 0.7F;
	public static float morningMist = 0.7F;
	public static boolean ghibliBiomes = true;
	public static boolean ambientSounds = true;
	public static float ambientVolume = 0.8F;
	public static float windLineAmount = 1.0F;
	public static int waterColor = 0x4FD6E3; // -1 = vanilla
	public static volatile int version;

	/** on/off switches for the smaller effects, saved and loaded by name */
	public static final java.util.LinkedHashMap<String, Boolean> FLAGS = new java.util.LinkedHashMap<>();

	static {
		// sounds
		FLAGS.put("weather_sounds", true);
		FLAGS.put("place_sounds", true);
		FLAGS.put("ghibli_music", true);
		// sky & weather
		FLAGS.put("daily_variety", true);
		FLAGS.put("ghibli_sun_moon", true);
		FLAGS.put("lightning_glow", true);
		FLAGS.put("ghibli_snow", true);
		// nature
		FLAGS.put("butterflies", true);
		FLAGS.put("light_motes", true);
		FLAGS.put("rain_ripples", true);
		FLAGS.put("shore_foam", true);
		FLAGS.put("grass_bend", true);
		FLAGS.put("dappled_light", true);
		FLAGS.put("water_steam", true);
		FLAGS.put("smoke_wisps", true);
		// look
		FLAGS.put("cartoon_outline", false);
		FLAGS.put("film_tone", true);
		FLAGS.put("warm_lamps", true);
		FLAGS.put("window_glow", true);
		FLAGS.put("gentle_bob", true);
		// batch 3
		FLAGS.put("seasons", true);
		FLAGS.put("cloud_towers", true);
		FLAGS.put("sky_reflection", true);
		FLAGS.put("valley_fog", true);
		FLAGS.put("bird_flocks", true);
		FLAGS.put("dragonflies", true);
		FLAGS.put("dandelion_seeds", true);
		FLAGS.put("fish_jumps", true);
		FLAGS.put("wet_ground", true);
		FLAGS.put("puddles", true);
		FLAGS.put("leaf_rustle", true);
		FLAGS.put("paper_texture", false);
		FLAGS.put("bloom", false);
		FLAGS.put("photo_dof", true);
		FLAGS.put("auto_fps", true);
		// batch 4
		FLAGS.put("water_reflection", true);
		FLAGS.put("face_light", true);
		FLAGS.put("torch_glow", true);
		FLAGS.put("crickets", true);
		// batch 5: clouds
		FLAGS.put("cloud_mega", true);
		FLAGS.put("tower_lightning", true);
		FLAGS.put("sun_beams", true);
		FLAGS.put("cloud_rows", true);
		FLAGS.put("cirrus_layer", true);
		FLAGS.put("water_sparkle_dots", true);
		FLAGS.put("water_sunset", true);
		FLAGS.put("anime_water", true);
		FLAGS.put("distant_storm", true);
		FLAGS.put("cloud_sea", true);
		FLAGS.put("snow_cover", true);
		FLAGS.put("waterfall_foam", true);
		FLAGS.put("underwater_light", true);
		FLAGS.put("sky_lanterns", true);
		FLAGS.put("rain_drops", true);
		FLAGS.put("cel_shading", true);
		FLAGS.put("blue_hour", true);
		FLAGS.put("silver_clouds", true);
		FLAGS.put("cloud_steady", true);
		FLAGS.put("big_clouds", true);
		FLAGS.put("living_clouds", true);
		FLAGS.put("horizon_bank", true);
		FLAGS.put("cloud_weather", true);
		FLAGS.put("cozy_home", true);
		FLAGS.put("living_wind", true);
		FLAGS.put("walk_ripples", true);
		FLAGS.put("grass_kick", true);
		FLAGS.put("startle_birds", true);
		FLAGS.put("painterly", false);
		FLAGS.put("anime_twos", false);
		FLAGS.put("colored_shadows", false);
		FLAGS.put("anime_outline", false);
		FLAGS.put("outline_boil", true);
		FLAGS.put("dream_glow", false);
		FLAGS.put("layered_haze", true);
		FLAGS.put("painted_grass", false);
		FLAGS.put("dream_sleep", true);
		FLAGS.put("window_light", true);
		FLAGS.put("rare_comet", true);
		FLAGS.put("pixel_art", false);
		FLAGS.put("cloud_smooth_edges", true);
		FLAGS.put("deep_sky", false);
		FLAGS.put("giant_tree", true);
		FLAGS.put("spirit_buddy", true);
		FLAGS.put("kites", true);
		FLAGS.put("glow_sea", true);
		FLAGS.put("fox_rain", true);
		FLAGS.put("festival_night", true);
		FLAGS.put("hill_wind", true);
		FLAGS.put("horizon_train", true);
		FLAGS.put("horizon_village", true);
		FLAGS.put("big_moon", true);
		FLAGS.put("horizon_birds", true);
		FLAGS.put("airships", true);
		FLAGS.put("waterfall_far", true);
		FLAGS.put("horizon_lake", true);
		FLAGS.put("far_shower", true);
		FLAGS.put("horizon_pines", true);
		FLAGS.put("lone_tree", true);
		FLAGS.put("power_poles", true);
		FLAGS.put("hill_meadow", true);
		FLAGS.put("hill_torii", true);
		FLAGS.put("shore_waves", true);
		// comfort
		FLAGS.put("per_world", false);
	}

	public static boolean on(String key) {
		return Boolean.TRUE.equals(FLAGS.get(key)) && !AutoFps.paused(key);
	}

	/** the menu value, ignoring Auto FPS pauses */
	public static boolean chosen(String key) {
		return Boolean.TRUE.equals(FLAGS.get(key));
	}

	// photo mode (not saved): client-side time / weather for the sky
	public static boolean photoMode;
	public static int photoTime = 12300;
	public static String photoWeather = "clear";
	public static String photoTimelapse = "off"; // off / slow / fast
	/** horizon cumulonimbus look: mixed (random each day) / tower / wide / anvil */
	public static String towerStyle = "mixed";
	/** Raymarch Ghibli clouds: screen pixels per cloud pixel (2 = half resolution .. 4 = quarter) */
	public static int raymarchScale = 4;
	/** amount of puff cloud (multiplier) */
	public static float ghibliAmount = 1.0F;
	/** screen-space sun shadows: quality (low / medium / high / ultra), darkness 0..1, colour (anime / neutral / warm) */

	/** photo-mode time-lapse: the sky turns on its own (a day in about two minutes, or thirty seconds) */
	public static void tickPhoto() {
		if (photoMode && !"off".equals(photoTimelapse)) {
			photoTime = (photoTime + ("fast".equals(photoTimelapse) ? 40 : 10)) % 24000;
		}
	}

	/** the raymarched Ghibli puff clouds from baked noise (CrumbleNoise): the mod's only cloud renderer */
	public static boolean cloudCrumble() {
		return "3d_crumble".equals(cloudMode);
	}
	public static boolean stars = true;
	public static float milkyWay = 1.0F;
	public static float aurora = 1.0F;
	public static boolean shootingStars = true;
	public static float shootingStarPeriod = 12.0F;
	public static boolean sunRays = true;
	public static String sunsetStyle = "auto";
	public static float fogTint = 0.75F;

	private SkySettings() {
	}

	public static void load(ResourceManager manager) {
		try (InputStream in = manager.open(CONFIG); Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			apply(JsonParser.parseReader(reader).getAsJsonObject());
		} catch (Exception e) {
			SvregSky.LOG.warn("SvregSky: could not read {} ({}), using defaults", CONFIG, e.toString());
		}
		JsonObject user = com.svreg.sky.config.SkyConfig.read();
		if (user != null) {
			try {
				apply(user);
			} catch (Exception e) {
				SvregSky.LOG.warn("SvregSky: bad config/svregsky.json ({})", e.toString());
			}
		}
		version++;
		SvregSky.LOG.info("SvregSky: sky settings loaded (clouds {} {}x{}, wind {})", cloudMode, paintedClouds, cloudLayers, windStrength);
	}

	/** applies the keys present in o; missing keys keep their current value */
	public static void apply(JsonObject o) {
		if (o.has("raymarch_scale")) {
			raymarchScale = Math.max(2, Math.min(8, o.get("raymarch_scale").getAsInt()));
		}
		if (o.has("ghibli_amount")) {
			ghibliAmount = Math.max(0.25F, Math.min(2.5F, o.get("ghibli_amount").getAsFloat()));
		}
		if (o.has("tower_style")) {
			towerStyle = o.get("tower_style").getAsString();
		}
		if (o.has("preset")) {
			com.svreg.sky.config.Presets.current = o.get("preset").getAsString();
		}
			for (java.util.Map.Entry<String, Boolean> e : FLAGS.entrySet()) {
				if (o.has(e.getKey())) {
					e.setValue(o.get(e.getKey()).getAsBoolean());
				}
			}
			cirrusSky = bool(o, "cirrus_sky", cirrusSky);
			paintedClouds = bool(o, "painted_clouds", paintedClouds);
			cloudLayers = Math.max(1, Math.min(3, (int) num(o, "cloud_layers", cloudLayers)));
			cloudCoverage = Math.max(-0.2F, Math.min(0.2F, num(o, "cloud_coverage", cloudCoverage)));
			cloudSpeed = num(o, "cloud_speed", cloudSpeed);
			aerialFade = Math.max(0, Math.min(2, num(o, "aerial_fade", aerialFade)));
			painterlyStrength = Math.max(0.1F, Math.min(1, num(o, "painterly_strength", painterlyStrength)));
			animeShadow = Math.max(0.1F, Math.min(1, num(o, "anime_shadow", animeShadow)));
			outlineStrength = Math.max(0.1F, Math.min(1, num(o, "outline_strength", outlineStrength)));
			dreamGlow = Math.max(0.1F, Math.min(1, num(o, "dream_glow_strength", dreamGlow)));
			layeredHaze = Math.max(0.1F, Math.min(1, num(o, "layered_haze_strength", layeredHaze)));
			outlineWidth = Math.max(1, Math.min(3, Math.round(num(o, "outline_width", outlineWidth))));
			pixelSize = Math.max(2, Math.min(6, Math.round(num(o, "pixel_size", pixelSize))));
			pixelBands = Math.max(4, Math.min(16, Math.round(num(o, "pixel_bands", pixelBands))));
			pixelDither = Math.max(0, Math.min(1, num(o, "pixel_dither", pixelDither)));
			if (o.has("cloud_palette")) {
				cloudPalette = o.get("cloud_palette").getAsString();
			}
			if (o.has("mod_language")) {
				modLanguage = o.get("mod_language").getAsString();
			}
			if (o.has("outline_color")) {
				outlineColor = o.get("outline_color").getAsString();
			}
			if (o.has("cloud_texture")) {
				cloudTexture = Identifier.parse(o.get("cloud_texture").getAsString());
			}
			// only the raymarched puff clouds are left: every older cloud mode now loads as them
			cloudMode = "3d_crumble";
			cloud3dDistance = Math.max(8, Math.min(96, (int) num(o, "cloud_3d_distance", cloud3dDistance)));
			windStrength = Math.max(0, Math.min(4, num(o, "wind_strength", windStrength)));
			windSpeed = Math.max(0, Math.min(4, num(o, "wind_speed", windSpeed)));
			farGrassTint = Math.max(0, Math.min(1, num(o, "far_grass_tint", farGrassTint)));
			windLines = bool(o, "wind_lines", windLines);
			fireflies = bool(o, "fireflies", fireflies);
			fallingLeaves = bool(o, "falling_leaves", fallingLeaves);
			waterSparkles = bool(o, "water_sparkles", waterSparkles);
			cloudShadows = Math.max(0, Math.min(1, num(o, "cloud_shadows", cloudShadows)));
			rainbow = bool(o, "rainbow", rainbow);
			cloudGodRays = bool(o, "cloud_god_rays", cloudGodRays);
			horizonMountains = bool(o, "horizon_mountains", horizonMountains);
			colorGrading = Math.max(0, Math.min(1, num(o, "color_grading", colorGrading)));
			morningMist = Math.max(0, Math.min(1, num(o, "morning_mist", morningMist)));
			ghibliBiomes = bool(o, "ghibli_biome_colors", ghibliBiomes);
			ambientSounds = bool(o, "ambient_sounds", ambientSounds);
			ambientVolume = Math.max(0, Math.min(1, num(o, "ambient_volume", ambientVolume)));
			windLineAmount = Math.max(0, Math.min(3, num(o, "wind_line_amount", windLineAmount)));
			if (o.has("water_color")) {
				String wc = o.get("water_color").getAsString().trim();
				waterColor = wc.isEmpty() ? -1 : Integer.parseInt(wc.replace("#", ""), 16);
			}
			stars = bool(o, "stars", stars);
			milkyWay = Math.max(0, num(o, "milky_way", milkyWay));
			aurora = Math.max(0, num(o, "aurora", aurora));
			shootingStars = bool(o, "shooting_stars", shootingStars);
			shootingStarPeriod = Math.max(1, num(o, "shooting_star_period", shootingStarPeriod));
			sunRays = bool(o, "sun_rays", sunRays);
			sunsetStyle = o.has("sunset_style") ? o.get("sunset_style").getAsString().toLowerCase(java.util.Locale.ROOT) : sunsetStyle;
			fogTint = Math.max(0, Math.min(1, num(o, "fog_tint", fogTint)));
	}

	private static boolean bool(JsonObject o, String key, boolean def) {
		return o.has(key) ? o.get(key).getAsBoolean() : def;
	}

	private static float num(JsonObject o, String key, float def) {
		return o.has(key) ? o.get(key).getAsFloat() : def;
	}
}
