package com.svreg.sky.sky;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.svreg.sky.SvregSky;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Where the raymarched puff clouds are, seen from above: a 256 x 256 map (4 blocks per texel, 1024 blocks around the
 * camera, in cloud space) of how thick the cloud cover is, worked out on the CPU from the same weather map and the
 * same rule as cirrus_crumble.fsh. It feeds the soft cloud shadows on the terrain (a texture on unit 6 of the Sodium
 * terrain shader) and the light shafts in the sky (the sky's cloud footprint texture).
 */
public final class PuffShadow {
	public static final int N = 256, UNIT = 6;
	public static final float CELL = 4.0F;
	/** must match WTILE and SHEAR in cirrus_crumble.fsh; MID = the cloud height the shadow is cast from */
	private static final float WTILE = 1280.0F, SHEAR = 0.12F, MID = 60.0F;
	private static float[] weather;
	private static final byte[] cover = new byte[N * N];
	private static float originX = Float.NaN, originZ, amount = -1, scale = -1;
	private static long lastNanos;
	private static int version;
	private static int tex = -1;
	private static boolean dirty, broken;
	private static ByteBuffer buf;

	private PuffShadow() {
	}

	/** the weather map's cover channel at its 256 x 256 texels, the same values the shader's atlas holds */
	private static void bakeWeather() {
		weather = new float[CrumbleNoise.WN * CrumbleNoise.WN];
		for (int z = 0; z < CrumbleNoise.WN; z++) {
			for (int x = 0; x < CrumbleNoise.WN; x++) {
				float r = CrumbleNoise.weather((x + 0.5F) / CrumbleNoise.WN, (z + 0.5F) / CrumbleNoise.WN)[0];
				weather[x + z * CrumbleNoise.WN] = Math.round(Math.max(0, Math.min(1, r)) * 255) / 255F;
			}
		}
	}

	/** the cover at a cloud-space position, bilinear and tiling like the shader's texture read */
	private static float coverAt(float x, float z) {
		int n = CrumbleNoise.WN;
		float u = (float) (x / WTILE - Math.floor(x / WTILE)) * n - 0.5F, v = (float) (z / WTILE - Math.floor(z / WTILE)) * n - 0.5F;
		int x0 = (int) Math.floor(u), z0 = (int) Math.floor(v);
		float fx = u - x0, fz = v - z0;
		int xa = Math.floorMod(x0, n), xb = Math.floorMod(x0 + 1, n), za = Math.floorMod(z0, n), zb = Math.floorMod(z0 + 1, n);
		float a = weather[xa + za * n], b = weather[xb + za * n], c = weather[xa + zb * n], d = weather[xb + zb * n];
		return (a + (b - a) * fx) * (1 - fz) + (c + (d - c) * fx) * fz;
	}

	private static float smooth(float e0, float e1, float x) {
		float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
		return t * t * (3 - 2 * t);
	}

	/** recomputes the map around the camera (cloud space) a few times a second, or at once when it moved far */
	public static void update(float camX, float camZ, float amt) {
		if (weather == null) {
			bakeWeather();
		}
		long now = System.nanoTime();
		boolean far = Float.isNaN(originX) || Math.abs(camX - (originX + N * CELL / 2)) > 160 || Math.abs(camZ - (originZ + N * CELL / 2)) > 160;
		// "big clouds" read the weather map wider (gWS in cirrus_crumble.fsh)
		float ws = SkySettings.on("big_clouds") ? 0.48F : 1.0F;
		if (!far && now - lastNanos < 400_000_000L && Math.abs(amt - amount) < 0.02F && ws == scale) {
			return;
		}
		lastNanos = now;
		amount = amt;
		scale = ws;
		originX = (float) (Math.floor(camX / CELL) * CELL) - N * CELL / 2;
		originZ = (float) (Math.floor(camZ / CELL) * CELL) - N * CELL / 2;
		float lo = 0.55F - 0.06F * amt, hi = 0.80F - 0.06F * amt;
		for (int z = 0; z < N; z++) {
			for (int x = 0; x < N; x++) {
				float wx = originX + (x + 0.5F) * CELL - SHEAR * MID, wz = originZ + (z + 0.5F) * CELL;
				float c = smooth(lo, hi, coverAt(wx * ws, wz * ws));
				cover[x + z * N] = (byte) Math.round(c * 255);
			}
		}
		version++;
		dirty = true;
	}

	public static boolean ready() {
		return !Float.isNaN(originX);
	}

	public static int version() {
		return version;
	}

	/** cover 0..255 per texel, row-major (z rows) */
	public static byte[] cover() {
		return cover;
	}

	public static float originX() {
		return originX;
	}

	public static float originZ() {
		return originZ;
	}

	/** uploads the map if it changed and binds it to texture unit 6 for the terrain shader; false if unusable */
	public static boolean bindTerrain() {
		if (broken || !ready()) {
			return false;
		}
		try {
			int prevUnit = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
			GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + UNIT);
			if (tex < 0) {
				tex = GL11C.glGenTextures();
				GlStateManager._bindTexture(tex);
				GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
				GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
				GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, 33071);
				GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, 33071);
				GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, 33085, 0); // max level 0
				GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, 32856 /* RGBA8 */, N, N, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
				buf = MemoryUtil.memAlloc(N * N * 4);
				dirty = true;
				SvregSky.LOG.info("SvregSky: puff cloud shadow map ready ({}x{})", N, N);
			}
			GlStateManager._bindTexture(tex);
			GL33C.glBindSampler(UNIT, 0);
			if (dirty && GL11C.glGetInteger(GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING) == 0) {
				for (int i = 0; i < N * N; i++) {
					byte c = cover[i];
					buf.put(i * 4, c).put(i * 4 + 1, c).put(i * 4 + 2, c).put(i * 4 + 3, (byte) 255);
				}
				GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 4);
				GL11C.glPixelStorei(GL11C.GL_UNPACK_ROW_LENGTH, 0);
				GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_PIXELS, 0);
				GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_ROWS, 0);
				buf.position(0).limit(N * N * 4);
				GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, N, N, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, buf);
				buf.clear();
				dirty = false;
			}
			GlStateManager._activeTexture(prevUnit);
			return true;
		} catch (Throwable t) {
			broken = true;
			SvregSky.LOG.error("SvregSky: puff cloud shadows failed, turning them off", t);
			return false;
		}
	}
}
