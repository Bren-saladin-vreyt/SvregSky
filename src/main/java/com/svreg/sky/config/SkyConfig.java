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

	/** a file-name-safe key for the world or server being played, or null on the title screen */
	public static String worldKey() {
		try {
			net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
			String k = null;
			if (mc.getSingleplayerServer() != null) {
				k = "sp_" + mc.getSingleplayerServer().getWorldData().getLevelName();
			} else if (mc.getCurrentServer() != null) {
				k = "mp_" + mc.getCurrentServer().ip;
			}
			return k == null ? null : k.replaceAll("[^A-Za-z0-9._-]", "_");
		} catch (Throwable t) {
			return null;
		}
	}

	private static Path worldFile(String key) {
		return FabricLoader.getInstance().getConfigDir().resolve("svregsky").resolve("worlds").resolve(key + ".json");
	}

	/** joining a world: with per-world settings on, that world's saved settings are applied over the global ones */
	public static void onJoin() {
		String key = worldKey();
		if (!SkySettings.on("per_world") || key == null) {
			return;
		}
		JsonObject o = read(worldFile(key));
		if (o != null) {
			o.remove("per_world");
			SkySettings.apply(o);
			SkySettings.version++;
			SvregSky.LOG.info("SvregSky: per-world settings for {}", key);
		}
	}

	/** leaving a world: back to the global settings */
	public static void onLeave() {
		if (!SkySettings.on("per_world")) {
			return;
		}
		JsonObject o = read(file());
		if (o != null) {
			SkySettings.apply(o);
			SkySettings.version++;
		}
	}

	public static JsonObject read() {
		return read(file());
	}

	private static JsonObject read(Path f) {
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
		for (java.util.Map.Entry<String, Boolean> e : SkySettings.FLAGS.entrySet()) {
			o.addProperty(e.getKey(), e.getValue());
		}
		o.addProperty("preset", Presets.current);
		o.addProperty("tower_style", SkySettings.towerStyle);
		o.addProperty("ghibli_style", SkySettings.ghibliStyle);
		o.addProperty("ghibli_amount", SkySettings.ghibliAmount);
		o.addProperty("ghibli_size", SkySettings.ghibliSize);
		o.addProperty("shadow_quality", SkySettings.shadowQuality);
		o.addProperty("shadow_color", SkySettings.shadowColor);
		o.addProperty("shadow_method", SkySettings.shadowMethod);
		o.addProperty("shadow_darkness", SkySettings.shadowDarkness);
		o.addProperty("water_color", SkySettings.waterColor < 0 ? "" : String.format("#%06X", SkySettings.waterColor));
		String key = worldKey();
		if (SkySettings.on("per_world") && key != null) {
			write(worldFile(key), o);
			// the global file only learns that per-world mode is on
			JsonObject g = read(file());
			if (g == null) {
				g = o.deepCopy();
			}
			g.addProperty("per_world", true);
			write(file(), g);
		} else {
			write(file(), o);
		}
	}

	private static void write(Path f, JsonObject o) {
		try {
			Files.createDirectories(f.getParent());
			try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
				GSON.toJson(o, w);
			}
		} catch (Exception e) {
			SvregSky.LOG.warn("SvregSky: could not save settings ({})", e.toString());
		}
	}
}
