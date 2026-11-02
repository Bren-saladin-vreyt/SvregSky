package com.svreg.sky.sky;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * "Ghibli cumulus" cloud field (pure Java, no game classes, so previews can run it too).
 * Clouds are laid out in blocks, so the same sky can be rasterised at 4-block or 2-block cells:
 * - cumulus humilis / mediocris: medium heaps with a flat base, most of the sky
 * - cumulus congestus: a few tall towers
 * - fractocumulus: small torn bits drifting on their own
 * Each cloud is a soft envelope of domes; layered inverted Worley noise (warped by value noise) eats its surface so the
 * outline becomes a crowd of round bubbles, each with its own light and shade.
 */
public final class CumulusGen {
	/** sky size in blocks (the field tiles) and layer height in blocks */
	public static final float SIZE = 1024F, HEIGHT = 88F;
	public final int nx, ny, nz;
	public final float cell;
	public final byte[] occ;
	public final byte[] nrm;
	/** per-cell brightness 0..255: bubble centres and thin, open parts are lighter (gives every bubble its own shape from any side) */
	public final byte[] light;
	private float[] v;
	private final Random rng;
	private final int seedOff;
	public float countMul = 1.0F, sizeMul = 1.0F;

	/** one dome: ellipsoid centre and radii in blocks, plus the flat base of its cloud */
	private record Dome(float x, float y, float z, float rx, float ry, float rz, float base, float bite) {
	}

	private final List<Dome> domes = new ArrayList<>();

	public CumulusGen(float cell, long seed) {
		this.cell = cell;
		nx = Math.round(SIZE / cell);
		nz = nx;
		ny = Math.round(HEIGHT / cell);
		occ = new byte[nx * ny * nz];
		nrm = new byte[nx * ny * nz * 3];
		light = new byte[nx * ny * nz];
		rng = new Random(seed);
		seedOff = rng.nextInt(1 << 20);
	}

	public int idx(int x, int y, int z) {
		return (Math.floorMod(x, nx) * ny + y) * nz + Math.floorMod(z, nz);
	}

	private float rnd(float a, float b) {
		return a + rng.nextFloat() * (b - a);
	}

	private float gauss(float s) {
		return (float) rng.nextGaussian() * s;
	}

	// ------------------------------------------------------------------ cloud kinds (all sizes in blocks)

	/** humilis / mediocris: a main dome with smaller domes around it, flat base */
	private void mediocris(float cx, float cz, float R, float tall) {
		float base = rnd(4, 12);
		float H = R * (0.9F + 0.5F * tall);
		domes.add(new Dome(cx, base + 3, cz, R, H, R * rnd(0.8F, 1.1F), base, 0.5F));
		int n = 4 + rng.nextInt(6);
		for (int i = 0; i < n; i++) {
			double a = rng.nextDouble() * Math.PI * 2;
			float d = R * rnd(0.5F, 1.45F);
			float r = R * rnd(0.35F, 0.65F);
			domes.add(new Dome(cx + (float) Math.cos(a) * d, base, cz + (float) Math.sin(a) * d, r, H * rnd(0.5F, 0.9F) * r / R * 1.4F, r, base, 0.5F));
		}
		// little torn puffs hanging around the heap
		for (int i = 0; i < 3 + rng.nextInt(6); i++) {
			double a = rng.nextDouble() * Math.PI * 2;
			float d = R * rnd(1.3F, 2.2F);
			float r = Math.max(3, R * rnd(0.12F, 0.24F));
			float py = base + rnd(0, H * 0.6F);
			domes.add(new Dome(cx + (float) Math.cos(a) * d, py, cz + (float) Math.sin(a) * d, r * rnd(1, 1.6F), r * rnd(0.6F, 0.9F), r * rnd(1, 1.4F), py - r, 0.75F));
		}
		// rounded heads on top: the heap bulges upward like a cauliflower
		for (int i = 0; i < 2 + rng.nextInt(3); i++) {
			float r = R * rnd(0.35F, 0.6F);
			domes.add(new Dome(cx + gauss(R * 0.35F), base + H * rnd(0.4F, 0.75F), cz + gauss(R * 0.35F), r, r, r, base, 0.5F));
		}
	}

	/** congestus: stacked domes climbing high, leaning a little */
	private void congestus(float cx, float cz, float R) {
		float base = rnd(3, 8);
		mediocris(cx, cz, R, 0.6F);
		float lx = gauss(0.15F), lz = gauss(0.15F);
		float y = base + R * 0.5F;
		float r = R * 0.62F;
		while (y < HEIGHT - 8 && r > 5) {
			for (int j = 0; j < 1 + rng.nextInt(2); j++) {
				domes.add(new Dome(cx + lx * (y - base) + gauss(r * 0.25F), y, cz + lz * (y - base) + gauss(r * 0.25F), r, r * 0.95F, r, base, 0.5F));
			}
			y += r * rnd(0.6F, 0.85F);
			r *= rnd(0.8F, 0.92F);
		}
	}

	/** fractocumulus: a few small ragged bits, eaten harder */
	private void fracto(float cx, float cz, float R) {
		int n = 2 + rng.nextInt(5);
		float y = rnd(8, 35);
		for (int i = 0; i < n; i++) {
			float r = R * rnd(0.5F, 1.0F);
			float px = cx + gauss(R * 1.6F), pz = cz + gauss(R * 1.6F), py = y + gauss(4);
			domes.add(new Dome(px, py, pz, r * rnd(1.0F, 1.8F), r * rnd(0.5F, 0.8F), r * rnd(1.0F, 1.5F), py - r, 0.75F));
		}
	}

	public CumulusGen generate() {
		int count = Math.max(4, Math.round((115 + rng.nextInt(20)) * countMul));
		for (int c = 0; c < count; c++) {
			float cx = rng.nextFloat() * SIZE, cz = rng.nextFloat() * SIZE;
			float p = rng.nextFloat();
			if (p < 0.5F) {
				mediocris(cx, cz, sz(26, 0.35F), rng.nextFloat());
			} else if (p < 0.58F) {
				congestus(cx, cz, sz(32, 0.2F));
			} else {
				fracto(cx, cz, sz(9, 0.3F));
			}
		}
		rasterise();
		return this;
	}

	private float sz(float mean, float spread) {
		return (float) Math.max(4, Math.min(64, sizeMul * mean * Math.exp(rng.nextGaussian() * spread)));
	}

	// ------------------------------------------------------------------ noise (tiles every SIZE blocks)

	private static float hash(int x, int y, int z, int s) {
		int h = x * 374761393 + y * 668265263 + z * 1274126177 + s * 1442695041;
		h = (h ^ (h >>> 13)) * 1274126177;
		return ((h ^ (h >>> 16)) & 0xFFFFFF) / (float) 0xFFFFFF;
	}

	/** value noise with lattice step `step` blocks */
	private float vnoise(float x, float y, float z, float step, int s) {
		int per = Math.round(SIZE / step);
		x /= step;
		y /= step;
		z /= step;
		int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
		float fx = x - ix, fy = y - iy, fz = z - iz;
		fx = fx * fx * (3 - 2 * fx);
		fy = fy * fy * (3 - 2 * fy);
		fz = fz * fz * (3 - 2 * fz);
		float r = 0;
		for (int c = 0; c < 8; c++) {
			int ax = c & 1, ay = (c >> 1) & 1, az = (c >> 2) & 1;
			float w = (ax == 1 ? fx : 1 - fx) * (ay == 1 ? fy : 1 - fy) * (az == 1 ? fz : 1 - fz);
			r += w * hash(Math.floorMod(ix + ax, per), iy + ay, Math.floorMod(iz + az, per), s + seedOff);
		}
		return r;
	}

	/** inverted Worley: 1 at a bubble centre, falling to ~0 between bubbles (cell size `step` blocks) */
	private float bubbles(float x, float y, float z, float step, int s) {
		int per = Math.round(SIZE / step);
		float gx = x / step, gy = y / step, gz = z / step;
		int ix = (int) Math.floor(gx), iy = (int) Math.floor(gy), iz = (int) Math.floor(gz);
		float best = 9;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					int cx = ix + dx, cy = iy + dy, cz = iz + dz;
					int hx = Math.floorMod(cx, per), hz = Math.floorMod(cz, per);
					float px = cx + hash(hx, cy, hz, s + seedOff), py = cy + hash(hx, cy, hz, s + seedOff + 17), pz = cz + hash(hx, cy, hz, s + seedOff + 31);
					float ddx = px - gx, ddy = py - gy, ddz = pz - gz;
					best = Math.min(best, ddx * ddx + ddy * ddy + ddz * ddz);
				}
			}
		}
		return Math.max(0, 1 - (float) Math.sqrt(best));
	}

	// ------------------------------------------------------------------ rasterising

	private void rasterise() {
		v = new float[nx * ny * nz];
		java.util.Arrays.fill(v, -9);
		// one scratch byte per cell: first the "eaten harder" flag, then the bubble value, then the raw light
		byte[] tmp = new byte[v.length];
		// 1. soft envelope: 1 at a dome's core, 0 on its ellipsoid, a bit beyond (so bubbles can poke out); flat base
		for (Dome d : domes) {
			float k = 1.25F;
			int x0 = (int) Math.floor((d.x - d.rx * k) / cell), x1 = (int) Math.ceil((d.x + d.rx * k) / cell);
			int y0 = Math.max(0, (int) Math.floor(Math.max(d.base - 5, d.y - d.ry * k) / cell)), y1 = Math.min(ny - 1, (int) Math.ceil((d.y + d.ry * k) / cell));
			int z0 = (int) Math.floor((d.z - d.rz * k) / cell), z1 = (int) Math.ceil((d.z + d.rz * k) / cell);
			for (int x = x0; x <= x1; x++) {
				float dx = ((x + 0.5F) * cell - d.x) / d.rx;
				for (int y = y0; y <= y1; y++) {
					float wy = (y + 0.5F) * cell;
					if (wy < d.base - 5) {
						continue;
					}
					// squashed underside: almost flat, but the bubbles can still bulge a few blocks below it
					float dy = wy < d.y ? (wy - d.y) / Math.max(4F, Math.min(d.ry, d.y - d.base + 6F)) : (wy - d.y) / d.ry;
					for (int z = z0; z <= z1; z++) {
						float dz = ((z + 0.5F) * cell - d.z) / d.rz;
						float f = 1 - (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
						if (f <= -0.25F) {
							continue;
						}
						int i = idx(x, y, z);
						if (f > v[i]) {
							v[i] = f;
							tmp[i] = (byte) (d.bite > 0.6F ? 1 : 0);
						}
					}
				}
			}
		}
		// 2. erode with warped, layered bubbles
		float[] out = v; // eroded in place: each cell only reads itself
		for (int x = 0; x < nx; x++) {
			for (int y = 0; y < ny; y++) {
				for (int z = 0; z < nz; z++) {
					int i = idx(x, y, z);
					if (v[i] < -1) {
						out[i] = -1;
						continue;
					}
					float f = v[i];
					float px = (x + 0.5F) * cell, py = (y + 0.5F) * cell, pz = (z + 0.5F) * cell;
					// value-noise warp so the bubble lattice never lines up
					float wx = (vnoise(px, py, pz, 64, 1) - 0.5F) * 30 + (vnoise(px, py, pz, 16, 8) - 0.5F) * 8, wy = (vnoise(px, py, pz, 32, 2) - 0.5F) * 10, wz = (vnoise(px, py, pz, 64, 3) - 0.5F) * 30 + (vnoise(px, py, pz, 16, 9) - 0.5F) * 8;
					float qx = px + wx, qy = py + wy, qz = pz + wz;
					float b = bubbles(qx, qy, qz, 32, 7) * 0.45F + bubbles(qx, qy, qz, 16, 4) * 0.25F + bubbles(qx, qy, qz, 8, 5) * 0.2F + bubbles(qx, qy, qz, 4, 6) * 0.1F;
					float erode = (1 - b) * (tmp[i] == 1 ? 0.85F : 0.68F);
					tmp[i] = (byte) Math.round(Math.max(0, Math.min(1, b)) * 255);
					out[i] = f - erode + 0.36F;
				}
			}
		}
		for (int i = 0; i < v.length; i++) {
			occ[i] = (byte) (out[i] > 0 ? 1 : 0);
		}
		// 3. normals from the field gradient (smoothed over two cells so light bands follow bubbles, not single cells)
		for (int x = 0; x < nx; x++) {
			for (int y = 0; y < ny; y++) {
				for (int z = 0; z < nz; z++) {
					int i = idx(x, y, z);
					if (occ[i] == 0) {
						continue;
					}
					float gx = 0, gy = 0, gz = 0;
					for (int s = 1; s <= 2; s++) {
						float w = s == 1 ? 1 : 0.5F;
						gx += w * (at(out, x + s, y, z) - at(out, x - s, y, z));
						gy += w * (at(out, x, y + s, z) - at(out, x, y - s, z));
						gz += w * (at(out, x, y, z + s) - at(out, x, y, z - s));
					}
					float gl = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);
					if (gl < 1e-5F) {
						gx = 0;
						gy = -1;
						gz = 0;
						gl = 1;
					}
					nrm[i * 3] = (byte) Math.round(-gx / gl * 127);
					nrm[i * 3 + 1] = (byte) Math.round(-gy / gl * 127);
					nrm[i * 3 + 2] = (byte) Math.round(-gz / gl * 127);
					int open = 0;
					for (int s = 1; s <= 2; s++) {
						open += empty(x + s, y, z) + empty(x - s, y, z) + empty(x, y + s, z) + empty(x, y - s, z) + empty(x, y, z + s) + empty(x, y, z - s);
					}
					float bub = (tmp[i] & 255) / 255F;
					float lf = 0.55F * Math.max(0, Math.min(1, (bub - 0.25F) * 2.2F)) + 0.45F * open / 12F;
					tmp[i] = (byte) Math.round(Math.max(0, Math.min(1, lf)) * 255);
				}
			}
		}
		v = null;
		blurLight(tmp);
	}

	/** soften the light map so bands come out as clean blobs, not speckles */
	private void blurLight(byte[] lf) {
		for (int x = 0; x < nx; x++) {
			for (int y = 0; y < ny; y++) {
				for (int z = 0; z < nz; z++) {
					int i = idx(x, y, z);
					if (occ[i] == 0) {
						continue;
					}
					float sum = 0, w = 0;
					for (int dx = -1; dx <= 1; dx++) {
						for (int dy = -1; dy <= 1; dy++) {
							for (int dz = -1; dz <= 1; dz++) {
								int yy = y + dy;
								if (yy < 0 || yy >= ny) {
									continue;
								}
								int j = idx(x + dx, yy, z + dz);
								if (occ[j] != 0) {
									sum += (lf[j] & 255) / 255F;
									w++;
								}
							}
						}
					}
					light[i] = (byte) Math.round(Math.max(0, Math.min(1, sum / w)) * 255);
				}
			}
		}
	}

	private int empty(int x, int y, int z) {
		return y < 0 || y >= ny || occ[idx(x, y, z)] == 0 ? 1 : 0;
	}

	private float at(float[] f, int x, int y, int z) {
		if (y < 0 || y >= ny) {
			return -1;
		}
		return Math.max(-0.3F, f[idx(x, y, z)]);
	}

	/** preview helper: java CumulusGen out.png seed cell -> RGBA atlas, slices tiled (tx per row) */
	public static void main(String[] args) throws Exception {
		CumulusGen g = new CumulusGen(Float.parseFloat(args[2]), Long.parseLong(args[1])).generate();
		int tx = Math.min(g.ny, 8192 / g.nx), rows = (g.ny + tx - 1) / tx;
		java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(g.nx * tx, g.nz * rows, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		long filled = 0;
		for (int y = 0; y < g.ny; y++) {
			int ox = (y % tx) * g.nx, oz = (y / tx) * g.nz;
			for (int x = 0; x < g.nx; x++) {
				for (int z = 0; z < g.nz; z++) {
					int i = g.idx(x, y, z);
					int a = g.occ[i] != 0 ? 128 + (g.light[i] & 255) * 127 / 255 : 0;
					filled += g.occ[i];
					int r = g.nrm[i * 3] + 128, gg = g.nrm[i * 3 + 1] + 128, b = g.nrm[i * 3 + 2] + 128;
					img.setRGB(ox + x, oz + z, a << 24 | (r & 255) << 16 | (gg & 255) << 8 | (b & 255));
				}
			}
		}
		javax.imageio.ImageIO.write(img, "png", new java.io.File(args[0]));
		System.out.println("grid " + g.nx + "x" + g.ny + "x" + g.nz + " tx " + tx + " fill " + filled / (double) g.occ.length);
	}
}
