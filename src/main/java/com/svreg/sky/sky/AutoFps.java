package com.svreg.sky.sky;

import net.minecraft.client.Minecraft;

import java.util.Set;

/**
 * Keeps the game smooth on phones: when the frame rate stays under the target, the heaviest effects pause for a while
 * (in steps), and come back once there is headroom again. Nothing is saved; the menu settings stay as they are.
 */
public final class AutoFps {
	public static final int TARGET = 40;
	private static final Set<String> STEP1 = Set.of("cartoon_outline", "bloom", "photo_dof", "light_motes", "shore_foam", "dragonflies", "dandelion_seeds", "waterfall_foam", "underwater_light");
	private static final Set<String> STEP2 = Set.of("butterflies", "water_steam", "smoke_wisps", "fish_jumps", "bird_flocks", "puddles", "sky_reflection", "rain_ripples", "paper_texture", "film_tone", "cloud_sea", "rain_drops");
	private static int level;
	private static int lowTicks, highTicks;

	private AutoFps() {
	}

	/** called each client tick (20/s) */
	public static void tick() {
		if (!SkySettings.on("auto_fps")) {
			level = 0;
			return;
		}
		int fps = Minecraft.getInstance().getFps();
		if (fps > 0 && fps < TARGET) {
			lowTicks++;
			highTicks = 0;
		} else if (fps > TARGET + 15) {
			highTicks++;
			lowTicks = 0;
		} else {
			lowTicks = Math.max(0, lowTicks - 1);
			highTicks = Math.max(0, highTicks - 1);
		}
		if (lowTicks > 100 && level < 3) {          // 5 s too slow: one step lighter
			level++;
			lowTicks = 0;
			tell(level);
			SkySettings.version++;
		} else if (highTicks > 300 && level > 0) {  // 15 s comfortable: one step back up
			level--;
			highTicks = 0;
			tell(level);
			SkySettings.version++;
		}
	}

	/** a short note above the hotbar, so it is clear why some effects went away for a while */
	private static void tell(int lv) {
		try {
			Minecraft mc = Minecraft.getInstance();
			String msg = lv == 0 ? "SvregSky Auto FPS: semua efek nyala lagi"
					: "SvregSky Auto FPS: FPS rendah, " + (lv == 1 ? "beberapa efek kecil" : lv == 2 ? "lebih banyak efek" : "efek berat & awan") + " dijeda sementara (bisa dimatiin di menu)";
			mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.literal(msg), false);
		} catch (Throwable ignored) {
		}
	}

	public static int level() {
		return level;
	}

	/** true when Auto FPS currently pauses this effect */
	static boolean paused(String key) {
		return level >= 1 && STEP1.contains(key) || level >= 2 && STEP2.contains(key);
	}

	/** step 3 also lightens the clouds and wind lines */
	public static boolean heavyPaused() {
		return level >= 3;
	}
}
