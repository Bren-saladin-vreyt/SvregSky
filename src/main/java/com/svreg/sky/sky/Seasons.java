package com.svreg.sky.sky;

import net.minecraft.client.Minecraft;

/**
 * Client-side seasons that turn with the in-game days: spring (blossoms, fresh greens), summer (towering clouds),
 * autumn (orange-red leaves) and winter (frosty colours, snowflakes). Each season lasts {@link #DAYS} days and blends into the next.
 */
public final class Seasons {
	public static final int DAYS = 8;
	private static float spring, summer = 1, autumn, winter;

	private Seasons() {
	}

	public static void update() {
		Minecraft mc = Minecraft.getInstance();
		if (!SkySettings.on("seasons") || mc.level == null) {
			spring = autumn = winter = 0;
			summer = 1;
			return;
		}
		double days = mc.level.getOverworldClockTime() / 24000.0 + DAYS * 0.5; // a new world starts in mid-spring
		double pos = (days / DAYS) % 4.0;                              // 0 spring, 1 summer, 2 autumn, 3 winter
		float[] w = new float[4];
		int i = (int) Math.floor(pos);
		float f = (float) (pos - i);
		// hold each season, blend over the last fifth into the next
		float blend = Math.max(0, (f - 0.8F) / 0.2F);
		blend = blend * blend * (3 - 2 * blend);
		w[i % 4] += 1 - blend;
		w[(i + 1) % 4] += blend;
		spring = w[0];
		summer = w[1];
		autumn = w[2];
		winter = w[3];
	}

	public static float spring() {
		return spring;
	}

	public static float summer() {
		return summer;
	}

	public static float autumn() {
		return autumn;
	}

	public static float winter() {
		return winter;
	}

	/** 0 spring, 1 summer, 2 autumn, 3 winter: the dominant one */
	public static int current() {
		float m = Math.max(Math.max(spring, summer), Math.max(autumn, winter));
		return m == spring ? 0 : m == summer ? 1 : m == autumn ? 2 : 3;
	}
}
