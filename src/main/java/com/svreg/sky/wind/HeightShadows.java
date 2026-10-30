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
	/** per column: water depth (0 = land), and the shore distance field (packed into the alpha channel) */
	private static final byte[] depth = new byte[SIZE * SIZE];
	private static final int[] dist = new int[SIZE * SIZE];
	private static final byte[] alpha = new byte[SIZE * SIZE];
	private static ClientLevel lastLevel;
	private static long lastNanos, lastField, lastBind;

	private HeightShadows() {
	}

	/** world shadows on (the "world" or "both" method) */
	public static boolean shadowsOn() {
		return !broken && SkySettings.on("screen_shadows") && !"screen".equals(SkySettings.shadowMethod);
	}

	/** the column map is needed by the world shadows, the anime water or the shore waves */
	public static boolean active() {
		return !broken && (shadowsOn() || SkySettings.on("anime_water") || SkySettings.on("shore_waves") || SkySettings.on("underwater_light"));
	}

	/** the map has data (texture made and filled at least once) */
	public static boolean ready() {
		return tex >= 0 && originX != Integer.MIN_VALUE;
	}

	/** strength for the shader right now (0 = off) */
	public static float strength() {
		if (!shadowsOn() || tex < 0) {
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
			// once per frame is enough (this is called for every terrain region draw)
			long now = System.nanoTime();
			if (now - lastBind < 6_000_000L) {
				return;
			}
			lastBind = now;
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
			if (rebuilding || now - lastNanos > 50_000_000L) {
				lastNanos = now;
				refresh(level, camX, camZ);
			}
			GlStateManager._activeTexture(prevUnit);
		} catch (Throwable t) {
			broken = true;
			SvregSky.LOG.error("SvregSky: world sun shadows failed, turning them off", t);
		}
	}

	private static boolean rebuilding;
	private static int buildX, buildZ, buildRow;

	private static void refresh(ClientLevel level, double camX, double camZ) {
		int cx = ((int) Math.floor(camX) >> 4 << 4) - SIZE / 2, cz = ((int) Math.floor(camZ) >> 4 << 4) - SIZE / 2;
		if (!rebuilding && (level != lastLevel || Math.abs(cx - buildX) >= 16 || Math.abs(cz - buildZ) >= 16 || originX == Integer.MIN_VALUE)) {
			// moved a chunk or more: rebuild the whole map over a few frames, then swap it in at once
			lastLevel = level;
			buildX = cx;
			buildZ = cz;
			buildRow = 0;
			rebuilding = true;
			minY = level.getMinY();
		}
		int from, to;
		if (rebuilding) {
			from = buildRow;
			to = Math.min(SIZE, from + 32);
			buildRow = to;
		} else {
			// keep the map fresh for placed / broken blocks: 16 rows every 50 ms, the whole map in under a second
			from = nextRow;
			to = Math.min(SIZE, from + 16);
			nextRow = to >= SIZE ? 0 : to;
		}
		boolean plants = shadowsOn();
		boolean water = SkySettings.on("anime_water") || SkySettings.on("shore_waves") || SkySettings.on("underwater_light");
		net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
		for (int z = from; z < to; z++) {
			for (int x = 0; x < SIZE; x++) {
				int wx = buildX + x, wz = buildZ + z;
				int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, wx, wz);
				int h = Math.max(0, Math.min(65535, top - minY));
				// grass, ferns and flowers on top of the column: they cast small soft shadows too (height in 1/128 block)
				int plant = 0;
				if (plants) {
					pos.set(wx, top, wz);
					if (plant(level, pos)) {
						pos.setY(top + 1);
						plant = plant(level, pos) ? 230 : 100;
					}
				}
				// water: how deep (blocks of water under the surface, up to 15)
				int d = 0;
				if (water) {
					pos.set(wx, top - 1, wz);
					while (d < 15 && level.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER)) {
						d++;
						pos.setY(top - 1 - d);
					}
				}
				depth[z * SIZE + x] = (byte) d;
				int i = (z * SIZE + x) * 4;
				buf.put(i, (byte) (h & 255));
				buf.put(i + 1, (byte) (h >> 8));
				buf.put(i + 2, (byte) plant);
				buf.put(i + 3, alpha[z * SIZE + x]);
			}
		}
		boolean full = false;
		if (rebuilding) {
			if (to < SIZE) {
				return; // keep building; the old map stays in use until the new one is complete
			}
			rebuilding = false;
			nextRow = 0;
			originX = buildX;
			originZ = buildZ;
			full = true;
		} else if (to == SIZE && System.nanoTime() - lastField > 2_000_000_000L) {
			full = true;
		}
		if (full || fullUpload) {
			lastField = System.nanoTime();
			if (water) {
				shoreField();
			}
			from = 0;
			to = SIZE;
			fullUpload = false;
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

	/**
	 * Distance (in blocks, up to 15) from every column to the nearest column of the other kind (water vs land),
	 * a two-pass chamfer transform; stored with the water depth in the alpha channel: depth << 4 | distance.
	 */
	private static void shoreField() {
		final int INF = 1 << 20;
		for (int pass = 0; pass < 2; pass++) {
			// pass 0: distance of water columns to land; pass 1: distance of land columns to water
			boolean wantWater = pass == 0;
			for (int i = 0; i < SIZE * SIZE; i++) {
				boolean water = depth[i] > 0;
				dist[i] = water == wantWater ? INF : 0;
			}
			for (int z = 0; z < SIZE; z++) {
				for (int x = 0; x < SIZE; x++) {
					int i = z * SIZE + x, v = dist[i];
					if (v == 0) continue;
					if (x > 0) v = Math.min(v, dist[i - 1] + 3);
					if (z > 0) {
						v = Math.min(v, dist[i - SIZE] + 3);
						if (x > 0) v = Math.min(v, dist[i - SIZE - 1] + 4);
						if (x < SIZE - 1) v = Math.min(v, dist[i - SIZE + 1] + 4);
					}
					dist[i] = v;
				}
			}
			for (int z = SIZE - 1; z >= 0; z--) {
				for (int x = SIZE - 1; x >= 0; x--) {
					int i = z * SIZE + x, v = dist[i];
					if (v == 0) continue;
					if (x < SIZE - 1) v = Math.min(v, dist[i + 1] + 3);
					if (z < SIZE - 1) {
						v = Math.min(v, dist[i + SIZE] + 3);
						if (x < SIZE - 1) v = Math.min(v, dist[i + SIZE + 1] + 4);
						if (x > 0) v = Math.min(v, dist[i + SIZE - 1] + 4);
					}
					dist[i] = v;
				}
			}
			for (int i = 0; i < SIZE * SIZE; i++) {
				boolean water = depth[i] > 0;
				if (water == wantWater) {
					int d = Math.min(15, Math.max(1, (dist[i] + 1) / 3));
					alpha[i] = (byte) ((depth[i] << 4) | d);
				}
			}
		}
		for (int i = 0; i < SIZE * SIZE; i++) {
			buf.put(i * 4 + 3, alpha[i]);
		}
	}

	private static boolean plant(ClientLevel level, net.minecraft.core.BlockPos pos) {
		net.minecraft.world.level.block.state.BlockState s = level.getBlockState(pos);
		return !s.isAir() && s.getFluidState().isEmpty() && Wind.kind(s) > 0 && s.getCollisionShape(level, pos).isEmpty();
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
