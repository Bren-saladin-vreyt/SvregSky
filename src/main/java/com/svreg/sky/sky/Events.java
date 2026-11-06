package com.svreg.sky.sky;

import net.minecraft.client.Minecraft;

/**
 * The calendar of rare moments: which nights the sea glows, which days get a fox rain (rain while the sun shines),
 * which nights are festival nights. Everything is worked out from the world's day number, so it is the same for
 * everyone and does not change when the game restarts. Test hooks force them on.
 */
public final class Events {
	public static boolean forceGlowSea, forceFoxRain, forceFestival;

	private Events() {
	}

	private static long dayHash(long day, long salt) {
		long h = day * 0x9E3779B97F4A7C15L + salt;
		h ^= h >>> 31;
		h *= 0xBF58476D1CE4E5B9L;
		h ^= h >>> 29;
		return h;
	}

	/** the day number; a night belongs to the day it started on */
	private static long day(boolean night) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return 0;
		}
		long t = mc.level.getOverworldClockTime();
		return Math.floorDiv(night ? t - 12000L : t, 24000L);
	}

	/** tonight the sea glows (about one night in three) */
	public static boolean glowSeaTonight() {
		return forceGlowSea || (SkySettings.on("glow_sea") && Math.floorMod(dayHash(day(true), 0x51EAL), 3L) == 0);
	}

	/** how much the sea glows right now: night, a glowing night, no heavy rain */
	public static float glowSea() {
		if (!glowSeaTonight()) {
			return 0;
		}
		return Math.max(0, CirrusSky.nightLevel() - 0.2F) / 0.8F * (1 - 0.6F * CirrusSky.rainLevel());
	}

	/** today has a fox rain: a short sunny shower late in the afternoon (about one day in five) */
	public static boolean foxRainToday() {
		return forceFoxRain || (SkySettings.on("fox_rain") && Math.floorMod(dayHash(day(false), 0xF0C5L), 5L) == 0);
	}

	/** how hard the fox rain falls right now (0..1): early afternoon on a fox-rain day, not during real rain */
	public static float foxRain() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || !foxRainToday() || CirrusSky.rainLevel() > 0.2F) {
			return 0;
		}
		long tod = Math.floorMod(mc.level.getOverworldClockTime(), 24000L);
		if (forceFoxRain) {
			return 1;
		}
		// late afternoon, 10300..11900 ticks (about 1.3 minutes), easing in and out: with the sun this low the rainbow
		// stands high enough to clear the cloud bank on the horizon
		float a = (tod - 10300) / 300F, b = (11900 - tod) / 300F;
		return Math.max(0, Math.min(1, Math.min(a, b)));
	}

	/** tonight is a festival night: lanterns in the far villages, fireworks, drums (about one night in six) */
	public static boolean festivalTonight() {
		return forceFestival || (SkySettings.on("festival_night") && Math.floorMod(dayHash(day(true), 0xFE57L), 6L) == 0);
	}
}
