package com.svreg.sky.items;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.svreg.sky.SvregSky;
import com.svreg.sky.sky.SkySettings;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * Cartoon look for every 16x item texture (vanilla and modded), applied while the texture atlas loads:
 * brighter, more saturated colours and a bold dark outline. Hand-made textures from a pack that are bigger than 16x
 * (like the CirrusPack katanas) are left as they are. Toggle: "toon_items" in assets/svregsky/sky.json.
 */
public final class ToonItems {
	private static volatile long lastCheck;
	private static volatile boolean enabled = true;
	private static final java.util.Set<NativeImage> DONE = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

	private ToonItems() {
	}

	private static boolean enabled() {
		long now = System.currentTimeMillis();
		if (now - lastCheck > 3000) {
			lastCheck = now;
			boolean on = true;
			try (InputStream in = Minecraft.getInstance().getResourceManager().open(SkySettings.CONFIG);
				 Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
				JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
				if (o.has("toon_items")) {
					on = o.get("toon_items").getAsBoolean();
				}
			} catch (Exception ignored) {
			}
			enabled = on;
		}
		return enabled;
	}

	private static int seen;

	public static void process(Identifier name, NativeImage image) {
		try {
			if (seen < 6 || (name != null && name.getPath().contains("apple"))) {
				seen++;
				SvregSky.LOG.info("SvregSky: sprite {} {}x{}", name, image == null ? -1 : image.getWidth(), image == null ? -1 : image.getHeight());
			}
			if (name == null || image == null || !name.getPath().startsWith("item/")) {
				return;
			}
			int w = image.getWidth();
			if (w != 16 || !enabled() || !DONE.add(image)) {
				return;
			}
			apply(image);
			if (name.getPath().equals("item/diamond_sword") || name.getPath().equals("item/apple")) {
				SvregSky.LOG.info("SvregSky: cartoon item textures on ({})", name);
			}
		} catch (Throwable t) {
			SvregSky.LOG.warn("SvregSky: could not cartoon {}: {}", name, t.toString());
		}
	}

	private static void apply(NativeImage img) {
		int w = img.getWidth(), h = img.getHeight();
		int[] src = new int[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				src[x + y * w] = img.getPixel(x, y); // ARGB
			}
		}
		for (int y = 0; y < h; y++) {
			int frameTop = y - y % 16; // animated items: frames stacked vertically
			for (int x = 0; x < w; x++) {
				int c = src[x + y * w];
				int a = c >>> 24;
				if (a < 16) {
					continue;
				}
				float r = (c >> 16 & 0xFF) / 255F, g = (c >> 8 & 0xFF) / 255F, b = (c & 0xFF) / 255F;
				// saturation + a little brightness
				float l = 0.299F * r + 0.587F * g + 0.114F * b;
				float sat = 1.28F;
				r = l + (r - l) * sat;
				g = l + (g - l) * sat;
				b = l + (b - l) * sat;
				r = r * 1.06F + 0.015F;
				g = g * 1.06F + 0.015F;
				b = b * 1.06F + 0.015F;
				boolean edge = isEmpty(src, w, h, frameTop, x - 1, y) || isEmpty(src, w, h, frameTop, x + 1, y)
						|| isEmpty(src, w, h, frameTop, x, y - 1) || isEmpty(src, w, h, frameTop, x, y + 1);
				if (edge) {
					// bold toon outline: dark, tinted toward deep purple
					r = r * 0.38F + 0.16F * 0.62F * 0.6F;
					g = g * 0.38F + 0.10F * 0.62F * 0.6F;
					b = b * 0.38F + 0.22F * 0.62F * 0.6F;
				} else if (isEmpty(src, w, h, frameTop, x - 1, y - 1) || isEmpty(src, w, h, frameTop, x, y - 2)) {
					// soft rim light on the upper-left inside the outline
					r = r + (1 - r) * 0.18F;
					g = g + (1 - g) * 0.18F;
					b = b + (1 - b) * 0.15F;
				}
				int ri = Math.round(Math.max(0, Math.min(1, r)) * 255);
				int gi = Math.round(Math.max(0, Math.min(1, g)) * 255);
				int bi = Math.round(Math.max(0, Math.min(1, b)) * 255);
				img.setPixel(x, y, a << 24 | ri << 16 | gi << 8 | bi);
			}
		}
	}

	private static boolean isEmpty(int[] src, int w, int h, int frameTop, int x, int y) {
		if (x < 0 || x >= w || y < frameTop || y >= Math.min(h, frameTop + 16)) {
			return true;
		}
		return (src[x + y * w] >>> 24) < 16;
	}
}
