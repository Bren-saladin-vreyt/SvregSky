package com.svreg.sky.sky;

/**
 * Pre-baked erosion noise for the raymarched clouds (pure Java, so previews can run it too).
 * A tiling 3D field (128 x 64 x 128 blocks, one texel per 2 blocks) of warped billowy noise is stored as 32 horizontal
 * slices side by side, each 64 x 64 with a one-texel wrap-around border (66 x 66), so the GPU can read it with plain
 * bilinear filtering and no seams. Reading two slices replaces dozens of hash computations per raymarch step.
 */
public final class RaymarchNoise {
	public static final int N = 64, SLICES = 32, PAD = N + 2;
	public static final int WIDTH = PAD * SLICES, HEIGHT = PAD;

	private RaymarchNoise() {
	}

	private static float hash(int x, int y, int z, int s) {
		int h = x * 374761393 + y * 668265263 + z * 1274126177 + s * 1442695041;
		h = (h ^ (h >>> 13)) * 1274126177;
		return ((h ^ (h >>> 16)) & 0xFFFFFF) / (float) 0xFFFFFF;
	}

	/** periodic value noise: lattice of lx x ly x lz cells over the tile, coordinates in tile units 0..1 */
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

	private static float billow(float x, float y, float z, int lx, int ly, int lz, int s) {
		return 1 - Math.abs(2 * vn(x, y, z, lx, ly, lz, s) - 1);
	}

	/** inverted 3D Worley on a periodic lattice: 1 at a bubble centre, falling to ~0 where bubbles meet */
	private static float bubbles(float x, float y, float z, int lx, int ly, int lz, int s) {
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
					float ddx = px - x, ddy = (py - y) * 1.15F, ddz = pz - z;
					best = Math.min(best, ddx * ddx + ddy * ddy + ddz * ddz);
				}
			}
		}
		return Math.max(0, 1 - (float) Math.sqrt(best) * 0.95F);
	}

	/** erosion value 0..1 at tile coordinates (0..1 each axis): big round bubbles, smaller bubbles on them, a little warp */
	public static float erosion(float u, float v, float w) {
		float wx = (vn(u, v, w, 2, 1, 2, 1) - 0.5F) * 0.12F, wz = (vn(u, v, w, 2, 1, 2, 2) - 0.5F) * 0.12F;
		float qx = u + wx, qz = w + wz;
		return bubbles(qx, v, qz, 5, 3, 5, 3) * 0.75F + bubbles(qx, v, qz, 10, 5, 10, 4) * 0.25F;
	}

	/** the atlas as grey values 0..255, row-major WIDTH x HEIGHT */
	public static byte[] bake() {
		float[] slice = new float[N * N];
		byte[] out = new byte[WIDTH * HEIGHT];
		for (int s = 0; s < SLICES; s++) {
			float v = (s + 0.5F) / SLICES;
			for (int z = 0; z < N; z++) {
				for (int x = 0; x < N; x++) {
					slice[x + z * N] = erosion((x + 0.5F) / N, v, (z + 0.5F) / N);
				}
			}
			for (int z = -1; z <= N; z++) {
				for (int x = -1; x <= N; x++) {
					float e = slice[Math.floorMod(x, N) + Math.floorMod(z, N) * N];
					out[(s * PAD + x + 1) + (z + 1) * WIDTH] = (byte) Math.round(Math.max(0, Math.min(1, e)) * 255);
				}
			}
		}
		return out;
	}

	/** preview helper: java RaymarchNoise out.png */
	public static void main(String[] args) throws Exception {
		byte[] a = bake();
		java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(WIDTH, HEIGHT, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < HEIGHT; y++) {
			for (int x = 0; x < WIDTH; x++) {
				int g = a[x + y * WIDTH] & 255;
				img.setRGB(x, y, 0xFF000000 | g << 16 | g << 8 | g);
			}
		}
		javax.imageio.ImageIO.write(img, "png", new java.io.File(args[0]));
	}
}
