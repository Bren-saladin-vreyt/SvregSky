package com.svreg.sky.fx;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.svreg.sky.sky.CirrusSky;
import com.svreg.sky.sky.SkySettings;
import com.svreg.sky.wind.WindState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * Kites on windy days: when the living wind picks up, a few bright kites rise over the open land around the
 * player, tugging and swaying with the gusts on long strings; when the wind calms they sink away.
 * Drawn in the Effects particle draw.
 */
final class Kites {
	static final int KITES = 4, SEGS = 7;
	static final int MAX_QUADS = KITES * (SEGS + 1);
	private static final Random RNG = new Random();
	private static final int[] COLORS = {0xE8473C, 0xF2B632, 0x3C8DE8, 0x4FBF7A, 0xE86FB0, 0x8E6CE8};

	private static final class Kite {
		double ax, ay, az;          // where the string is held (on the ground)
		float height, phase, life, rise;
		int color;
		boolean alive;
	}

	private static final Kite[] kites = new Kite[KITES];
	private static float spawnT = 5;

	static {
		for (int i = 0; i < KITES; i++) {
			kites[i] = new Kite();
		}
	}

	private Kites() {
	}

	static boolean anyOn() {
		return SkySettings.on("kites");
	}

	static int emit(BufferBuilder b, ClientLevel level, Vec3 cam, float dt, float t, float rx, float ry, float rz, float ux, float uy, float uz) {
		LocalPlayer pl = Minecraft.getInstance().player;
		if (pl == null) {
			return 0;
		}
		float wind = WindState.factor();
		float day = Math.max(CirrusSky.dayLevel(), CirrusSky.goldLevel());
		boolean windy = anyOn() && wind > 1.05F && day > 0.4F && CirrusSky.rainLevel() < 0.2F;
		spawnT -= dt;
		if (windy && spawnT <= 0) {
			spawnT = 6 + RNG.nextFloat() * 10;
			for (Kite k : kites) {
				if (!k.alive) {
					double a = RNG.nextDouble() * Math.PI * 2, r = 30 + RNG.nextDouble() * 45;
					int x = (int) Math.floor(pl.getX() + Math.cos(a) * r), z = (int) Math.floor(pl.getZ() + Math.sin(a) * r);
					int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
					if (Math.abs(y - pl.getY()) > 20 || !level.getFluidState(new net.minecraft.core.BlockPos(x, y - 1, z)).isEmpty()) {
						break;
					}
					k.ax = x + 0.5;
					k.ay = y + 1.2;
					k.az = z + 0.5;
					k.height = 22 + RNG.nextFloat() * 14;
					k.phase = RNG.nextFloat() * 6.28F;
					k.color = COLORS[RNG.nextInt(COLORS.length)];
					k.rise = 0;
					k.alive = true;
					break;
				}
			}
		}
		int q = 0;
		// the wind blows along (0.943, 0.333) like the grass waves and clouds
		float wx = 0.943F, wz = 0.333F;
		for (Kite k : kites) {
			if (!k.alive) {
				continue;
			}
			k.rise += ((windy ? 1 : 0) - k.rise) * Math.min(1, dt * (windy ? 0.25F : 0.4F));
			if (!windy && k.rise < 0.02F) {
				k.alive = false;
				continue;
			}
			if (Math.hypot(k.ax - pl.getX(), k.az - pl.getZ()) > 140) {
				k.alive = false;
				continue;
			}
			// the kite flies downwind of the holder, high up, swaying and tugging with the gusts
			float h = k.height * k.rise * (0.85F + 0.15F * Math.min(1.6F, wind));
			float sway = (float) Math.sin(t * 0.9 + k.phase) * 2.5F + (float) Math.sin(t * 2.3 + k.phase * 2) * 0.8F;
			float tug = (float) Math.sin(t * 1.7 + k.phase * 3) * 1.2F;
			double kx = k.ax + wx * (h * 0.7 + tug) - wz * sway;
			double ky = k.ay + h + tug * 0.5;
			double kz = k.az + wz * (h * 0.7 + tug) + wx * sway;
			// string: a gentle sag from the holder to the kite, drawn as short soft segments
			double px = k.ax, py = k.ay, pz = k.az;
			int strCol = Math.round(255 * 0.55F * k.rise) << 24 | 0xF2EEE4;
			for (int s = 1; s <= SEGS; s++) {
				float f = s / (float) SEGS;
				double sx = k.ax + (kx - k.ax) * f, sz = k.az + (kz - k.az) * f;
				double sy = k.ay + (ky - k.ay) * f - Math.sin(Math.PI * f) * h * 0.08;
				float mx = (float) ((px + sx) / 2 - cam.x), my = (float) ((py + sy) / 2 - cam.y), mz = (float) ((pz + sz) / 2 - cam.z);
				float dx = (float) (sx - px) / 2, dy = (float) (sy - py) / 2, dz = (float) (sz - pz) / 2;
				// width across the string, facing the camera
				float vx = mx, vy = my, vz = mz;
				float cx = dy * vz - dz * vy, cy = dz * vx - dx * vz, cz = dx * vy - dy * vx;
				float cl = (float) Math.sqrt(cx * cx + cy * cy + cz * cz) + 1e-5F;
				float w = 0.03F;
				Effects.billboard(b, mx, my, mz, 1, cx / cl * w, cy / cl * w, cz / cl * w, dx, dy, dz, 38, strCol);
				q++;
				px = sx;
				py = sy;
				pz = sz;
			}
			// the kite itself, tilting with the sway
			float tilt = sway * 0.08F;
			float ct = (float) Math.cos(tilt), st = (float) Math.sin(tilt);
			float r2x = rx * ct + ux * st, r2y = ry * ct + uy * st, r2z = rz * ct + uz * st;
			float u2x = ux * ct - rx * st, u2y = uy * ct - ry * st, u2z = uz * ct - rz * st;
			Effects.billboard(b, (float) (kx - cam.x), (float) (ky - cam.y), (float) (kz - cam.z), 1.3F, r2x, r2y, r2z, u2x, u2y, u2z, 36,
					Math.round(255 * Math.min(1, k.rise * 1.5F)) << 24 | k.color);
			q++;
		}
		return q;
	}
}
