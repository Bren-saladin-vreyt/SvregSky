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
	public static boolean stars = true;
	public static float milkyWay = 1.0F;
	public static float aurora = 1.0F;
	public static boolean shootingStars = true;
	public static float shootingStarPeriod = 12.0F;
	public static boolean sunRays = true;
	public static float fogTint = 0.75F;

	private SkySettings() {
	}

	public static void load(ResourceManager manager) {
		try (InputStream in = manager.open(CONFIG); Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			JsonObject o = JsonParser.parseReader(reader).getAsJsonObject();
			cirrusSky = bool(o, "cirrus_sky", true);
			paintedClouds = bool(o, "painted_clouds", true);
			cloudLayers = Math.max(1, Math.min(3, (int) num(o, "cloud_layers", 3)));
			cloudCoverage = Math.max(-0.2F, Math.min(0.2F, num(o, "cloud_coverage", 0)));
			cloudSpeed = num(o, "cloud_speed", 1);
			cloudTexture = o.has("cloud_texture") ? Identifier.parse(o.get("cloud_texture").getAsString())
					: Identifier.fromNamespaceAndPath(SvregSky.MOD_ID, "textures/sky/clouds.png");
			blockyClouds = bool(o, "blocky_clouds", false);
			stars = bool(o, "stars", true);
			milkyWay = Math.max(0, num(o, "milky_way", 1));
			aurora = Math.max(0, num(o, "aurora", 1));
			shootingStars = bool(o, "shooting_stars", true);
			shootingStarPeriod = Math.max(1, num(o, "shooting_star_period", 12));
			sunRays = bool(o, "sun_rays", true);
			fogTint = Math.max(0, Math.min(1, num(o, "fog_tint", 0.75F)));
			SvregSky.LOG.info("SvregSky: sky settings loaded from {} (clouds {}x{}, blocky {})", CONFIG, paintedClouds, cloudLayers, blockyClouds);
		} catch (Exception e) {
			SvregSky.LOG.warn("SvregSky: could not read {} ({}), using defaults", CONFIG, e.toString());
		}
	}

	private static boolean bool(JsonObject o, String key, boolean def) {
		return o.has(key) ? o.get(key).getAsBoolean() : def;
	}

	private static float num(JsonObject o, String key, float def) {
		return o.has(key) ? o.get(key).getAsFloat() : def;
	}
}
