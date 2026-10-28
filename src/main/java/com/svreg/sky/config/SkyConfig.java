package com.svreg.sky.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.svreg.sky.SvregSky;
import com.svreg.sky.sky.SkySettings;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The in-game settings (SvregSky button in Options) are saved to config/svregsky.json.
 * They are applied on top of the resource pack's sky.json, so the menu always wins.
 */
public final class SkyConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private SkyConfig() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("svregsky.json");
	}

	public static JsonObject read() {
		Path f = file();
		if (!Files.exists(f)) {
			return null;
		}
		try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
			return JsonParser.parseReader(r).getAsJsonObject();
		} catch (Exception e) {
			SvregSky.LOG.warn("SvregSky: could not read {} ({})", f, e.toString());
			return null;
		}
	}

	public static void save() {
		JsonObject o = new JsonObject();
		o.addProperty("cirrus_sky", SkySettings.cirrusSky);
		o.addProperty("painted_clouds", SkySettings.paintedClouds);
		o.addProperty("cloud_mode", SkySettings.cloudMode);
		o.addProperty("cloud_layers", SkySettings.cloudLayers);
		o.addProperty("cloud_coverage", SkySettings.cloudCoverage);
		o.addProperty("cloud_speed", SkySettings.cloudSpeed);
		o.addProperty("cloud_3d_distance", SkySettings.cloud3dDistance);
		o.addProperty("cloud_3d_cell", SkySettings.cloud3dCell);
		o.addProperty("cloud_3d_levels", SkySettings.cloud3dLevels);
		o.addProperty("stars", SkySettings.stars);
		o.addProperty("milky_way", SkySettings.milkyWay);
		o.addProperty("aurora", SkySettings.aurora);
		o.addProperty("shooting_stars", SkySettings.shootingStars);
		o.addProperty("sun_rays", SkySettings.sunRays);
		o.addProperty("sunset_style", SkySettings.sunsetStyle);
		o.addProperty("fog_tint", SkySettings.fogTint);
		o.addProperty("wind_strength", SkySettings.windStrength);
		o.addProperty("wind_speed", SkySettings.windSpeed);
		o.addProperty("wind_lines", SkySettings.windLines);
		o.addProperty("fireflies", SkySettings.fireflies);
		o.addProperty("falling_leaves", SkySettings.fallingLeaves);
		o.addProperty("water_sparkles", SkySettings.waterSparkles);
		o.addProperty("cloud_shadows", SkySettings.cloudShadows);
		o.addProperty("rainbow", SkySettings.rainbow);
		o.addProperty("cloud_god_rays", SkySettings.cloudGodRays);
		o.addProperty("horizon_mountains", SkySettings.horizonMountains);
		o.addProperty("color_grading", SkySettings.colorGrading);
		o.addProperty("morning_mist", SkySettings.morningMist);
		o.addProperty("ghibli_biome_colors", SkySettings.ghibliBiomes);
		o.addProperty("ambient_sounds", SkySettings.ambientSounds);
		o.addProperty("ambient_volume", SkySettings.ambientVolume);
		o.addProperty("wind_line_amount", SkySettings.windLineAmount);
		o.addProperty("far_grass_tint", SkySettings.farGrassTint);
		o.addProperty("water_color", SkySettings.waterColor < 0 ? "" : String.format("#%06X", SkySettings.waterColor));
		try {
			Files.createDirectories(file().getParent());
			try (Writer w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
				GSON.toJson(o, w);
			}
		} catch (Exception e) {
			SvregSky.LOG.warn("SvregSky: could not save settings ({})", e.toString());
		}
	}
}
