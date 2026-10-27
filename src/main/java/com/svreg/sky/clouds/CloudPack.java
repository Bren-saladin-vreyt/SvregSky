package com.svreg.sky.clouds;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.svreg.sky.SvregSky;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the cloud setup from resource packs.
 *
 * <pre>
 * assets/svregsky/clouds/layers.json
 * {
 *   "layers": [
 *     {
 *       "texture": "svregsky:clouds/deck.png",   // density map, 1 pixel = one 12x12 block cloud cell
 *       "height": 0,                              // blocks above the vanilla cloud height
 *       "slices": [0.74, 0.81, 0.87],             // optional: stacked 4-block slices, one density threshold each (bottom to top)
 *       "threshold": 0.5,                         // used when "slices" is missing: a single flat layer
 *       "slice_spacing": 4,                       // blocks between slices
 *       "speed": 1.0,                             // drift speed (1 = vanilla)
 *       "time_offset": 0,                         // shifts the pattern so layers don't line up
 *       "haze": 0.0,                              // 0..1 fades the layer toward sky blue (looks farther away)
 *       "opacity": 1.0,                           // 0..1
 *       "color": "#FFFFFF"                        // multiplies the vanilla cloud colour
 *     }
 *   ]
 * }
 * </pre>
 *
 * Density of a pixel = brightness x alpha, so a vanilla-style white-on-transparent clouds.png works with threshold 0.5.
 */
public final class CloudPack {
	public static final Identifier CONFIG = Identifier.fromNamespaceAndPath(SvregSky.MOD_ID, "clouds/layers.json");

	/** one renderable slice: a ready mask plus how to draw it */
	public record SliceDef(boolean[] mask, int width, int height, float yOffset, float speed, long timeOffset,
						   float haze, float opacity, int color, boolean extraSlice) {
	}

	private CloudPack() {
	}

	/** returns null if the config is missing or broken (the caller falls back to the built-in clouds) */
	public static List<SliceDef> load(ResourceManager manager) {
		try (InputStream in = manager.open(CONFIG); Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
			JsonArray layers = root.getAsJsonArray("layers");
			List<SliceDef> out = new ArrayList<>();
			for (JsonElement el : layers) {
				readLayer(manager, el.getAsJsonObject(), out);
			}
			SvregSky.LOG.info("SvregSky: loaded {} cloud slices from {}", out.size(), CONFIG);
			return out;
		} catch (Exception e) {
			SvregSky.LOG.warn("SvregSky: could not read {} ({}), using built-in clouds", CONFIG, e.toString());
			return null;
		}
	}

	private static void readLayer(ResourceManager manager, JsonObject o, List<SliceDef> out) throws Exception {
		Identifier tex = Identifier.parse(o.get("texture").getAsString());
		float height = getFloat(o, "height", 0);
		float spacing = getFloat(o, "slice_spacing", 4);
		float speed = getFloat(o, "speed", 1);
		long timeOffset = o.has("time_offset") ? o.get("time_offset").getAsLong() : 0L;
		float haze = getFloat(o, "haze", 0);
		float opacity = getFloat(o, "opacity", 1);
		int color = o.has("color") ? parseColor(o.get("color").getAsString()) : 0xFFFFFF;

		float[] thresholds;
		if (o.has("slices")) {
			JsonArray arr = o.getAsJsonArray("slices");
			thresholds = new float[arr.size()];
			for (int i = 0; i < arr.size(); i++) {
				thresholds[i] = arr.get(i).getAsFloat();
			}
		} else {
			thresholds = new float[]{getFloat(o, "threshold", 0.5F)};
		}

		float[] density;
		int w, h;
		try (InputStream in = manager.open(tex); NativeImage img = NativeImage.read(in)) {
			w = img.getWidth();
			h = img.getHeight();
			density = new float[w * h];
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int argb = img.getPixel(x, y);
					float a = (argb >>> 24) / 255F;
					float r = (argb >> 16 & 0xFF) / 255F, g = (argb >> 8 & 0xFF) / 255F, b = (argb & 0xFF) / 255F;
					density[x + y * w] = (0.299F * r + 0.587F * g + 0.114F * b) * a;
				}
			}
		}

		// add top slices first so the farthest geometry is drawn first
		for (int k = thresholds.length - 1; k >= 0; k--) {
			boolean[] mask = new boolean[w * h];
			for (int i = 0; i < mask.length; i++) {
				mask[i] = density[i] >= thresholds[k];
			}
			out.add(new SliceDef(mask, w, h, height + k * spacing, speed, timeOffset, haze, opacity, color, k > 0));
		}
	}

	private static float getFloat(JsonObject o, String key, float def) {
		return o.has(key) ? o.get(key).getAsFloat() : def;
	}

	private static int parseColor(String s) {
		s = s.trim();
		if (s.startsWith("#")) {
			s = s.substring(1);
		}
		return Integer.parseInt(s, 16) & 0xFFFFFF;
	}
}
