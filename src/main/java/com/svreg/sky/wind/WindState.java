package com.svreg.sky.wind;

import com.svreg.sky.sky.SkySettings;

/**
 * One wind for the whole world: it swells and calms by itself over a few minutes and blows harder in a storm.
 * Grass waves, leaf rustle, falling leaves and petals, wind lines, the wind sound and the drift of the puff
 * clouds all follow this one value, so a gust is seen in the sky and on the ground at the same time.
 */
public final class WindState {
	private static final long START = System.nanoTime();
	private static float level = 1;
	private static long lastNanos;
	/** test hook: a fixed wind multiplier (0 = off) */
	public static float force;

	private WindState() {
	}

	public static boolean on() {
		return SkySettings.on("living_wind");
	}

	/** multiplier on the menu's wind settings (about 0.4 calm .. 2.2 stormy); 1 when the living wind is off */
	public static float factor() {
		if (force > 0) {
			return force;
		}
		if (!on()) {
			return 1;
		}
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0 : (float) Math.min(0.5, (now - lastNanos) / 1e9);
		lastNanos = now;
		double t = (now - START) / 1e9;
		// slow swells: two long, unrelated waves (periods of about 3.4 and 7.6 minutes)
		float swell = (float) (0.5 + 0.3 * Math.sin(t * 0.031) + 0.2 * Math.sin(t * 0.0137 + 1.7));
		float target = 0.45F + 0.95F * swell * swell + 0.9F * com.svreg.sky.sky.RaymarchClouds.storm();
		target = Math.max(0.35F, Math.min(2.2F, target));
		level += (target - level) * Math.min(1, dt * 0.15F);
		return level;
	}

	/** the wind strength to use right now (menu strength x living wind) */
	public static float strength() {
		return SkySettings.windStrength * factor();
	}

	/** wave speed: gusts also travel a little faster when the wind is up */
	public static float speed() {
		return SkySettings.windSpeed * (0.65F + 0.35F * factor());
	}

	/** how fast the clouds drift (the menu's cloud speed x living wind) */
	public static float cloudSpeed() {
		return SkySettings.cloudSpeed * factor();
	}
}
