package com.svreg.sky.sky;

/**
 * Pre-baked noise for the "crumble" raymarched clouds, all in one atlas so the shader needs one sampler:
 * rows 0..65: a tiling 3D detail field (128 x 64 x 128 blocks, one texel per 2 blocks): puffs in R and G, wisps in B, as 32 slices
 * of 64 x 64 side by side, each with a one-texel wrap border;
 * rows 66..323: a tiling 2D weather map (256 x 256 texels; the shader spreads it over 1280 blocks) with a one-texel
 * wrap border: R = cloud cover, G = how tall the clouds grow there, B = cloud base offset. Columns 0..257 hold it
 * sharp, columns 258..515 blurred (read far away, where small clouds would be lone blinking pixels).
 */
public final class CrumbleNoise {
	public static final int N = 64, SLICES = 32, PAD = N + 2;
	public static final int WN = 256, WPAD = WN + 2;
	public static final int WIDTH = PAD * SLICES, HEIGHT = PAD + WPAD;

	private static float hash(int x, int y, int z, int s) {
		int h = x * 374761393 + y * 668265263 + z * 1274126177 + s * 1442695041;
		h = (h ^ (h >>> 13)) * 1274126177;
		return ((h ^ (h >>> 16)) & 0xFFFFFF) / (float) 0xFFFFFF;
	}

	/** periodic value noise, coordinates in tile units 0..1, lattice lx x ly x lz */
	private static float vn(float x, float y, float z, int lx, int ly, int lz, int s) {
		x *= lx;
		y *= ly;
		z *= lz;
		int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
		float fx = x - ix, fy = y - iy, fz = z - iz;
		fx = fx * fx * (3 - 2 * fx);
		fy = fy * fy * (3 - 2 * fy);
		fz = fz * fz * (3 - 2 * fz);
		float r = 0;
		for (int c = 0; c < 8; c++) {
			int ax = c & 1, ay = (c >> 1) & 1, az = (c >> 2) & 1;
			float w = (ax == 1 ? fx : 1 - fx) * (ay == 1 ? fy : 1 - fy) * (az == 1 ? fz : 1 - fz);
			r += w * hash(Math.floorMod(ix + ax, lx), Math.floorMod(iy + ay, ly), Math.floorMod(iz + az, lz), s);
		}
		return r;
	}

	/** inverted 3D Worley on a periodic lattice: 1 at a puff centre, falling toward 0 where puffs meet */
	private static float puffs(float x, float y, float z, int lx, int ly, int lz, int s) {
		x *= lx;
		y *= ly;
		z *= lz;
		int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
		float best = 9;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					int cx = ix + dx, cy = iy + dy, cz = iz + dz;
					int hx = Math.floorMod(cx, lx), hy = Math.floorMod(cy, ly), hz = Math.floorMod(cz, lz);
					float px = cx + 0.15F + 0.7F * hash(hx, hy, hz, s), py = cy + 0.15F + 0.7F * hash(hx, hy, hz, s + 7), pz = cz + 0.15F + 0.7F * hash(hx, hy, hz, s + 13);
					float ddx = px - x, ddy = py - y, ddz = pz - z;
					best = Math.min(best, ddx * ddx + ddy * ddy + ddz * ddz);
				}
			}
		}
		return Math.max(0, 1 - (float) Math.sqrt(best));
	}

	/**
	 * detail at tile coords: R = medium puffs (~26 blocks), G = small puffs (~13 blocks), B = billowy wisps (high layer).
	 * Each puff channel is centred so its average is about 0.5: the shader can fade a channel to 0.5 far away and the
	 * cloud keeps its size.
	 */
	public static float[] detail(float u, float v, float w) {
		float wx = (vn(u, v, w, 2, 1, 2, 5) - 0.5F) * 0.10F, wz = (vn(u, v, w, 2, 1, 2, 6) - 0.5F) * 0.10F;
		float qx = u + wx, qz = w + wz;
		float m = puffs(qx, v, qz, 5, 3, 5, 3);
		float sm = puffs(qx, v, qz, 10, 5, 10, 4);
		float bl = 1 - Math.abs(2 * vn(u, v, w, 8, 4, 8, 1) - 1);
		return new float[]{(m - MEAN_M) * 1.3F + 0.5F, (sm - MEAN_S) * 1.3F + 0.5F, bl};
	}

	/** measured averages of the two puff fields (see main) */
	static final float MEAN_M = 0.495F, MEAN_S = 0.495F;

	/** weather map at tile coords 0..1: uneven cover with gaps, clumps and streaks */
	public static float[] weather(float u, float w) {
		float cov = vn(u, 0, w, 6, 1, 6, 11) * 0.32F + vn(u, 0, w, 16, 1, 16, 12) * 0.40F + vn(u, 0, w, 40, 1, 40, 13) * 0.28F;
		float tall = vn(u, 0, w, 10, 1, 10, 21) * 0.7F + vn(u, 0, w, 32, 1, 32, 22) * 0.3F;
		float base = vn(u, 0, w, 5, 1, 5, 31);
		return new float[]{cov, tall, base};
	}

	private static int b(float f) {
		return Math.round(Math.max(0, Math.min(1, f)) * 255);
	}

	/** periodic box blur of radius 3 along x (dx = 1) or z (dz = 1) */
	private static float[] box(float[] a, int dx, int dz) {
		float[] r = new float[a.length];
		for (int z = 0; z < WN; z++) {
			for (int x = 0; x < WN; x++) {
				float sum = 0;
				for (int i = -3; i <= 3; i++) {
					sum += a[Math.floorMod(x + i * dx, WN) + Math.floorMod(z + i * dz, WN) * WN];
				}
				r[x + z * WN] = sum / 7F;
			}
		}
		return r;
	}

	/** writes a weather map (3 channels) with its one-texel wrap border below the detail slices, at column x0 */
	private static void put(int[] out, float[][] ch, int x0) {
		for (int z = -1; z <= WN; z++) {
			for (int x = -1; x <= WN; x++) {
				int i = Math.floorMod(x, WN) + Math.floorMod(z, WN) * WN;
				out[(x0 + x + 1) + (PAD + z + 1) * WIDTH] = 0xFF000000 | b(ch[0][i]) << 16 | b(ch[1][i]) << 8 | b(ch[2][i]);
			}
		}
	}

	/** the atlas as packed ARGB, row-major WIDTH x HEIGHT */
	public static int[] bake() {
		int[] out = new int[WIDTH * HEIGHT];
		int[] slice = new int[N * N];
		for (int s = 0; s < SLICES; s++) {
			float v = (s + 0.5F) / SLICES;
			for (int z = 0; z < N; z++) {
				for (int x = 0; x < N; x++) {
					float[] d = detail((x + 0.5F) / N, v, (z + 0.5F) / N);
					slice[x + z * N] = 0xFF000000 | b(d[0]) << 16 | b(d[1]) << 8 | b(d[2]);
				}
			}
			for (int z = -1; z <= N; z++) {
				for (int x = -1; x <= N; x++) {
					out[(s * PAD + x + 1) + (z + 1) * WIDTH] = slice[Math.floorMod(x, N) + Math.floorMod(z, N) * N];
				}
			}
		}
		float[][] ch = new float[3][WN * WN];
		for (int z = 0; z < WN; z++) {
			for (int x = 0; x < WN; x++) {
				float[] c = weather((x + 0.5F) / WN, (z + 0.5F) / WN);
				for (int k = 0; k < 3; k++) {
					ch[k][x + z * WN] = c[k];
				}
			}
		}
		put(out, ch, 0);
		// the same map blurred (box 7 x 7, twice: about a 3.5 texel gaussian) for far away, beside the sharp one
		for (int k = 0; k < 3; k++) {
			for (int pass = 0; pass < 2; pass++) {
				ch[k] = box(box(ch[k], 1, 0), 0, 1);
			}
		}
		put(out, ch, WPAD);
		return out;
	}

	/** preview helper: java CrumbleNoise out.png */
	public static void main(String[] args) throws Exception {
		int[] a = bake();
		java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(WIDTH, HEIGHT, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		img.setRGB(0, 0, WIDTH, HEIGHT, a, 0, WIDTH);
		javax.imageio.ImageIO.write(img, "png", new java.io.File(args[0]));
	}
}
