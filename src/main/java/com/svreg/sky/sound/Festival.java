package com.svreg.sky.sound;

import com.svreg.sky.sky.CirrusSky;
import com.svreg.sky.sky.Events;
import com.svreg.sky.sky.SkySettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import java.util.Random;

/**
 * Festival night sounds: far-off taiko drums in a slow rhythm and the soft pops of distant fireworks, carried on the
 * wind from the direction of the painted village.
 */
public final class Festival {
	private static final Random RNG = new Random();
	private static final SoundEvent DRUM = SoundEvent.createVariableRangeEvent(Identifier.withDefaultNamespace("block.note_block.basedrum"));
	private static final SoundEvent BLAST = SoundEvent.createVariableRangeEvent(Identifier.withDefaultNamespace("entity.firework_rocket.blast_far"));
	private static final SoundEvent TWINKLE = SoundEvent.createVariableRangeEvent(Identifier.withDefaultNamespace("entity.firework_rocket.twinkle_far"));
	// a taiko pattern in ticks: don . don . don-don . . (rest)
	private static final int[] PATTERN = {0, 10, 20, 25, 40, 50, 60, 65};
	private static int tick, beat, blastIn = 40;

	private Festival() {
	}

	public static void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null || mc.isPaused() || !SkySettings.ambientSounds) {
			return;
		}
		float night = CirrusSky.nightLevel();
		if (!Events.festivalTonight() || night < 0.4F || CirrusSky.rainLevel() > 0.3F || !SkySettings.horizonMountains) {
			return;
		}
		float vol = SkySettings.ambientVolume * Math.min(1, (night - 0.4F) * 3);
		// the village sits toward azimuth 3.4 rad on the painted horizon: sounds come from there, far away
		double ax = Math.cos(3.4) * 40, az = Math.sin(3.4) * 40;
		double x = mc.player.getX() + ax, y = mc.player.getEyeY() + 4, z = mc.player.getZ() + az;
		tick++;
		int t = tick % 140;
		for (int p : PATTERN) {
			if (t == p) {
				mc.getSoundManager().play(new SimpleSoundInstance(DRUM, SoundSource.AMBIENT, 0.30F * vol, p % 20 == 5 ? 0.62F : 0.55F,
						SoundInstance.createUnseededRandom(), x, y, z));
			}
		}
		if (--blastIn <= 0) {
			blastIn = 50 + RNG.nextInt(90);
			SoundEvent e = RNG.nextFloat() < 0.6F ? BLAST : TWINKLE;
			mc.getSoundManager().play(new SimpleSoundInstance(e, SoundSource.AMBIENT, 0.35F * vol, 0.8F + RNG.nextFloat() * 0.3F,
					SoundInstance.createUnseededRandom(), x, y + 10, z));
		}
	}
}
