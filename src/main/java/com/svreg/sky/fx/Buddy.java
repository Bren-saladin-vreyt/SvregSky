package com.svreg.sky.fx;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.svreg.sky.sky.CirrusSky;
import com.svreg.sky.sky.SkySettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * A little travelling friend: a round, fluffy spirit (roh kapuk) with a sprout on its head that floats beside the
 * player. It bobs along, hurries after you when you run, dozes off on the ground when you stand still for a while,
 * holds its sprout up as a leaf umbrella in the rain and glows softly at night. Drawn in the Effects particle draw.
 */
final class Buddy {
	static final int MAX_QUADS = 2;
	private static final Random RNG = new Random();
	private static double x = Double.NaN, y, z;
	private static float idle, blinkT = 2, blink, lean, fade;
	private static boolean left;
	private static double lastPX, lastPZ;

	private Buddy() {
	}

	static boolean anyOn() {
		return SkySettings.on("spirit_buddy");
	}

	static int emit(BufferBuilder b, ClientLevel level, Vec3 cam, float dt, float t, float rx, float ry, float rz, float ux, float uy, float uz) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer pl = mc.player;
		if (!anyOn() || pl == null || pl.isSpectator()) {
			x = Double.NaN;
			return 0;
		}
		boolean under = pl.isUnderWater();
		fade += ((under ? 0 : 1) - fade) * Math.min(1, dt * 3);
		if (fade < 0.02F) {
			return 0;
		}
		// how long the player has stood still
		double mv = Math.hypot(pl.getX() - lastPX, pl.getZ() - lastPZ);
		lastPX = pl.getX();
		lastPZ = pl.getZ();
		idle = mv > 0.02 || mc.options.keyAttack.isDown() || mc.options.keyUse.isDown() ? 0 : idle + dt;
		boolean asleep = idle > 25;
		float rain = CirrusSky.rainLevel();
		boolean umbrella = !asleep && rain > 0.25F && level.canSeeSky(BlockPos.containing(pl.getEyePosition()));
		float day = Math.max(CirrusSky.dayLevel(), CirrusSky.goldLevel());

		// where it wants to be: a little ahead and to the right of the player's view, or on the ground when asleep
		float yaw = (float) Math.toRadians(pl.getYRot());
		double fx = -Math.sin(yaw), fz = Math.cos(yaw);         // facing direction (horizontal)
		double sx = -fz, sz = fx;                                // to the right
		double tx, ty, tz;
		if (asleep) {
			tx = pl.getX() - sx * 0.9 + fx * 1.0;
			tz = pl.getZ() - sz * 0.9 + fz * 1.0;
			ty = pl.getY() + 0.28;
		} else {
			double bob = Math.sin(t * 2.2) * 0.09 + Math.sin(t * 0.7) * 0.05;
			// on the left of the view: the right side is where the held item is
			tx = pl.getX() - sx * 0.75 + fx * 1.2;
			tz = pl.getZ() - sz * 0.75 + fz * 1.2;
			ty = pl.getEyeY() - 0.15 + bob;
		}
		if (Double.isNaN(x) || Math.abs(x - tx) + Math.abs(z - tz) + Math.abs(y - ty) > 24) {
			x = tx;
			y = ty;
			z = tz;
		}
		double k = Math.min(1, dt * (asleep ? 1.2 : 2.6));
		double ox = x, oz = z;
		x += (tx - x) * k;
		y += (ty - y) * k;
		z += (tz - z) * k;
		// face the way it drifts (relative to the camera), lean into the motion
		double vx = (x - ox), vz = (z - oz);
		double side = vx * rx + vz * rz;
		if (Math.abs(side) > 0.004) {
			left = side < 0;
		}
		lean += ((float) Math.max(-0.35, Math.min(0.35, side * 6)) - lean) * Math.min(1, dt * 4);
		// blink now and then
		blinkT -= dt;
		if (blinkT <= 0) {
			blink = 0.14F;
			blinkT = 2.5F + RNG.nextFloat() * 3.5F;
		}
		blink = Math.max(0, blink - dt);

		int pose = asleep ? 1 : umbrella ? 2 : 0;
		int g = (blink > 0 ? 128 : 0) + (left ? 64 : 0);
		int lit = Math.round(255 * Math.max(0.15F, day));
		int argb = Math.round(255 * fade) << 24 | Math.round(pose * 255 / 3F) << 16 | g << 8 | lit;
		float px = (float) (x - cam.x), py = (float) (y - cam.y), pz = (float) (z - cam.z);
		// tilt the sprite a little with the lean (rotate right/up around the view axis)
		float cl = (float) Math.cos(lean), sl = (float) Math.sin(lean);
		float r2x = rx * cl + ux * sl, r2y = ry * cl + uy * sl, r2z = rz * cl + uz * sl;
		float u2x = ux * cl - rx * sl, u2y = uy * cl - ry * sl, u2z = uz * cl - rz * sl;
		int q = 0;
		float night = 1 - day;
		if (night > 0.2F) {
			// soft glow around it at night, like a little lantern
			Effects.billboard(b, px, py, pz, 0.55F, rx, ry, rz, ux, uy, uz, 0,
					Math.round(255 * 0.30F * night * fade) << 24 | 0xD8FF9A);
			q++;
		}
		Effects.billboard(b, px, py, pz, 0.24F, r2x, r2y, r2z, u2x, u2y, u2z, 34, argb);
		return q + 1;
	}
}
