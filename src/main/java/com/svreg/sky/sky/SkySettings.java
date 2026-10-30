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
 *   "cloud_mode": "painted",     // "painted", "3d" = 3D near + painted far layers, "3d_full" = only 3D, flat sunset-style slabs to the horizon
 *   "cloud_3d_distance": 40,     // 3D clouds: radius in cells (1 cell = cloud_3d_cell blocks)
 *   "cloud_3d_cell": 8,          // 3D clouds: block size of one cloud cell
 *   "cloud_3d_levels": 3,        // 3D clouds: how many steps tall the thickest clouds get
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
	public static Identifier cloudTexture = Identifier.fromNamespaceAndPath(SvregSky.MOD_ID, "textures/sky/clouds.png");
	public static boolean blockyClouds = false;
	public static String cloudMode = "painted";
	public static int cloud3dDistance = 40;
	public static int cloud3dCell = 8;
	public static int cloud3dLevels = 3;
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
		FLAGS.put("light_rays", true);
		FLAGS.put("water_sparkle_dots", true);
		FLAGS.put("water_sunset", true);
		FLAGS.put("screen_shadows", false);
		FLAGS.put("shadow_debug", false);
		FLAGS.put("anime_water", true);
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
	/** 3D Ghibli clouds: look ("auto" = a different weather each day), amount and size multipliers */
	public static String ghibliStyle = "auto";
	public static float ghibliAmount = 1.0F, ghibliSize = 1.0F;
	/** screen-space sun shadows: quality (low / medium / high / ultra), darkness 0..1, colour (anime / neutral / warm) */
	public static String shadowQuality = "medium", shadowColor = "anime", shadowMethod = "world";
	public static float shadowDarkness = 0.45F;

	/** photo-mode time-lapse: the sky turns on its own (a day in about two minutes, or thirty seconds) */
	public static void tickPhoto() {
		if (photoMode && !"off".equals(photoTimelapse)) {
			photoTime = (photoTime + ("fast".equals(photoTimelapse) ? 40 : 10)) % 24000;
		}
	}

	/** any 3D cloud mode */
	public static boolean cloud3d() {
		return "3d".equals(cloudMode) || "3d_full".equals(cloudMode);
	}

	/** puffy 3D Ghibli clouds (their own mesh, no painted cumulus) */
	public static boolean cloudGhibli() {
		return "3d_ghibli".equals(cloudMode) || "3d_ghibli_mix".equals(cloudMode);
	}

	/** 3D Ghibli clouds near, painted cloud layers further out */
	public static boolean ghibliMix() {
		return "3d_ghibli_mix".equals(cloudMode);
	}

	/** pure 3D sunset-reference clouds, no painted layers */
	public static boolean cloud3dFull() {
		return "3d_full".equals(cloudMode);
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
		if (o.has("ghibli_style")) {
			ghibliStyle = o.get("ghibli_style").getAsString();
		}
		if (o.has("ghibli_amount")) {
			ghibliAmount = Math.max(0.25F, Math.min(2.5F, o.get("ghibli_amount").getAsFloat()));
		}
		if (o.has("ghibli_size")) {
			ghibliSize = Math.max(0.5F, Math.min(2.0F, o.get("ghibli_size").getAsFloat()));
		}
		if (o.has("shadow_quality")) {
			shadowQuality = o.get("shadow_quality").getAsString();
		}
		if (o.has("shadow_method")) {
			shadowMethod = o.get("shadow_method").getAsString();
		}
		if (o.has("shadow_color")) {
			shadowColor = o.get("shadow_color").getAsString();
		}
		if (o.has("shadow_darkness")) {
			shadowDarkness = Math.max(0.1F, Math.min(0.9F, o.get("shadow_darkness").getAsFloat()));
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
			if (o.has("cloud_texture")) {
				cloudTexture = Identifier.parse(o.get("cloud_texture").getAsString());
			}
			blockyClouds = bool(o, "blocky_clouds", blockyClouds);
			cloudMode = o.has("cloud_mode") ? o.get("cloud_mode").getAsString().toLowerCase(java.util.Locale.ROOT) : cloudMode;
			cloud3dDistance = Math.max(8, Math.min(96, (int) num(o, "cloud_3d_distance", cloud3dDistance)));
			cloud3dCell = Math.max(2, Math.min(24, (int) num(o, "cloud_3d_cell", cloud3dCell)));
			cloud3dLevels = Math.max(1, Math.min(8, (int) num(o, "cloud_3d_levels", cloud3dLevels)));
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
