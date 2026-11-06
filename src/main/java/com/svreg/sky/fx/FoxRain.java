package com.svreg.sky.fx;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.svreg.sky.sky.Events;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * Fox rain (kitsune no yomeiri): a short shower falling while the sun shines. The drops catch the sunlight as thin
 * golden-white streaks, now and then one glints, and a rainbow stands in the shower (see CirrusSky).
 * Drawn in the Effects particle draw.
 */
final class FoxRain {
	static final int DROPS = 110;
	static final int MAX_QUADS = DROPS;
	private static final Random RNG = new Random();
	private static final float[] dx = new float[DROPS], dy = new float[DROPS], dz = new float[DROPS], sp = new float[DROPS];
	private static boolean seeded;

	private FoxRain() {
	}

	static boolean anyOn() {
		return com.svreg.sky.sky.SkySettings.on("fox_rain");
	}

	static int emit(BufferBuilder b, ClientLevel level, Vec3 cam, float dt, float t, float rx, float ry, float rz, float ux, float uy, float uz) {
		float k = Events.foxRain();
		if (k < 0.01F || Minecraft.getInstance().player == null) {
			return 0;
		}
		if (!seeded) {
			for (int i = 0; i < DROPS; i++) {
				dx[i] = (RNG.nextFloat() - 0.5F) * 24;
				dz[i] = (RNG.nextFloat() - 0.5F) * 24;
				dy[i] = RNG.nextFloat() * 16 - 4;
				sp[i] = 10 + RNG.nextFloat() * 4;
			}
			seeded = true;
		}
		int q = 0;
		int n = Math.round(DROPS * k);
		for (int i = 0; i < n; i++) {
			dy[i] -= sp[i] * dt;
			if (dy[i] < -6) {
				dy[i] += 18;
				dx[i] = (RNG.nextFloat() - 0.5F) * 24;
				dz[i] = (RNG.nextFloat() - 0.5F) * 24;
			}
			double wx = cam.x + dx[i], wz = cam.z + dz[i], wy = cam.y + dy[i];
			// no drops under a roof
			int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(wx), (int) Math.floor(wz));
			if (wy < top) {
				continue;
			}
			float x = dx[i], y = dy[i], z = dz[i];
			float glint = (float) Math.max(0, Math.sin(t * 7 + i * 1.7)) ;
			glint = glint > 0.97F ? 1 : 0.0F;
			int a = Math.round(255 * Math.min(1, k * (0.7F + 0.3F * glint)));
			int col = glint > 0 ? 0xFFFBEA : 0xFFE6A8;
			// a thin streak along the fall, facing the camera
			float cx = -z, cz = x;
			float cl = (float) Math.sqrt(cx * cx + cz * cz) + 1e-4F;
			float w = 0.02F;
			Effects.billboard(b, x, y, z, 1, cx / cl * w, 0, cz / cl * w, 0, 0.5F, 0, 38, a << 24 | col);
			q++;
		}
		return q;
	}
}
