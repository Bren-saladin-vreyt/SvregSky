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
	public static final float SIZE = 1024F, HEIGHT = 144F;
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

	/** world position (blocks, in the tiling field) of cell (0, 0); a window grid covers only part of the sky and does not wrap */
	public final float ox, oz;
	public final boolean window;

	public CumulusGen(float cell, long seed) {
		this(cell, seed, 0, 0, SIZE);
	}

	/** a grid covering only `span` x `span` blocks starting at (ox, oz): the same sky as the full grid, for a fine patch near the camera */
	public CumulusGen(float cell, long seed, float ox, float oz, float span) {
		this.cell = cell;
		this.ox = ox;
		this.oz = oz;
		window = span < SIZE;
		nx = Math.round(span / cell);
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
		float H = R * (0.85F + 0.5F * tall);
		// lopsided and stretched along one direction (like a ship's hull), lobes of very different size and height
		double th = rng.nextDouble() * Math.PI;
		float ax = (float) Math.cos(th), az = (float) Math.sin(th);
		float len = R * rnd(0.7F, 1.35F);
		float lean = rnd(-0.5F, 0.5F);                       // the tall part sits toward one end
		int n = 3 + rng.nextInt(4);
		for (int i = 0; i < n; i++) {
			float t = (n == 1 ? 0 : i / (float) (n - 1)) * 2 - 1;   // -1..1 along the hull
			float along = t * len + gauss(R * 0.12F);
			float side = gauss(R * 0.4F);
			float k = (float) Math.exp(-(t - lean) * (t - lean) * 1.6F); // taller near the lean point
			float r = R * rnd(0.4F, 0.75F) * (0.55F + 0.6F * k);
			float hy = base + r * rnd(0.05F, 0.3F) + H * 0.55F * k * rnd(0.4F, 1.0F);
			domes.add(new Dome(cx + ax * along - az * side, hy, cz + az * along + ax * side, r, r * rnd(0.75F, 0.95F), r, base, 0.5F));
		}
		// a low flat keel along the whole length keeps the bottom straight and long
		domes.add(new Dome(cx, base + 1.5F, cz, Math.abs(ax) * len * 0.75F + R * 0.3F, Math.max(3, H * 0.16F), Math.abs(az) * len * 0.75F + R * 0.3F, base, 0.5F));
		// rounded heads on top around the tall part
		for (int i = 0; i < 2 + rng.nextInt(3); i++) {
			float r = R * rnd(0.3F, 0.5F);
			float along = lean * len + gauss(R * 0.35F);
			domes.add(new Dome(cx + ax * along + gauss(R * 0.2F), base + H * rnd(0.55F, 0.85F), cz + az * along + gauss(R * 0.2F), r, r * 0.9F, r, base, 0.5F));
		}
		// deep notches between lobes (the clover look): carve a few round bites out of the sides
		for (int i = 0; i < 1 + rng.nextInt(3); i++) {
			float t = rnd(-0.8F, 0.8F);
			float sgn = rng.nextBoolean() ? 1 : -1;
			float r = R * rnd(0.25F, 0.4F);
			float off = R * rnd(0.55F, 0.8F) * sgn;
			domes.add(new Dome(cx + ax * t * len - az * off, base + H * rnd(0.3F, 0.7F), cz + az * t * len + ax * off, r, r * 1.3F, r, base, -1F));
		}
		// little torn puffs hanging around the heap
		for (int i = 0; i < 3 + rng.nextInt(6); i++) {
			double a = rng.nextDouble() * Math.PI * 2;
			float d = R * rnd(1.3F, 2.2F);
			float r = Math.max(3, R * rnd(0.12F, 0.24F));
			float py = base + rnd(0, H * 0.6F);
			domes.add(new Dome(cx + (float) Math.cos(a) * d, py, cz + (float) Math.sin(a) * d, r * rnd(1, 1.6F), r * rnd(0.6F, 0.9F), r * rnd(1, 1.4F), py - r, 0.75F));
		}
	}

	/**
	 * A giant billowing cumulus (the CazToon / Ghibli hero cloud): a long lopsided base, a towering cauliflower crown
	 * built from big round heads that shrink as they climb, side lobes at different heights and deep notches.
	 */
	private void giant(float cx, float cz, float R) {
		float base = rnd(3, 7);
		mediocris(cx, cz, R * 0.6F, 1.0F);
		double th = rng.nextDouble() * Math.PI * 2;
		float lx = (float) Math.cos(th) * 0.12F, lz = (float) Math.sin(th) * 0.12F; // the crown leans downwind
		float y = base + R * 0.3F, r = R * 0.48F;
		while (y < HEIGHT - r * 0.5F && r > 8) {
			int m = 3 + rng.nextInt(3);
			for (int j = 0; j < m; j++) {
				double a = rng.nextDouble() * Math.PI * 2;
				float d = r * rnd(0.25F, 0.75F);
				float rr = r * rnd(0.55F, 0.85F);
				domes.add(new Dome(cx + lx * (y - base) + (float) Math.cos(a) * d, y + gauss(r * 0.12F), cz + lz * (y - base) + (float) Math.sin(a) * d,
						rr, rr * rnd(0.8F, 0.95F), rr, base, 0.5F));
			}
			y += r * rnd(0.38F, 0.5F);
			r *= rnd(0.9F, 0.96F);
		}
		// big side lobes bulging out at mid height
		for (int i = 0; i < 2 + rng.nextInt(3); i++) {
			double a = rng.nextDouble() * Math.PI * 2;
			float d = R * rnd(0.35F, 0.6F);
			float rr = R * rnd(0.25F, 0.38F);
			domes.add(new Dome(cx + (float) Math.cos(a) * d, base + R * rnd(0.3F, 0.9F), cz + (float) Math.sin(a) * d, rr, rr * 0.9F, rr, base, 0.5F));
		}
		// deep notches between the heads
		for (int i = 0; i < 2 + rng.nextInt(2); i++) {
			double a = rng.nextDouble() * Math.PI * 2;
			float d = R * rnd(0.4F, 0.6F);
			float rr = R * rnd(0.16F, 0.24F);
			domes.add(new Dome(cx + (float) Math.cos(a) * d, base + R * rnd(0.5F, 1.1F), cz + (float) Math.sin(a) * d, rr, rr * 1.3F, rr, base, -1F));
		}
	}

	/** congestus: stacked domes climbing high, leaning a little */
	private void congestus(float cx, float cz, float R) {
		float base = rnd(3, 8);
		mediocris(cx, cz, R, 0.6F);
		float lx = gauss(0.15F), lz = gauss(0.15F);
		float y = base + R * 0.5F;
		float r = R * 0.6F;
		// a broad tower of round heads: each level is a cluster of 3-4 bubbles, shrinking slowly, ending in a wide round top
		while (y < HEIGHT - r * 0.8F && r > 7) {
			int m = 3 + rng.nextInt(2);
			for (int j = 0; j < m; j++) {
				double a = rng.nextDouble() * Math.PI * 2;
				float d = r * rnd(0.2F, 0.6F);
				domes.add(new Dome(cx + lx * (y - base) + (float) Math.cos(a) * d, y, cz + lz * (y - base) + (float) Math.sin(a) * d, r * rnd(0.6F, 0.8F), r * rnd(0.55F, 0.7F), r * rnd(0.6F, 0.8F), base, 0.5F));
			}
			y += r * rnd(0.45F, 0.6F);
			r *= rnd(0.88F, 0.95F);
		}
	}

	/** stratocumulus terraces: long flat slabs stretched along the wind, stacked in two or three shrinking steps */
	private void terraces(float cx, float cz, float R) {
		float base = rnd(6, 20);
		int layers = 2 + rng.nextInt(2);
		float len = R * rnd(1.8F, 3.0F), wid = R * rnd(0.5F, 0.8F);
		float th = rnd(3.5F, 5.5F);
		for (int k = 0; k < layers; k++) {
			float y = base + k * th * 1.4F;
			float sx = cx + gauss(R * 0.2F) + k * R * rnd(-0.3F, 0.5F), sz = cz + gauss(R * 0.15F);
			int n = 4 + rng.nextInt(4);
			for (int i = 0; i < n; i++) {
				float t = (i / (float) (n - 1) - 0.5F) * len;
				float rx = len / n * rnd(0.9F, 1.4F), rz = wid * rnd(0.6F, 1.0F);
				// the wind runs mostly along +x, so slabs stretch in x
				domes.add(new Dome(sx + t, y + gauss(0.8F), sz + gauss(wid * 0.3F), rx, th * rnd(0.8F, 1.2F), rz, y - th, 0.6F));
			}
			len *= rnd(0.55F, 0.8F);
			wid *= rnd(0.6F, 0.85F);
		}
	}

	/** fractocumulus: a few small ragged bits, eaten harder */
	private void fracto(float cx, float cz, float R) {
		int n = 3 + rng.nextInt(6);
		float y = rnd(8, 40);
		for (int i = 0; i < n; i++) {
			float r = R * rnd(0.5F, 1.0F);
			float px = cx + gauss(R * 1.6F), pz = cz + gauss(R * 1.6F), py = y + gauss(4);
			domes.add(new Dome(px, py, pz, r * rnd(1.0F, 1.8F), r * rnd(0.5F, 0.8F), r * rnd(1.0F, 1.5F), py - r, 0.75F));
		}
	}

	public CumulusGen generate() {
		int count = Math.max(4, Math.round((150 + rng.nextInt(20)) * countMul));
		// Poisson-disk style placement: every big cloud keeps clear sky around it, so clouds stand apart instead of merging
		List<float[]> placed = new ArrayList<>();
		// a few giant billowing cumulus first, spread over the sky so one is usually in view
		int giants = Math.max(1, Math.round((2 + rng.nextInt(3)) * Math.min(1.5F, countMul)));
		for (int g = 0, tries = 0; g < giants && tries < 200; tries++) {
			float cx = rng.nextFloat() * SIZE, cz = rng.nextFloat() * SIZE;
			boolean free = true;
			for (float[] o : placed) {
				float dx = Math.abs(o[0] - cx), dz = Math.abs(o[1] - cz);
				dx = Math.min(dx, SIZE - dx);
				dz = Math.min(dz, SIZE - dz);
				if (dx * dx + dz * dz < 420 * 420) {
					free = false;
					break;
				}
			}
			if (!free) {
				continue;
			}
			placed.add(new float[]{cx, cz, 170, 0});
			giant(cx, cz, Math.min(120, 95 * sizeMul * rnd(0.9F, 1.1F)));
			g++;
		}
		for (int c = 0, tries = 0; c < count && tries < count * 30; tries++) {
			float cx = rng.nextFloat() * SIZE, cz = rng.nextFloat() * SIZE;
			float p = rng.nextFloat();
			boolean small = p >= 0.6F;
			float foot = small ? 10 : (p < 0.45F ? 62 : 64); // rough footprint radius in blocks
			boolean free = true;
			for (float[] o : placed) {
				float dx = Math.abs(o[0] - cx), dz = Math.abs(o[1] - cz);
				dx = Math.min(dx, SIZE - dx);
				dz = Math.min(dz, SIZE - dz);
				float need = small || o[3] > 0 ? (foot + o[2]) * 0.55F : (foot + o[2]) * 1.05F;
				if (dx * dx + dz * dz < need * need) {
					free = false;
					break;
				}
			}
			if (!free) {
				continue;
			}
			placed.add(new float[]{cx, cz, foot, small ? 1 : 0});
			c++;
			if (p < 0.38F) {
				mediocris(cx, cz, sz(36, 0.3F), rng.nextFloat());
			} else if (p < 0.45F) {
				congestus(cx, cz, sz(34, 0.2F));
			} else if (p < 0.62F) {
				terraces(cx, cz, sz(20, 0.3F));
			} else if (p < 0.82F) {
				fracto(cx, cz, sz(6, 0.4F));
			}
		}
		if (noiseLook) {
			rasteriseNoise();
		} else {
			rasterise();
		}
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
		float[] carve = new float[v.length]; // 0 = not carved
		// 1. soft envelope: 1 at a dome's core, 0 on its ellipsoid, a bit beyond (so bubbles can poke out); flat base
		float[] shifts = window ? new float[]{-SIZE, 0, SIZE} : new float[]{0};
		for (Dome d0 : domes) for (float shx : shifts) for (float shz : shifts) {
			Dome d = new Dome(d0.x + shx - ox, d0.y, d0.z + shz - oz, d0.rx, d0.ry, d0.rz, d0.base, d0.bite);
			float k = 1.25F;
			int x0 = (int) Math.floor((d.x - d.rx * k) / cell), x1 = (int) Math.ceil((d.x + d.rx * k) / cell);
			int y0 = Math.max(0, (int) Math.floor(Math.max(d.base - 5, d.y - d.ry * k) / cell)), y1 = Math.min(ny - 1, (int) Math.ceil((d.y + d.ry * k) / cell));
			int z0 = (int) Math.floor((d.z - d.rz * k) / cell), z1 = (int) Math.ceil((d.z + d.rz * k) / cell);
			if (window) {
				if (x1 < 0 || z1 < 0 || x0 >= nx || z0 >= nz) {
					continue;
				}
				x0 = Math.max(0, x0);
				z0 = Math.max(0, z0);
				x1 = Math.min(nx - 1, x1);
				z1 = Math.min(nz - 1, z1);
			}
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
						if (d.bite < 0) {
							carve[i] = Math.max(carve[i], f); // remember the deepest bite, applied after all domes
						} else if (f > v[i]) {
							v[i] = f;
							tmp[i] = (byte) (d.bite > 0.6F ? 1 : 0);
						}
					}
				}
			}
		}
		// carve the notches: a bite pushes the field down by its strength (only into cloud that exists)
		for (int i = 0; i < v.length; i++) {
			if (carve[i] > 0 && v[i] > -1) {
				v[i] -= carve[i] * 1.4F;
			}
		}
		carve = null;
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
					float px = ox + (x + 0.5F) * cell, py = (y + 0.5F) * cell, pz = oz + (z + 0.5F) * cell;
					// value-noise warp so the bubble lattice never lines up
					float wx = (vnoise(px, py, pz, 64, 1) - 0.5F) * 18 + (vnoise(px, py, pz, 16, 8) - 0.5F) * 8, wy = (vnoise(px, py, pz, 32, 2) - 0.5F) * 10, wz = (vnoise(px, py, pz, 64, 3) - 0.5F) * 18 + (vnoise(px, py, pz, 16, 9) - 0.5F) * 8;
					float qx = px + wx, qy = py + wy, qz = pz + wz;
					float b = bubbles(qx, qy, qz, 32, 7) * 0.2F + bubbles(qx, qy, qz, 16, 4) * 0.28F + bubbles(qx, qy, qz, 8, 5) * 0.27F + bubbles(qx, qy, qz, 4, 6) * 0.17F
							+ 0.04F;
					// ragged pixel edge: a little fine noise so outlines step irregularly like a painted pixel cloud
					b += (hash((int) Math.floor(qx / 2), (int) Math.floor(qy / 2), (int) Math.floor(qz / 2), 40) - 0.5F) * 0.1F;
					float erode = (1 - b) * (tmp[i] == 1 ? 0.85F : 0.68F);
					tmp[i] = (byte) Math.round(Math.max(0, Math.min(1, b)) * 255);
					out[i] = f - erode + 0.36F;
				}
			}
		}
		v = null;
		// light: how much cloud lies above each cell (sun mostly from above) -> white tops, blue bellies; thin parts brighter
		for (int x = 0; x < nx; x++) {
			for (int z = 0; z < nz; z++) {
				float acc = 0;
				for (int y = ny - 1; y >= 0; y--) {
					int i = idx(x, y, z);
					float d = out[i];
					if (d > 0) {
						float L = (float) Math.exp(-acc / 40F) * 0.6F + 0.15F * (1 - smooth(0, 0.35F, d)) + 0.25F * side(x, y, z, out);
						tmp[i] = (byte) Math.round(Math.max(0, Math.min(1, L)) * 255);
						acc += Math.min(1, d * 2) * cell;
					} else {
						acc = Math.max(0, acc - cell * 0.5F); // gaps let light back in a little
					}
				}
			}
		}
		lightReady = true;
		finish(out, tmp);
		lightReady = false;
	}

	private boolean lightReady;

	/** how open the cell is sideways within ~10 blocks: outer walls of a heap catch the sun */
	private float side(int x, int y, int z, float[] f) {
		int r = Math.max(2, Math.round(10 / cell)), open = 0;
		for (int s = 1; s <= r; s++) {
			open += (f[idx(x + s, y, z)] <= 0 ? 1 : 0) + (f[idx(x - s, y, z)] <= 0 ? 1 : 0) + (f[idx(x, y, z + s)] <= 0 ? 1 : 0) + (f[idx(x, y, z - s)] <= 0 ? 1 : 0);
		}
		return Math.min(1, open / (4F * r) * 2.5F);
	}

	/** occupancy, normals from the field gradient and the brightness map, from a signed field (cloud where > 0) */
	private void finish(float[] out, byte[] tmp) {
		for (int i = 0; i < out.length; i++) {
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
					// gradient over ~8 blocks either way so the light falls in wide clean areas, not per-cell speckles
					int span = Math.max(2, Math.round(8 / cell));
					for (int s = 1; s <= span; s++) {
						float w = 1F / s;
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
					int reach = Math.max(2, Math.round(6 / cell));
					for (int s = 1; s <= reach; s++) {
						open += empty(x + s, y, z) + empty(x - s, y, z) + empty(x, y + s, z) + empty(x, y - s, z) + empty(x, y, z + s) + empty(x, y, z - s);
					}
					if (!lightReady) {
						float bub = (tmp[i] & 255) / 255F;
						float lf = open / (6F * reach) + 0.15F * bub; // how open the spot is (creases between bubbles are dark), bubble value only a hint
						tmp[i] = (byte) Math.round(Math.max(0, Math.min(1, lf)) * 255);
					}
				}
			}
		}
		blurLight(tmp);
	}

	// ------------------------------------------------------------------ "noise" look: the whole sky from layered noise

	/** set before generate(): build the field purely from noise (coverage map x height profile x Perlin-Worley erosion) */
	public boolean noiseLook;

	private static float remap(float v, float lo, float hi) {
		return (v - lo) / (hi - lo);
	}

	private static float smooth(float e0, float e1, float x) {
		float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
		return t * t * (3 - 2 * t);
	}

	private float fbm(float x, float y, float z, float step, int s, int oct) {
		float sum = 0, amp = 1, tot = 0;
		for (int o = 0; o < oct; o++) {
			sum += vnoise(x, y, z, step, s + o * 13) * amp;
			tot += amp;
			amp *= 0.5F;
			step *= 0.5F;
		}
		return sum / tot;
	}

	/**
	 * Volumetric-style clouds sampled into the voxel grid: a 2D coverage map decides where clouds are and how tall,
	 * a height profile gives flat bases and rounded tops, low-frequency Perlin-Worley gives the big heaps, and
	 * high-frequency Worley eats the surface (wispy and torn at the bottom and edges, billowy higher up).
	 */
	private void rasteriseNoise() {
		int n = nx * ny * nz;
		float[] out = new float[n];
		byte[] tmp = new byte[n];
		float coverage = Math.max(0.15F, Math.min(0.9F, 0.47F * countMul));
		for (int x = 0; x < nx; x++) {
			float px = (x + 0.5F) * cell;
			for (int z = 0; z < nz; z++) {
				float pz = (z + 0.5F) * cell;
				// where the clouds are: blotchy coverage with clear sky between
				float cov = fbm(px, 0, pz, 256, 21, 3) * 0.5F + bubbles(px, 0, pz, 64, 22) * 0.5F;
				cov = smooth(1 - coverage - 0.1F, 1 - coverage + 0.3F, cov);
				float kind = vnoise(px, 0, pz, 256, 24); // tall towers here and there
				float base = 6 + 6 * vnoise(px, 0, pz, 128, 25);
				float top = base + 10 + (30 + 44 * smooth(0.35F, 0.8F, kind)) * cov * sizeMul;
				for (int y = 0; y < ny; y++) {
					int i = idx(x, y, z);
					float h = (y + 0.5F) * cell;
					float hf = (h - base) / Math.max(1, top - base);
					if (hf < -0.05F || hf > 1.05F || cov <= 0.001F) {
						out[i] = -1;
						continue;
					}
					// flat-ish base, round top
					float prof = smooth(0, 0.06F, hf) * (1 - smooth(0.55F, 1.0F, hf));
					float wx = (vnoise(px, h, pz, 32, 1) - 0.5F) * 20 + (vnoise(px, h, pz, 8, 4) - 0.5F) * 5, wy = (vnoise(px, h, pz, 32, 2) - 0.5F) * 8, wz = (vnoise(px, h, pz, 32, 3) - 0.5F) * 20 + (vnoise(px, h, pz, 8, 5) - 0.5F) * 5;
					float qx = px + wx, qy = h + wy, qz = pz + wz;
					// big heaps: Perlin fbm remapped by low-frequency Worley (Perlin-Worley)
					float per = fbm(qx, qy, qz, 64, 30, 3);
					float wor = bubbles(qx, qy, qz, 32, 31) * 0.6F + bubbles(qx, qy, qz, 16, 32) * 0.4F;
					float pw = remap(per, wor - 1, 1);
					float d = remap(pw, 1 - cov, 1) * prof * 1.5F;
					// surface detail: billowy bubbles up high, inverted (wispy, torn) near the base
					float wd = bubbles(qx, qy, qz, 16, 33) * 0.45F + bubbles(qx, qy, qz, 8, 34) * 0.35F + bubbles(qx, qy, qz, 4, 35) * 0.2F;
					float det = wd + (1 - 2 * wd) * (1 - smooth(0.0F, 0.45F, hf)) * 0.6F;
					d = remap(d, det * 0.6F, 1);
					out[i] = d;
					tmp[i] = (byte) Math.round(Math.max(0, Math.min(1, wd)) * 255);
				}
			}
		}
		finish(out, tmp);
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
					int br = Math.max(1, Math.round(4 / cell));
					for (int dx = -br; dx <= br; dx++) {
						for (int dy = -br; dy <= br; dy++) {
							for (int dz = -br; dz <= br; dz++) {
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
		CumulusGen g = new CumulusGen(Float.parseFloat(args[2]), Long.parseLong(args[1]));
		g.noiseLook = args.length > 3 && args[3].equals("noise");
		g.generate();
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
