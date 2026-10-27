package com.svreg.sky.clouds;

import net.minecraft.client.renderer.CloudRenderer;

import java.util.Arrays;

/**
 * Builds tileable cloud maps in the same cell format vanilla's CloudRenderer reads from clouds.png.
 * One cell = 12x12 blocks. A set cell is a cloud, an empty (0) cell is sky.
 */
public final class CloudMapGen {
	public static final int SIZE = 256;
	private static final int CLOUD_COLOR = 0xFFFFFFFF;

	private CloudMapGen() {
	}

	// ------------------------------------------------------------------ tileable value noise
	private static double hash(int ix, int iy, int seed) {
		double v = Math.sin(ix * 127.1 + iy * 311.7 + seed * 74.7) * 43758.5453;
		return v - Math.floor(v);
	}

	private static double fade(double t) {
		return t * t * t * (t * (t * 6 - 15) + 10);
	}

	private static double noise(double x, double y, int period, int seed) {
		int ix = (int) Math.floor(x);
		int iy = (int) Math.floor(y);
		double fx = fade(x - ix);
		double fy = fade(y - iy);
		int i0 = Math.floorMod(ix, period), i1 = Math.floorMod(ix + 1, period);
		int j0 = Math.floorMod(iy, period), j1 = Math.floorMod(iy + 1, period);
		double a = hash(i0, j0, seed), b = hash(i1, j0, seed), c = hash(i0, j1, seed), d = hash(i1, j1, seed);
		return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy;
	}

	private static double fbm(double u, double v, int base, int octaves, int seed) {
		double sum = 0, amp = 0.55, total = 0;
		int p = base;
		for (int o = 0; o < octaves; o++) {
			sum += amp * noise(u * p, v * p, p, seed + o * 13);
			total += amp;
			amp *= 0.5;
			p *= 2;
		}
		return sum / total;
	}

	/**
	 * Density field for big cumulus masses with lobed edges.
	 * clusterFreq: how many big masses fit across the map (higher = smaller clouds).
	 */
	public static double[] density(int seed, int clusterFreq, int lobeFreq) {
		double[] d = new double[SIZE * SIZE];
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				double u = (x + 0.5) / SIZE, v = (y + 0.5) / SIZE;
				double clus = fbm(u, v, clusterFreq, 3, seed);
				double lob = Math.abs(2 * noise(u * lobeFreq, v * lobeFreq, lobeFreq, seed + 50) - 1);
				double lob2 = Math.abs(2 * noise(u * lobeFreq * 2, v * lobeFreq * 2, lobeFreq * 2, seed + 60) - 1);
				d[x + y * SIZE] = 0.74 * clus + 0.17 * lob + 0.09 * lob2;
			}
		}
		return d;
	}

	/** small scattered puffs that sit around the big masses */
	public static boolean[] puffs(int seed, double coverage, double[] near, double nearMin) {
		double[] p = new double[SIZE * SIZE];
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				double u = (x + 0.5) / SIZE, v = (y + 0.5) / SIZE;
				p[x + y * SIZE] = fbm(u, v, 20, 2, seed + 90);
			}
		}
		double thr = quantile(p, 1 - coverage);
		boolean[] m = new boolean[SIZE * SIZE];
		for (int i = 0; i < m.length; i++) {
			m[i] = p[i] >= thr && (near == null || near[i] >= nearMin);
		}
		return m;
	}

	public static double quantile(double[] values, double q) {
		double[] s = values.clone();
		Arrays.sort(s);
		int i = (int) Math.max(0, Math.min(s.length - 1, Math.round(q * (s.length - 1))));
		return s[i];
	}

	public static boolean[] threshold(double[] d, double coverage) {
		double thr = quantile(d, 1 - coverage);
		boolean[] m = new boolean[d.length];
		for (int i = 0; i < d.length; i++) {
			m[i] = d[i] >= thr;
		}
		return m;
	}

	public static boolean[] or(boolean[] a, boolean[] b) {
		boolean[] m = new boolean[a.length];
		for (int i = 0; i < a.length; i++) {
			m[i] = a[i] || b[i];
		}
		return m;
	}

	/** removes single-cell specks and fills single-cell holes so the 3D shapes stay tidy */
	public static boolean[] tidy(boolean[] m) {
		return tidy(m, SIZE, SIZE);
	}

	public static boolean[] tidy(boolean[] m, int w, int h) {
		boolean[] out = m.clone();
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int n = 0;
				for (int dy = -1; dy <= 1; dy++) {
					for (int dx = -1; dx <= 1; dx++) {
						if ((dx != 0 || dy != 0) && m[Math.floorMod(x + dx, w) + Math.floorMod(y + dy, h) * w]) {
							n++;
						}
					}
				}
				int i = x + y * w;
				if (m[i] && n <= 1) {
					out[i] = false;
				} else if (!m[i] && n >= 7) {
					out[i] = true;
				}
			}
		}
		return out;
	}

	public static CloudRenderer.TextureData toTextureData(boolean[] m) {
		return toTextureData(m, SIZE, SIZE);
	}

	/** packs a mask exactly like CloudRenderer#prepare does for clouds.png */
	public static CloudRenderer.TextureData toTextureData(boolean[] m, int w, int h) {
		long[] cells = new long[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int i = x + y * w;
				if (!m[i]) {
					continue;
				}
				boolean north = !m[x + Math.floorMod(y - 1, h) * w];
				boolean east = !m[Math.floorMod(x + 1, w) + y * w];
				boolean south = !m[x + Math.floorMod(y + 1, h) * w];
				boolean west = !m[Math.floorMod(x - 1, w) + y * w];
				cells[i] = (long) CLOUD_COLOR << 4 | (north ? 1 : 0) << 3 | (east ? 1 : 0) << 2 | (south ? 1 : 0) << 1 | (west ? 1 : 0);
			}
		}
		return new CloudRenderer.TextureData(cells, w, h);
	}
}
