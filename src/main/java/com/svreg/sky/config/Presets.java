package com.svreg.sky.config;

import com.svreg.sky.sky.SkySettings;

/** One-tap setups: Ringan (light, for phones), Seimbang (balanced), Ghibli Penuh (everything). */
public final class Presets {
	/** the last preset picked in the menu ("custom" when none) */
	public static String current = "custom";

	private Presets() {
	}

	public static void apply(String name) {
		boolean light = name.equals("light"), full = name.equals("full");
		SkySettings.cirrusSky = true;
		SkySettings.paintedClouds = true;
		SkySettings.cloudMode = full ? "3d" : "painted";
		SkySettings.cloudLayers = light ? 1 : full ? 3 : 2;
		SkySettings.windLines = !light;
		SkySettings.windLineAmount = full ? 1.5F : 1.0F;
		SkySettings.fallingLeaves = !light;
		SkySettings.waterSparkles = !light;
		SkySettings.fireflies = true;
		SkySettings.cloudShadows = light ? 0 : full ? 0.8F : 0.5F;
		SkySettings.cloudGodRays = !light;
		SkySettings.rainbow = true;
		SkySettings.horizonMountains = true;
		SkySettings.ambientSounds = true;
		for (String k : SkySettings.FLAGS.keySet()) {
			if (k.equals("per_world") || k.equals("auto_fps")) {
				continue;
			}
			boolean on = switch (k) {
				case "cartoon_outline", "paper_texture" -> false;
				case "bloom" -> full;
				case "dragonflies", "dandelion_seeds", "fish_jumps", "bird_flocks", "puddles", "sky_reflection" -> !light;
				case "butterflies", "light_motes", "rain_ripples", "shore_foam", "water_steam", "smoke_wisps", "window_glow", "film_tone" -> !light && (full || !(k.equals("light_motes") || k.equals("shore_foam")));
				default -> true;
			};
			SkySettings.FLAGS.put(k, on);
		}
		SkySettings.version++;
	}
}
