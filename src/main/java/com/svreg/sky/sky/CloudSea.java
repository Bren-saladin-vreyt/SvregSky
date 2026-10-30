package com.svreg.sky.sky;

import net.minecraft.client.Minecraft;

/**
 * The cloud sea: when you stand high up (a mountain, a tower), the valleys below fill with a soft sea of cloud,
 * thickest in the morning; some days it stays all day. Drawn by the terrain shader (over the land below it)
 * and by the sky's cloud pass (out to the horizon).
 */
public final class CloudSea {
	/** the cloud sea's top, in world blocks */
	public static final int LEVEL = 100;
	private static float amount;
	private static long lastNanos;
	/** test hook: force the cloud sea on (amount 0..1, negative = normal) */
	public static float force = -1;

	private CloudSea() {
	}

	public static float amount() {
		return amount;
	}

	/** how high the camera is above the cloud sea (0 if below) */
	public static float heightAbove() {
		return (float) Math.max(0, com.svreg.sky.wind.Wind.cameraY() - LEVEL);
	}

	/** level + amount (fraction 0..0.99) for the terrain shader */
	public static float packed() {
		return amount > 0.01F ? LEVEL + Math.min(0.99F, amount) : 0;
	}

	public static void update(Minecraft mc, float day, float gold, float rain, float sunX) {
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0 : (float) Math.min(0.25, (now - lastNanos) / 1e9);
		lastNanos = now;
		float target = 0;
		if (SkySettings.on("cloud_sea") && mc.level != null && mc.level.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
			float h = heightAbove();
			float high = smooth(14, 34, h);
			long dayNo = mc.level.getOverworldClockTime() / 24000L;
			double r = Math.sin(dayNo * 9.137 + 1.7) * 43758.5453;
			r -= Math.floor(r);
			// mornings always (after sunrise, until mid morning); about a third of days keep it all day
			float morning = sunX > 0 ? Math.max(gold, 1 - smooth(0.25F, 0.7F, CirrusSky.sunDirection().y)) : 0;
			float allDay = r < 0.35 ? 0.85F : 0;
			target = high * Math.max(morning, allDay) * Math.max(day, gold * 0.9F) * (1 - rain * 0.7F);
		}
		if (force >= 0) {
			target = force;
		}
		amount += (target - amount) * Math.min(1, dt * 0.4F);
		if (force >= 0) {
			amount = force;
		}
	}

	private static float smooth(float e0, float e1, float x) {
		float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
		return t * t * (3 - 2 * t);
	}
}
