package com.svreg.sky.sound;

import com.svreg.sky.SvregSky;
import com.svreg.sky.sky.CirrusSky;
import com.svreg.sky.sky.SkySettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;

import java.util.Random;

/** Ghibli ambience: wind over open land, crickets at night, birdsong by day, a babbling stream near water. */
public final class Ambience {
	private static final SoundEvent WIND = event("ambient.wind_meadow");
	private static final SoundEvent CRICKETS = event("ambient.crickets");
	private static final SoundEvent STREAM = event("ambient.stream");
	private static final SoundEvent BIRD = event("ambient.bird");
	private static final Random RNG = new Random();

	private static Loop wind, crickets, stream;
	private static float waterNear;
	private static int tick, nextBird = 100;
	private static boolean broken;

	private Ambience() {
	}

	private static SoundEvent event(String path) {
		return SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(SvregSky.MOD_ID, path));
	}

	/** a looping, non-positional sound whose volume glides toward a target */
	private static final class Loop extends AbstractTickableSoundInstance {
		float target;

		Loop(SoundEvent e) {
			super(e, SoundSource.AMBIENT, SoundInstance.createUnseededRandom());
			this.looping = true;
			this.delay = 0;
			this.volume = 0.001F;
			this.relative = true;
			this.attenuation = SoundInstance.Attenuation.NONE;
		}

		@Override
		public void tick() {
			this.volume += (target - this.volume) * 0.03F;
			if (target <= 0.001F && this.volume < 0.005F) {
				this.stop();
			}
		}

		boolean done() {
			return this.isStopped();
		}
	}

	private static Loop drive(Loop loop, SoundEvent e, float target) {
		Minecraft mc = Minecraft.getInstance();
		if (target > 0.002F && (loop == null || loop.done())) {
			loop = new Loop(e);
			loop.target = target;
			mc.getSoundManager().play(loop);
		} else if (loop != null) {
			loop.target = target;
		}
		return loop;
	}

	public static void tick(Minecraft mc) {
		if (broken) {
			return;
		}
		try {
			Player player = mc.player;
			if (player == null || mc.level == null || mc.isPaused()) {
				return;
			}
			float vol = SkySettings.ambientSounds ? SkySettings.ambientVolume : 0;
			BlockPos eye = BlockPos.containing(player.getEyePosition());
			boolean outdoors = mc.level.canSeeSky(eye);
			boolean underwater = mc.level.getFluidState(eye).is(FluidTags.WATER);
			float day = CirrusSky.dayLevel(), night = CirrusSky.nightLevel(), rain = CirrusSky.rainLevel();
			float open = outdoors && !underwater ? 1 : 0;

			// water nearby: sample around the player twice a second
			if (++tick % 10 == 0) {
				int found = 0;
				for (int i = 0; i < 24; i++) {
					BlockPos p = eye.offset(RNG.nextInt(17) - 8, RNG.nextInt(7) - 4, RNG.nextInt(17) - 8);
					if (mc.level.getFluidState(p).is(FluidTags.WATER)) {
						found++;
					}
				}
				waterNear += (Math.min(1, found / 6F) - waterNear) * 0.5F;
			}

			wind = drive(wind, WIND, vol * open * (0.12F + 0.22F * Math.min(SkySettings.windStrength, 2)) * (1 + rain * 0.5F));
			crickets = drive(crickets, CRICKETS, vol * open * smooth(0.4F, 0.9F, night) * (1 - rain) * 0.45F);
			stream = drive(stream, STREAM, vol * waterNear * (underwater ? 0 : 0.5F));

			// birdsong: random songs around the player by day, more in the morning
			if (--nextBird <= 0) {
				float morning = CirrusSky.sunDirection().x > 0 ? 1.5F : 1F;
				nextBird = (int) ((120 + RNG.nextInt(260)) / morning);
				if (vol > 0 && open > 0 && day > 0.5F && rain < 0.2F) {
					double a = RNG.nextDouble() * Math.PI * 2, r = 6 + RNG.nextDouble() * 12;
					mc.getSoundManager().play(new SimpleSoundInstance(BIRD, SoundSource.AMBIENT, vol * 0.45F, 0.9F + RNG.nextFloat() * 0.25F,
							SoundInstance.createUnseededRandom(), player.getX() + Math.cos(a) * r, player.getEyeY() + 3 + RNG.nextDouble() * 5, player.getZ() + Math.sin(a) * r));
				}
			}
		} catch (Throwable t) {
			broken = true;
			SvregSky.LOG.error("SvregSky: ambient sounds failed, turning them off", t);
		}
	}

	private static float smooth(float e0, float e1, float x) {
		float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
		return t * t * (3 - 2 * t);
	}
}
