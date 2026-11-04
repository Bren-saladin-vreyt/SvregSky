package com.svreg.sky.look;

import com.svreg.sky.sky.SkySettings;

/**
 * Animation "on twos": hand-drawn anime is drawn at about 12 drawings a second, which is a big part of why it
 * looks drawn. With this on, the mod's own animation (grass and leaves in the wind, water, drifting and churning
 * clouds, the painted horizon, particles) moves in 12 steps a second, while the camera and the game still run at
 * full frame rate. All clocks step on the same global boundaries, so everything moves together.
 */
public final class Twos {
	public static final double FPS = 12.0;

	private Twos() {
	}

	public static boolean on() {
		return SkySettings.on("anime_twos");
	}

	/** a time in seconds, held on the current drawing while on twos */
	public static double step(double seconds) {
		return on() ? Math.floor(seconds * FPS) / FPS : seconds;
	}

	/** the number of the current drawing (changes 12 times a second) */
	private static long drawing() {
		return (long) Math.floor(System.nanoTime() / 1e9 * FPS);
	}

	/**
	 * Holds back frame time and lets it out in one piece when the next drawing starts: a simulation stepped with
	 * {@link #tick} stands still between drawings and jumps to where it should be on each new one.
	 */
	public static final class Clock {
		private double acc;
		private long last = Long.MIN_VALUE;

		public double tick(double dt) {
			if (!on()) {
				double r = acc + dt;
				acc = 0;
				return r;
			}
			acc = Math.min(acc + dt, 0.5);
			long d = drawing();
			if (d != last) {
				last = d;
				double r = acc;
				acc = 0;
				return r;
			}
			return 0;
		}
	}
}
