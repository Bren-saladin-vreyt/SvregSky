package com.svreg.sky.sky;

import net.minecraft.client.Minecraft;

/** Quiet light moments: the blue hour after sunset (and before sunrise), silver night clouds. */
public final class Twilight {
	private Twilight() {
	}

	/** blue hour: the sun just below the horizon (0..1) */
	public static float blueHour() {
		if (!SkySettings.on("blue_hour")) {
			return 0;
		}
		float y = CirrusSky.sunDirection().y;
		return smooth(0.02F, -0.05F, y) * smooth(-0.30F, -0.16F, y) * (1 - CirrusSky.rainLevel());
	}

	/** silver night clouds: early and late in the night on some (mostly summer) nights, low on the sun's side (0..1) */
	public static float silverClouds() {
		Minecraft mc = Minecraft.getInstance();
		if (!SkySettings.on("silver_clouds") || mc.level == null) {
			return 0;
		}
		long dayNo = mc.level.getOverworldClockTime() / 24000L;
		double r = Math.sin(dayNo * 5.871 + 3.3) * 43758.5453;
		r -= Math.floor(r);
		float chance = 0.25F + 0.45F * Seasons.summer();
		if (r > chance && !forceSilver) {
			return 0;
		}
		float y = CirrusSky.sunDirection().y;
		return smooth(-0.08F, -0.16F, y) * smooth(-0.62F, -0.40F, y) * (1 - CirrusSky.rainLevel());
	}

	/** test hook */
	public static boolean forceSilver;

	private static float smooth(float e0, float e1, float x) {
		float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
		return t * t * (3 - 2 * t);
	}
}
