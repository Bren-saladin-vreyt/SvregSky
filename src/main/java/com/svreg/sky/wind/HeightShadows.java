package com.svreg.sky.wind;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.svreg.sky.SvregSky;
import com.svreg.sky.sky.CirrusSky;
import com.svreg.sky.sky.SkySettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * World sun shadows that work everywhere (also on phones, where the depth buffer cannot be read):
 * a 256x256 map of the tallest solid block of every column around the camera, uploaded as a texture.
 * The terrain shader walks from each pixel toward the sun over this map; if a column rises above the ray, the pixel is in shadow.
 * Shadows from houses, trees and hills, also from things off screen. Entities neither cast nor receive them.
 */
public final class HeightShadows {
	public static final int SIZE = 256, UNIT = 7;
	private static int tex = -1, originX = Integer.MIN_VALUE, originZ, minY, nextRow;
	private static boolean broken, fullUpload;
	private static ByteBuffer buf;
	private static ClientLevel lastLevel;
	private static long lastNanos;

	private HeightShadows() {
	}

	/** world shadows on (the "world" or "both" method) */
	public static boolean active() {
		return !broken && SkySettings.on("screen_shadows") && !"screen".equals(SkySettings.shadowMethod);
	}

	/** strength for the shader right now (0 = off) */
	public static float strength() {
		if (!active() || tex < 0) {
			return 0;
		}
		org.joml.Vector3f sun = CirrusSky.sunDirection();
		if (sun.y < 0.04F) {
			return 0;
		}
		return SkySettings.shadowDarkness * Math.min(1, CirrusSky.dayLevel() + CirrusSky.goldLevel()) * (1 - CirrusSky.rainLevel());
	}

	public static float originX() {
		return originX;
	}

	public static float originZ() {
		return originZ;
	}

	public static float minY() {
		return minY;
	}

	/** refreshes part of the height map (at most every few milliseconds) and binds it to texture unit 7 */
	public static void update(double camX, double camZ) {
		if (!active()) {
			return;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			ClientLevel level = mc.level;
			if (level == null) {
				return;
			}
			int prevUnit = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
			GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + UNIT);
			if (tex < 0) {
				tex = GL11C.glGenTextures();
				GlStateManager._bindTexture(tex);
				GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
				GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
				GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, 33071);
				GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, 33071);
				GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, 33085, 0); // max level 0
				GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, 32856 /* RGBA8 */, SIZE, SIZE, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
				buf = MemoryUtil.memAlloc(SIZE * SIZE * 4);
				SvregSky.LOG.info("SvregSky: world sun shadows ready (height map {}x{})", SIZE, SIZE);
			}
			GlStateManager._bindTexture(tex);
			GL33C.glBindSampler(UNIT, 0);
			long now = System.nanoTime();
			if (now - lastNanos > 8_000_000L) {
				lastNanos = now;
				refresh(level, camX, camZ);
			}
			GlStateManager._activeTexture(prevUnit);
		} catch (Throwable t) {
			broken = true;
			SvregSky.LOG.error("SvregSky: world sun shadows failed, turning them off", t);
		}
	}

	private static void refresh(ClientLevel level, double camX, double camZ) {
		int cx = ((int) Math.floor(camX) >> 4 << 4) - SIZE / 2, cz = ((int) Math.floor(camZ) >> 4 << 4) - SIZE / 2;
		int from, to;
		if (level != lastLevel || Math.abs(cx - originX) >= 16 || Math.abs(cz - originZ) >= 16) {
			// moved a chunk or more: rebuild everything
			lastLevel = level;
			originX = cx;
			originZ = cz;
			minY = level.getMinY();
			from = 0;
			to = SIZE;
			nextRow = 0;
		} else if (fullUpload) {
			from = 0;
			to = SIZE;
			nextRow = 0;
			fullUpload = false;
		} else {
			// keep the map fresh for placed / broken blocks: 16 rows per update, the whole map about every half second
			from = nextRow;
			to = Math.min(SIZE, from + 16);
			nextRow = to >= SIZE ? 0 : to;
		}
		for (int z = from; z < to; z++) {
			for (int x = 0; x < SIZE; x++) {
				int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING, originX + x, originZ + z) - minY;
				h = Math.max(0, Math.min(65535, h));
				int i = (z * SIZE + x) * 4;
				buf.put(i, (byte) (h & 255));
				buf.put(i + 1, (byte) (h >> 8));
				buf.put(i + 2, (byte) 0);
				buf.put(i + 3, (byte) 255);
			}
		}
		if (GL11C.glGetInteger(GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING) != 0) {
			fullUpload = true; // a pixel buffer is bound right now: upload everything next time
			return;
		}
		GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 4);
		GL11C.glPixelStorei(GL11C.GL_UNPACK_ROW_LENGTH, 0);
		GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_PIXELS, 0);
		GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_ROWS, 0);
		buf.position(from * SIZE * 4);
		buf.limit(to * SIZE * 4);
		GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, from, SIZE, to - from, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, buf.slice());
		buf.clear();
	}

	/** march steps, reach in blocks for the chosen quality */
	public static float[] quality() {
		return switch (SkySettings.shadowQuality) {
			case "low" -> new float[]{12, 40};
			case "high" -> new float[]{30, 96};
			case "ultra" -> new float[]{40, 124};
			default -> new float[]{20, 64};
		};
	}
}
