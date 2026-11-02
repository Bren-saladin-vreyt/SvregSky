package com.svreg.sky.sky;

import java.util.Arrays;
import java.util.Random;

/**
 * Builds the voxel grid of the 3D Ghibli clouds (pure Java, no game classes, so it can also run in previews).
 * Each day picks a "weather" that mixes several cloud kinds: fair-weather puffs, big cumulus, towering cauliflowers,
 * flat pancakes, cloud streets lined up with the wind, merged twin towers, anvils with a spreading top, and torn fragments.
 * Every cloud is a union of ellipsoid puffs; each cell keeps the normal of the frontmost puff, so the toon light
 * bands follow every bump.
 */
public final class GhibliGen {
	public final int nx, ny, nz;
	public final byte[] occ;
	public final byte[] nrm;
	private final float[] best;
	private final Random rng;
	/** "auto" (each day picks a weather) or a fixed look: fair, cumulus, towers, streets, storm, castle, sheep, mixed */
	public String style = "auto";
	public float countMul = 1.0F, sizeMul = 1.0F;
	/** cotton mode: puffs only give a soft density, then layered warped noise tears it into irregular cotton-wool shapes */
	public boolean cotton;
	/** cotton density per cell (0..1, cloud above 0.5), kept for previews and finer meshes */
	public float[] dens;

	public GhibliGen(int nx, int ny, int nz, long seed) {
		this.nx = nx;
		this.ny = ny;
		this.nz = nz;
		int n = nx * ny * nz;
		occ = new byte[n];
		nrm = new byte[n * 3];
		best = new float[n];
		Arrays.fill(best, -1e9F);
		rng = new Random(seed);
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

	/** an ellipsoid puff; prio lets small bumps win over the body they sit on */
	private void puff(float cx, float cy, float cz, float rx, float ry, float rz, float prio) {
		if (dens != null) {
			softPuff(cx, cy, cz, rx, ry, rz);
		}
		int x0 = (int) Math.floor(cx - rx - 1), x1 = (int) Math.ceil(cx + rx + 1);
		int y0 = Math.max(0, (int) Math.floor(cy - ry - 1)), y1 = Math.min(ny - 1, (int) Math.ceil(cy + ry + 1));
		int z0 = (int) Math.floor(cz - rz - 1), z1 = (int) Math.ceil(cz + rz + 1);
		float r = Math.min(rx, Math.min(ry, rz));
		for (int x = x0; x <= x1; x++) {
			float dx = (x + 0.5F - cx) / rx;
			for (int y = y0; y <= y1; y++) {
				float dy = (y + 0.5F - cy) / ry;
				for (int z = z0; z <= z1; z++) {
					float dz = (z + 0.5F - cz) / rz;
					float d2 = dx * dx + dy * dy + dz * dz;
					if (d2 >= 1) {
						continue;
					}
					float score = (1 - d2) * r + prio;
					int i = idx(x, y, z);
					if (score > best[i]) {
						best[i] = score;
						// normal of an ellipsoid: gradient (dx/rx, dy/ry, dz/rz)
						float gx = dx / rx, gy = dy / ry, gz = dz / rz;
						float gl = (float) Math.sqrt(gx * gx + gy * gy + gz * gz) + 1e-6F;
						nrm[i * 3] = (byte) Math.round(gx / gl * 127);
						nrm[i * 3 + 1] = (byte) Math.round(gy / gl * 127);
						nrm[i * 3 + 2] = (byte) Math.round(gz / gl * 127);
					}
				}
			}
		}
	}

	/** soft density: 0.5 on the ellipsoid surface, rising to 1 in the middle, fading to 0 at twice the radius */
	private void softPuff(float cx, float cy, float cz, float rx, float ry, float rz) {
		float k = 1.9F;
		int x0 = (int) Math.floor(cx - rx * k), x1 = (int) Math.ceil(cx + rx * k);
		int y0 = Math.max(0, (int) Math.floor(cy - ry * k)), y1 = Math.min(ny - 1, (int) Math.ceil(cy + ry * k));
		int z0 = (int) Math.floor(cz - rz * k), z1 = (int) Math.ceil(cz + rz * k);
		for (int x = x0; x <= x1; x++) {
			float dx = (x + 0.5F - cx) / rx;
			for (int y = y0; y <= y1; y++) {
				float dy = (y + 0.5F - cy) / ry;
				for (int z = z0; z <= z1; z++) {
					float dz = (z + 0.5F - cz) / rz;
					float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
					float f = d < 1 ? 1 - 0.5F * d : Math.max(0, 0.5F - 0.5F * (d - 1) / (k - 1));
					int i = idx(x, y, z);
					// soft union: overlapping puffs thicken a bit instead of just taking the max
					dens[i] = Math.max(dens[i], f) + Math.min(dens[i], f) * 0.12F;
				}
			}
		}
	}

	private static float hash3(int x, int y, int z) {
		int h = x * 374761393 + y * 668265263 + z * 1274126177;
		h = (h ^ (h >>> 13)) * 1274126177;
		return ((h ^ (h >>> 16)) & 0xFFFFFF) / (float) 0xFFFFFF;
	}

	/** smooth value noise 0..1 (tiles every `per` cells in x and z so the sky wraps without seams) */
	private float vnoise(float x, float y, float z, int per) {
		int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
		float fx = x - ix, fy = y - iy, fz = z - iz;
		fx = fx * fx * (3 - 2 * fx);
		fy = fy * fy * (3 - 2 * fy);
		fz = fz * fz * (3 - 2 * fz);
		float r = 0;
		for (int c = 0; c < 8; c++) {
			int ax = (c & 1), ay = (c >> 1) & 1, az = (c >> 2) & 1;
			float w = (ax == 1 ? fx : 1 - fx) * (ay == 1 ? fy : 1 - fy) * (az == 1 ? fz : 1 - fz);
			r += w * hash3(Math.floorMod(ix + ax, per) + seedOff, iy + ay, Math.floorMod(iz + az, per));
		}
		return r;
	}

	private int seedOff;

	/** tears the soft density into cotton: warped, wind-stretched, layered noise eats the edges and leaves tufts and holes */
	private void cottonize() {
		seedOff = rng.nextInt(100000) * 7;
		float[] v = new float[dens.length];
		// noise lattice periods must divide the grid so the sky tiles: base frequency 1/16 cell
		for (int x = 0; x < nx; x++) {
			for (int y = 0; y < ny; y++) {
				for (int z = 0; z < nz; z++) {
					int i = idx(x, y, z);
					float d = dens[i];
					if (d < 0.08F) {
						v[i] = d;
						continue;
					}
					float px = x + 0.5F, py = y + 0.5F, pz = z + 0.5F;
					// domain warp: big slow bends so lobes are never round
					float wx = (vnoise(px / 16F, py / 8F, pz / 16F, nx / 16) - 0.5F) * 7F;
					float wy = (vnoise(px / 16F + 5.2F, py / 8F, pz / 16F, nx / 16) - 0.5F) * 3F;
					float wz = (vnoise(px / 16F, py / 8F + 3.7F, pz / 16F + 9.1F, nx / 16) - 0.5F) * 7F;
					float qx = px + wx, qy = py + wy, qz = pz + wz;
					// billowy fbm: |2n-1| turned over gives round tufts with sharp creases between them (cotton wool)
					float sum = 0, amp = 0.5F, tot = 0;
					int[] per = {nx / 8, nx / 4, nx / 2, nx};
					float[] fr = {1 / 8F, 1 / 4F, 1 / 2F, 1F};
					for (int o = 0; o < 4; o++) {
						float n = vnoise(qx * fr[o], qy * fr[o] * 1.3F, qz * fr[o], per[o]);
						n = 1 - Math.abs(2 * n - 1);
						sum += n * amp;
						tot += amp;
						amp *= 0.55F;
					}
					float e = sum / tot; // ~0.3..0.9
					// edges get eaten far more than the core; a little noise adds even in the middle (tufts poke out)
					float edge = 1 - Math.min(1, Math.max(0, (d - 0.5F) / 0.4F));
					v[i] = d + (e - 0.62F) * (0.35F + 0.75F * edge);
				}
			}
		}
		dens = v;
		// normals from the density gradient (two cells wide so the light bands stay smooth)
		for (int x = 0; x < nx; x++) {
			for (int y = 0; y < ny; y++) {
				for (int z = 0; z < nz; z++) {
					int i = idx(x, y, z);
					if (v[i] <= 0.5F) {
						continue;
					}
					float gx = v[idx(x + 1, y, z)] - v[idx(x - 1, y, z)] + 0.5F * (v[idx(x + 2, y, z)] - v[idx(x - 2, y, z)]);
					float gy = (y + 1 < ny ? v[idx(x, y + 1, z)] : 0) - (y > 0 ? v[idx(x, y - 1, z)] : 0)
							+ 0.5F * ((y + 2 < ny ? v[idx(x, y + 2, z)] : 0) - (y > 1 ? v[idx(x, y - 2, z)] : 0));
					float gz = v[idx(x, y, z + 1)] - v[idx(x, y, z - 1)] + 0.5F * (v[idx(x, y, z + 2)] - v[idx(x, y, z - 2)]);
					float gl = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);
					if (gl < 1e-4F) {
						gy = -1;
						gl = 1;
						gx = gz = 0;
					}
					nrm[i * 3] = (byte) Math.round(-gx / gl * 127);
					nrm[i * 3 + 1] = (byte) Math.round(-gy / gl * 127);
					nrm[i * 3 + 2] = (byte) Math.round(-gz / gl * 127);
				}
			}
		}
	}

	private void sphere(float cx, float cy, float cz, float r, float prio) {
		puff(cx, cy, cz, r, r, r, prio);
	}

	private int topAt(float px, float pz) {
		for (int y = ny - 1; y >= 0; y--) {
			if (best[idx((int) Math.floor(px), y, (int) Math.floor(pz))] > -1e8F) {
				return y;
			}
		}
		return -1;
	}

	/** small cauliflower bumps on the upper surface around (cx, cz) */
	private void bumps(float cx, float cz, float R, int count, float scale) {
		for (int i = 0; i < count; i++) {
			double a = rng.nextDouble() * Math.PI * 2;
			float d = rnd(0.15F, 1.0F) * R * 0.85F;
			float px = cx + (float) Math.cos(a) * d, pz = cz + (float) Math.sin(a) * d;
			int top = topAt(px, pz);
			if (top < 0) {
				continue;
			}
			float r = (R * rnd(0.08F, 0.2F) + 0.8F) * scale;
			sphere(px, top + 0.5F - rnd(0, 0.6F), pz, r, 0.5F);
		}
	}

	/** wide flat-bottomed body */
	private void body(float cx, float cz, float base, float R, float flat) {
		int n = (int) (4 + R * 0.5F);
		for (int i = 0; i < n; i++) {
			double a = rng.nextDouble() * Math.PI * 2;
			float d = rnd(0, 0.75F) * R;
			float r = R * rnd(0.32F, 0.5F);
			puff(cx + (float) Math.cos(a) * d, base + r * 0.55F * flat, cz + (float) Math.sin(a) * d, r, r * flat, r, 0);
		}
	}

	/** domes climbing from the body toward height H, leaning a bit */
	private void column(float cx, float cz, float base, float R, float H, float widthTop) {
		float lx = gauss(0.15F), lz = gauss(0.15F);
		float y = base + R * 0.4F;
		for (int k = 0; k < 16 && y < base + H; k++) {
			float t = (y - base) / Math.max(H, 1);
			float r = R * (0.48F - (0.48F - widthTop) * t) * rnd(0.85F, 1.15F);
			int m = 1 + rng.nextInt(2);
			for (int j = 0; j < m; j++) {
				sphere(cx + lx * (y - base) + gauss(0.18F * R), y, cz + lz * (y - base) + gauss(0.18F * R), r, 0.2F);
			}
			y += r * rnd(0.55F, 0.8F);
		}
	}

	// ------------------------------------------------------------------ cloud kinds
	private void cumulus(float cx, float cz, float R, float tallness) {
		float base = rnd(1, 3);
		float H = Math.min(ny - 1.5F, R * (0.55F + 0.9F * tallness));
		body(cx, cz, base, R, 1);
		column(cx, cz, base, R, H, 0.22F);
		bumps(cx, cz, R, (int) (10 + R * 2.2F), 1);
	}

	private void pancake(float cx, float cz, float R) {
		float base = rnd(1, 3);
		double ang = rng.nextDouble() * Math.PI;
		float ax = (float) Math.cos(ang), az = (float) Math.sin(ang);
		int n = (int) (5 + R * 0.6F);
		for (int i = 0; i < n; i++) {
			float t = rnd(-1, 1), s = rnd(-0.4F, 0.4F);
			float px = cx + (ax * t * 1.5F - az * s) * R, pz = cz + (az * t * 1.5F + ax * s) * R;
			float r = R * rnd(0.35F, 0.55F);
			puff(px, base + 1.2F, pz, r * 1.4F, Math.max(1.4F, r * 0.32F), r * 1.4F, 0);
		}
		bumps(cx, cz, R * 1.3F, (int) (8 + R * 1.5F), 0.7F);
	}

	private void tower(float cx, float cz, float R) {
		float base = rnd(1, 2.5F);
		body(cx, cz, base, R * 0.9F, 1);
		column(cx, cz, base, R, ny - 2.5F - base, 0.28F);
		bumps(cx, cz, R * 0.8F, (int) (14 + R * 2.5F), 1.1F);
	}

	private void anvil(float cx, float cz, float R) {
		tower(cx, cz, R * 0.85F);
		float top = ny - 3.5F;
		double ang = rng.nextDouble() * Math.PI * 2;
		float ax = (float) Math.cos(ang), az = (float) Math.sin(ang);
		for (int i = 0; i < 9; i++) {
			float t = (i / 8F) * 2 - 1;
			float px = cx + ax * (t * 1.4F + 0.4F) * R, pz = cz + az * (t * 1.4F + 0.4F) * R;
			float r = R * (0.42F - 0.15F * Math.abs(t));
			puff(px, top + rnd(-0.4F, 0.4F), pz, r * 1.3F, Math.max(1.3F, r * 0.35F), r * 1.3F, 0.3F);
		}
	}

	private void twins(float cx, float cz, float R) {
		double ang = rng.nextDouble() * Math.PI * 2;
		float ox = (float) Math.cos(ang) * R * 0.75F, oz = (float) Math.sin(ang) * R * 0.75F;
		cumulus(cx - ox, cz - oz, R * 0.8F, rnd(0.5F, 1));
		cumulus(cx + ox, cz + oz, R * 0.7F, rnd(0.2F, 0.8F));
	}

	private void street(float cx, float cz, float R) {
		// a row of small cumulus lined up with the wind
		int n = 4 + rng.nextInt(5);
		float step = R * rnd(1.4F, 2.1F);
		for (int i = 0; i < n; i++) {
			float px = cx + (i - n / 2F) * step * 0.943F + gauss(R * 0.3F);
			float pz = cz + (i - n / 2F) * step * 0.333F + gauss(R * 0.3F);
			cumulus(px, pz, R * rnd(0.45F, 0.75F), rnd(0, 0.5F));
		}
	}

	private void fragments(float cx, float cz, float R) {
		int n = 3 + rng.nextInt(5);
		for (int i = 0; i < n; i++) {
			float px = cx + gauss(R), pz = cz + gauss(R);
			float r = R * rnd(0.15F, 0.3F);
			float base = rnd(2, 6);
			for (int j = 0; j < 3; j++) {
				puff(px + gauss(r), base + gauss(0.6F), pz + gauss(r), r * rnd(1, 1.6F), Math.max(1.1F, r * 0.6F), r * rnd(1, 1.6F), 0);
			}
		}
	}

	/** a huge tiered cloud castle (Laputa-like): stacked wide terraces narrowing upward, towers on the rim */
	private void castle(float cx, float cz, float R) {
		R = Math.max(R, 14);
		float y = rnd(1, 2);
		float r = R;
		for (int tier = 0; tier < 5 && y < ny - 3; tier++) {
			int n = 8 + tier * 2;
			for (int i = 0; i < n; i++) {
				double a = rng.nextDouble() * Math.PI * 2;
				float d = rnd(0, 0.7F) * r;
				float pr = r * rnd(0.3F, 0.45F);
				puff(cx + (float) Math.cos(a) * d, y + pr * 0.4F, cz + (float) Math.sin(a) * d, pr, pr * 0.5F, pr, tier * 0.1F);
			}
			for (int k = 0; k < 3; k++) { // little turrets on the rim
				double a = rng.nextDouble() * Math.PI * 2;
				sphere(cx + (float) Math.cos(a) * r * 0.8F, y + r * 0.3F, cz + (float) Math.sin(a) * r * 0.8F, r * 0.2F, 0.4F);
			}
			y += r * rnd(0.35F, 0.5F);
			r *= 0.72F;
		}
		bumps(cx, cz, R, (int) (30 + R * 2), 1.2F);
	}

	/** a flock of small round "sheep" puffs drifting together */
	private void sheep(float cx, float cz, float R) {
		int n = 10 + rng.nextInt(14);
		for (int i = 0; i < n; i++) {
			float px = cx + gauss(R * 1.4F), pz = cz + gauss(R * 1.4F);
			float r = R * rnd(0.18F, 0.32F) + 1;
			float base = rnd(3, 7);
			sphere(px, base + r * 0.6F, pz, r, 0);
			sphere(px + r * 0.5F, base + r * 0.9F, pz + gauss(r * 0.3F), r * 0.7F, 0.3F);
		}
	}

	/** a long low wall of cloud (shelf / front), lumpy on top */
	private void wall(float cx, float cz, float R) {
		double ang = rng.nextDouble() * Math.PI;
		float ax = (float) Math.cos(ang), az = (float) Math.sin(ang);
		float len = R * rnd(4, 7);
		for (float t = -len / 2; t < len / 2; t += R * 0.4F) {
			float px = cx + ax * t + gauss(R * 0.15F), pz = cz + az * t + gauss(R * 0.15F);
			float r = R * rnd(0.45F, 0.7F);
			puff(px, 2 + r * 0.45F, pz, r, r * 0.6F, r * 0.8F, 0);
			if (rng.nextFloat() < 0.4F) {
				sphere(px, 2 + r, pz, r * 0.55F, 0.3F);
			}
		}
		bumps(cx, cz, len * 0.5F, (int) (len * 1.2F), 0.8F);
	}

	/** thin flat sheets high up (altostratus-like wisps), only a cell or two thick */
	private void sheet(float cx, float cz, float R) {
		float y = ny - rnd(3, 5);
		int n = 6 + rng.nextInt(6);
		for (int i = 0; i < n; i++) {
			float px = cx + gauss(R), pz = cz + gauss(R * 0.6F);
			float r = R * rnd(0.4F, 0.8F);
			puff(px, y, pz, r * 1.6F, 1.2F, r, 0);
		}
	}

	/** a huge billowy cumulus: a dome filled with many similar bubbles, arms reaching out sideways, little satellites */
	private void billow(float cx, float cz, float R) {
		float base = rnd(1, 2.5F);
		float H = Math.min(ny - 2.5F - base, R * rnd(0.6F, 1.1F));
		body(cx, cz, base, R * 0.8F, 0.8F);
		int n = (int) (20 + R * 2.5F);
		for (int i = 0; i < n; i++) {
			double a = rng.nextDouble() * Math.PI * 2;
			float h = (float) Math.pow(rng.nextFloat(), 0.9);
			float rad = R * (1 - 0.6F * h) * (float) Math.sqrt(rng.nextFloat()) * 0.85F;
			float r = Math.max(1.6F, R * rnd(0.2F, 0.32F) * (1 - 0.3F * h));
			sphere(cx + (float) Math.cos(a) * rad, base + 1 + h * H, cz + (float) Math.sin(a) * rad, r, 0.2F + h * 0.3F);
		}
		// arms: a chain of shrinking bubbles reaching out and a little up
		int arms = 1 + rng.nextInt(3);
		for (int k = 0; k < arms; k++) {
			double a = rng.nextDouble() * Math.PI * 2;
			float ax = (float) Math.cos(a), az = (float) Math.sin(a);
			float y = base + H * rnd(0.2F, 0.55F), d = R * 0.6F, len = R * rnd(1.2F, 1.9F);
			float rise = rnd(-0.05F, 0.25F);
			while (d < len) {
				float t = d / len;
				float r = Math.max(1.3F, R * (0.3F - 0.18F * t) * rnd(0.85F, 1.15F));
				sphere(cx + ax * d + gauss(r * 0.3F), y + rise * (d - R * 0.6F) + gauss(0.5F), cz + az * d + gauss(r * 0.3F), r, 0.3F);
				if (rng.nextFloat() < 0.5F) {
					sphere(cx + ax * d + gauss(r * 0.5F), y + rise * (d - R * 0.6F) + r * 0.6F, cz + az * d + gauss(r * 0.5F), r * 0.7F, 0.45F);
				}
				d += r * rnd(0.7F, 1.0F);
			}
		}
		bumps(cx, cz, R * 0.9F, (int) (12 + R * 1.8F), 1.1F);
		// lumpy belly: rounded bubbles at different heights under the body so it is not one flat floor
		for (int i = 0; i < (int) (6 + R * 0.5F); i++) {
			double a = rng.nextDouble() * Math.PI * 2;
			float d = R * rnd(0.3F, 0.95F);
			float r = Math.max(1.6F, R * rnd(0.16F, 0.26F));
			sphere(cx + (float) Math.cos(a) * d, base + r * rnd(0.55F, 1.1F), cz + (float) Math.sin(a) * d, r, 0.35F);
		}
		satellites(cx, cz, R);
	}

	/** small torn bits floating around a big cloud */
	private void satellites(float cx, float cz, float R) {
		int n = 3 + rng.nextInt(7);
		for (int i = 0; i < n; i++) {
			double a = rng.nextDouble() * Math.PI * 2;
			float d = R * rnd(1.1F, 1.9F);
			float r = rnd(1.0F, 2.4F);
			float px = cx + (float) Math.cos(a) * d, pz = cz + (float) Math.sin(a) * d, py = rnd(2, Math.min(ny - 3, R * 0.8F));
			sphere(px, py, pz, r, 0);
			if (rng.nextFloat() < 0.6F) {
				sphere(px + gauss(r), py + gauss(0.5F), pz + gauss(r), r * 0.7F, 0.2F);
			}
		}
	}

	/** wind-sheared pillow streak: a long row of flattened pillows in one or two tiers, tapering into ragged ends */
	private void pillows(float cx, float cz, float R) {
		double ang = Math.atan2(0.333, 0.943) + gauss(0.35F);
		float ax = (float) Math.cos(ang), az = (float) Math.sin(ang);
		float base = rnd(2, 6);
		float half = R * rnd(1.6F, 2.6F);
		for (float t = -half; t < half; t += R * rnd(0.25F, 0.4F)) {
			float taper = (float) Math.sqrt(Math.max(0, 1 - Math.abs(t) / half));
			float side = gauss(R * 0.22F);
			float px = cx + ax * t - az * side, pz = cz + az * t + ax * side;
			float r = Math.max(1.4F, R * rnd(0.32F, 0.5F) * (0.35F + 0.65F * taper));
			puff(px, base + r * 0.25F, pz, r * 1.15F, Math.max(1.3F, r * 0.42F), r * 1.15F, 0);
			if (taper > 0.35F && rng.nextFloat() < 0.65F) {
				float r2 = r * rnd(0.55F, 0.8F);
				puff(px + gauss(r * 0.3F), base + r * 0.25F + Math.max(1.3F, r * 0.42F) * 0.9F, pz + gauss(r * 0.3F), r2, Math.max(1.2F, r2 * 0.5F), r2, 0.3F);
			}
		}
		// ragged tails
		for (int i = 0; i < 6; i++) {
			float t = (rng.nextBoolean() ? 1 : -1) * half * rnd(0.9F, 1.4F);
			float px = cx + ax * t + gauss(R * 0.3F), pz = cz + az * t + gauss(R * 0.3F);
			float r = rnd(1.0F, 2.2F);
			puff(px, base + gauss(0.6F) + 1, pz, r * 1.3F, Math.max(1.0F, r * 0.5F), r * 1.3F, 0);
		}
	}

	/** "giant" look like the Ghibli references: few huge billowy clouds, pillow streaks, tiny bits between */
	private void giantDay() {
		int count = Math.max(4, Math.round((27 + rng.nextInt(8)) * countMul));
		for (int c = 0; c < count; c++) {
			float cx = rng.nextFloat() * nx, cz = rng.nextFloat() * nz;
			float p = rng.nextFloat();
			if (p < 0.42F) {
				billow(cx, cz, (float) Math.max(12, Math.min(26, sizeMul * Math.exp(Math.log(17) + rng.nextGaussian() * 0.25))));
			} else if (p < 0.7F) {
				pillows(cx, cz, sizeR(11F));
			} else if (p < 0.8F) {
				tower(cx, cz, sizeR(10F));
			} else if (p < 0.92F) {
				cumulus(cx, cz, sizeR(6F), rnd(0, 0.5F));
			} else {
				fragments(cx, cz, sizeR(7F));
			}
		}
	}

	private float sizeR(float mean) {
		return (float) Math.max(3.5, Math.min(26, sizeMul * Math.exp(Math.log(mean) + rng.nextGaussian() * 0.45)));
	}

	/** fills the grid; the day's weather decides how many clouds of each kind */
	public GhibliGen generate() {
		// weather of the day: 0 fair weather, 1 big cumulus, 2 towering afternoon, 3 cloud streets, 4 mixed / broken
		if (cotton && dens == null) {
			dens = new float[nx * ny * nz];
		}
		String st = style;
		if ("auto".equals(st)) {
			String[] days = {"fair", "cumulus", "towers", "streets", "mixed", "sheep", "storm", "castle"};
			float[] chance = {0.18F, 0.2F, 0.14F, 0.1F, 0.18F, 0.08F, 0.07F, 0.05F};
			float p = rng.nextFloat(), acc = 0;
			st = "mixed";
			for (int i = 0; i < days.length; i++) {
				acc += chance[i];
				if (p < acc) {
					st = days[i];
					break;
				}
			}
		}
		if ("giant".equals(st)) {
			giantDay();
			return finish();
		}
		float[] w = switch (st) {
			case "fair" -> new float[]{0.45F, 0.12F, 0, 0.12F, 0, 0.08F, 0, 0.13F, 0, 0.1F, 0, 0};
			case "cumulus" -> new float[]{0.12F, 0.42F, 0.08F, 0.07F, 0.03F, 0.12F, 0, 0.08F, 0, 0, 0.03F, 0.05F};
			case "towers" -> new float[]{0.08F, 0.2F, 0.3F, 0.04F, 0.14F, 0.14F, 0, 0.06F, 0.02F, 0, 0, 0.02F};
			case "streets" -> new float[]{0.08F, 0.12F, 0, 0.1F, 0, 0.05F, 0.5F, 0.1F, 0, 0, 0, 0.05F};
			case "sheep" -> new float[]{0.2F, 0.05F, 0, 0.05F, 0, 0, 0, 0.1F, 0, 0.6F, 0, 0};
			case "storm" -> new float[]{0.05F, 0.15F, 0.25F, 0, 0.25F, 0.1F, 0, 0.05F, 0, 0, 0.15F, 0};
			case "castle" -> new float[]{0.2F, 0.25F, 0.05F, 0.1F, 0, 0.05F, 0, 0.15F, 0.12F, 0.08F, 0, 0};
			default -> new float[]{0.16F, 0.2F, 0.09F, 0.1F, 0.05F, 0.09F, 0.07F, 0.1F, 0.02F, 0.06F, 0.03F, 0.03F};
		};
		int count = switch (st) {
			case "fair", "sheep" -> 70 + rng.nextInt(20);
			case "streets" -> 30 + rng.nextInt(10);
			case "castle" -> 32 + rng.nextInt(10);
			default -> 40 + rng.nextInt(16);
		};
		count = Math.max(4, Math.round(count * countMul));
		for (int c = 0; c < count; c++) {
			float cx = rng.nextFloat() * nx, cz = rng.nextFloat() * nz;
			float pick = rng.nextFloat();
			int kind = 0;
			for (float acc = w[0]; kind < w.length - 1 && pick > acc; acc += w[++kind]) {
			}
			switch (kind) {
				case 0 -> cumulus(cx, cz, sizeR(5.5F), rnd(0, 0.35F));           // small fair-weather puffs
				case 1 -> cumulus(cx, cz, sizeR(10.5F), rng.nextFloat());          // big cumulus
				case 2 -> tower(cx, cz, sizeR(9.5F));                             // towering cauliflower
				case 3 -> pancake(cx, cz, sizeR(9F));                             // flat, wide
				case 4 -> anvil(cx, cz, sizeR(10F));                              // spreading top
				case 5 -> twins(cx, cz, sizeR(9F));                               // two merged towers
				case 6 -> street(cx, cz, sizeR(7F));                              // a line along the wind
				case 7 -> fragments(cx, cz, sizeR(8F));                           // torn bits
				case 8 -> castle(cx, cz, sizeR(16F));                             // giant tiered cloud castle
				case 9 -> sheep(cx, cz, sizeR(6F));                               // a flock of little puffs
				case 10 -> wall(cx, cz, sizeR(8F));                               // long low shelf
				default -> sheet(cx, cz, sizeR(10F));                             // thin high sheets
			}
		}
		return finish();
	}

	private GhibliGen finish() {
		int n = nx * ny * nz;
		if (dens != null) {
			cottonize();
		}
		for (int i = 0; i < n; i++) {
			occ[i] = (byte) (dens != null ? (dens[i] > 0.5F ? 1 : 0) : (best[i] > -1e8F ? 1 : 0));
		}
		for (int x = 0; x < nx; x++) {
			for (int z = 0; z < nz; z++) {
				occ[idx(x, 0, z)] = 0; // flat bases: the bottom row stays empty
			}
		}
		return this;
	}

	/** preview helper: one cloud of each kind in a row along x (z = nz / 2) */
	public GhibliGen showcase() {
		float z = nz / 2F;
		cumulus(16, z, 5.5F, 0.2F);
		cumulus(48, z, 10.5F, 0.7F);
		tower(80, z, 9.5F);
		pancake(112, z, 9F);
		anvil(144, z, 10F);
		twins(176, z, 9F);
		street(208, z - 20, 7F);
		fragments(240, z, 8F);
		int n = nx * ny * nz;
		for (int i = 0; i < n; i++) {
			occ[i] = (byte) (best[i] > -1e8F ? 1 : 0);
		}
		return this;
	}

	/** preview helper: java GhibliGen out.png seed -> RGBA atlas (x + y * nx, z) like the WebGL preview expects */
	public static void main(String[] args) throws Exception {
		int nx = 256, ny = 22, nz = 256;
		GhibliGen g;
		if (args[1].equals("show")) {
			g = new GhibliGen(nx, ny, nz, 3).showcase();
		} else {
			g = new GhibliGen(nx, ny, nz, Long.parseLong(args[1]));
			if (args.length > 2) g.style = args[2];
			if (args.length > 3) g.cotton = true;
			g.generate();
		}
		java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(nx * ny, nz, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < ny; y++) {
			for (int x = 0; x < nx; x++) {
				for (int z = 0; z < nz; z++) {
					int i = g.idx(x, y, z);
					int a = g.dens != null ? Math.round(Math.max(0, Math.min(1, g.dens[i])) * 255) : (g.occ[i] != 0 ? 255 : 0);
					if (g.occ[i] == 0 && a > 127) a = 127;
					if (g.occ[i] != 0 && a < 128) a = 128;
					int r = g.nrm[i * 3] + 128, gg = g.nrm[i * 3 + 1] + 128, b = g.nrm[i * 3 + 2] + 128;
					img.setRGB(x + y * nx, z, a << 24 | (r & 255) << 16 | (gg & 255) << 8 | (b & 255));
				}
			}
		}
		javax.imageio.ImageIO.write(img, "png", new java.io.File(args[0]));
		long filled = 0;
		for (byte o : g.occ) filled += o;
		System.out.println("fill " + (filled / (double) g.occ.length));
	}
}
