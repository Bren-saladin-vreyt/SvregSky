package com.svreg.sky.sky;

import com.svreg.sky.SvregSky;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.WeatherData;

import java.util.Random;

/**
 * A storm seen coming from far away: a dark cumulonimbus wall rises on one side of the horizon in the minutes before
 * the rain, with lightning flickering inside and far thunder; after the rain it drifts off to the other side.
 * In single player the real weather timer says when the rain will come; on servers only the leaving storm shows.
 */
public final class DistantStorm {
	/** four minutes of warning before the rain */
	private static final int WINDOW = 4800;
	private static final Random RNG = new Random();
	private static final SoundEvent THUNDER = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(SvregSky.MOD_ID, "ambient.thunder"));
	private static float amount, approach, leave, flash, angle = 2.4F;
	private static boolean bolt, wasRaining;
	private static int boltTicks, flashIn = 120, thunderIn = -1;
	private static float thunderVol;
	private static long lastNanos;
	/** test hook: storm amount to show (negative = off) */
	public static float force = -1;

	private DistantStorm() {
	}

	public static float amount() {
		return amount;
	}

	public static float flash() {
		return flash;
	}

	public static boolean bolt() {
		return bolt;
	}

	/** angle index of the storm direction in 5 degree steps (0..71) */
	public static int angleIndex() {
		double deg = Math.toDegrees(angle) % 360.0;
		if (deg < 0) deg += 360.0;
		return (int) Math.round(deg / 5.0) % 72;
	}

	/** storm direction (x, z) on the ground plane */
	public static float dirX() {
		return (float) Math.cos(Math.toRadians(angleIndex() * 5.0));
	}

	public static float dirZ() {
		return (float) Math.sin(Math.toRadians(angleIndex() * 5.0));
	}

	/** amount, direction and flash packed for the shaders: direction * 100 + amount * 40 + flash (0..0.99) */
	public static float packed() {
		return angleIndex() * 100.0F + Math.round(amount * 40.0F) + Math.min(0.99F, flash) * (amount > 0.01F ? 1 : 0);
	}

	/** called every frame from the sky update */
	public static void update(Minecraft mc, float rain, float day) {
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0 : (float) Math.min(0.25, (now - lastNanos) / 1e9);
		lastNanos = now;
		if (!SkySettings.on("distant_storm") || mc.level == null) {
			amount = Math.max(0, amount - dt * 0.2F);
			flash = 0;
			bolt = false;
			return;
		}
		// coming: the single-player weather timer says how long until the rain
		float target = 0;
		try {
			if (mc.getSingleplayerServer() != null) {
				ServerLevel ow = mc.getSingleplayerServer().overworld();
				WeatherData w = ow != null ? ow.getWeatherData() : null;
				if (w != null && !w.isRaining() && w.getClearWeatherTime() <= 0 && w.getRainTime() > 0 && w.getRainTime() < WINDOW) {
					float t = 1 - w.getRainTime() / (float) WINDOW;
					target = t * t * (3 - 2 * t);
					if (approach <= 0.01F && leave <= 0.01F) {
						// a new storm: pick where it comes from
						long dayNo = mc.level.getOverworldClockTime() / 24000L;
						angle = (float) (((Math.sin(dayNo * 4.123 + w.getRainTime() * 0.0007) * 43758.5453) % 1.0 + 1.0) % 1.0 * Math.PI * 2);
					}
				}
			}
		} catch (Throwable ignored) {
			// weather data not reachable (server thread busy etc.): just no warning this time
		}
		approach += (target - approach) * Math.min(1, dt * 0.5F);
		// leaving: after the rain the storm drifts away to the other side
		boolean raining = rain > 0.5F;
		if (wasRaining && rain < 0.15F) {
			wasRaining = false;
			leave = 0.75F;
			angle += (float) Math.PI;
		}
		if (raining) {
			wasRaining = true;
			leave = 0;
		}
		leave = Math.max(0, leave - dt / 200.0F);
		float want = Math.max(approach, leave) * (1 - smooth(0.1F, 0.6F, rain));
		if (force >= 0) {
			want = force;
		}
		amount += (want - amount) * Math.min(1, dt * 0.8F);
		if (force >= 0) {
			amount = force;
		}

		// lightning inside the far storm, sometimes a bolt to the ground, then the thunder a few seconds later
		flash *= (float) Math.pow(0.02, dt);
		if (boltTicks > 0 && --boltTicks == 0) {
			bolt = false;
		}
		if (amount > 0.4F && SkySettings.on("lightning_glow")) {
			if (--flashIn <= 0) {
				flashIn = (int) ((140 + RNG.nextInt(380)) / (0.5F + amount));
				flash = 0.6F + RNG.nextFloat() * 0.4F;
				if (RNG.nextFloat() < 0.35F + 0.3F * amount) {
					bolt = true;
					boltTicks = 6 + RNG.nextInt(5);
				}
				thunderIn = 60 + RNG.nextInt(150);
				thunderVol = 0.12F + 0.3F * amount;
			}
		}
		if (thunderIn > 0 && --thunderIn == 0) {
			Player p = mc.player;
			if (p != null && SkySettings.on("weather_sounds")) {
				double a = angle, r = 60;
				mc.getSoundManager().play(new SimpleSoundInstance(THUNDER, SoundSource.WEATHER, thunderVol, 0.55F + RNG.nextFloat() * 0.15F,
						SoundInstance.createUnseededRandom(), p.getX() + Math.cos(a) * r, p.getEyeY() + 10, p.getZ() + Math.sin(a) * r));
			}
		}
	}

	private static float smooth(float e0, float e1, float x) {
		float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
		return t * t * (3 - 2 * t);
	}
}
