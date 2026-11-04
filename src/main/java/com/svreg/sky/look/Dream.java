package com.svreg.sky.look;

import com.svreg.sky.SvregSky;
import com.svreg.sky.sky.CirrusSky;
import com.svreg.sky.sky.SkySettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * A dream when sleeping: falling asleep, the view drifts into a pale lavender haze; waking up, it opens from a soft
 * white-gold glow (with a bird singing if it is morning), like a scene change in a Ghibli film.
 */
public final class Dream {
	private static final SoundEvent CHIRP = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(SvregSky.MOD_ID, "ambient.bird"));
	private static float sleep, wake;
	private static boolean wasSleeping;

	private Dream() {
	}

	public static boolean on() {
		return SkySettings.on("dream_sleep");
	}

	/** 0 awake .. 1 deep asleep */
	public static float sleep() {
		return on() ? sleep : 0;
	}

	/** 1 just woke up .. 0 a few seconds later */
	public static float wake() {
		return on() ? wake : 0;
	}

	/** called every client tick */
	public static void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			sleep = wake = 0;
			wasSleeping = false;
			return;
		}
		boolean sleeping = mc.player.isSleeping();
		if (sleeping) {
			sleep = Math.min(1, mc.player.getSleepTimer() / 100F);
		} else {
			sleep = Math.max(0, sleep - 0.1F);
		}
		if (wasSleeping && !sleeping) {
			wake = 1;
			if (on() && SkySettings.ambientSounds && CirrusSky.dayLevel() + CirrusSky.goldLevel() > 0.3F) {
				mc.getSoundManager().play(new SimpleSoundInstance(CHIRP, SoundSource.AMBIENT, 0.5F * Math.max(0.2F, SkySettings.ambientVolume), 1.0F,
						SoundInstance.createUnseededRandom(), mc.player.getX() + 3, mc.player.getEyeY() + 2, mc.player.getZ() + 2));
			}
		}
		wasSleeping = sleeping;
		if (!sleeping) {
			wake = Math.max(0, wake - 1 / 70F); // about 3.5 seconds
		}
	}
}
