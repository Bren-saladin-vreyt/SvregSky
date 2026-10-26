package com.svreg.sky.clouds;

import com.svreg.sky.SvregSky;
import com.svreg.sky.mixin.CloudRendererAccessor;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replaces the single vanilla cloud layer with several vanilla CloudRenderer instances:
 * <ul>
 *   <li>a cumulus deck made of stacked 4-block slices that shrink upward, so clouds tower like Ghibli cumulus;</li>
 *   <li>a high, sparse layer of smaller puffs, hazier and slower, so the sky reads as deep.</li>
 * </ul>
 * Every layer uses the vanilla cloud mesh, shader and pipeline, so the cost per layer is the same as vanilla clouds.
 */
public final class CloudLayers {
	/** slices of the main cumulus deck: share of the sky covered by each slice, bottom to top */
	private static final double[] DECK_COVERAGE = {0.26, 0.19, 0.13, 0.08, 0.04};
	private static final float SLICE_HEIGHT = 4.0F;     // vanilla cloud thickness
	private static final float HIGH_LAYER_OFFSET = 96.0F;

	private static final Map<CloudRenderer, Boolean> OURS = Collections.synchronizedMap(new IdentityHashMap<>());
	private static List<Layer> layers;
	private static boolean rendering;

	private record Layer(CloudRenderer renderer, float yOffset, float speed, long timeOffset, float haze, float alpha, boolean deckSlice) {
	}

	private CloudLayers() {
	}

	public static boolean isOurs(CloudRenderer renderer) {
		return OURS.containsKey(renderer);
	}

	public static boolean isRendering() {
		return rendering;
	}

	private static CloudRenderer newRenderer(boolean[] mask) {
		CloudRenderer r = new CloudRenderer();
		OURS.put(r, Boolean.TRUE);
		CloudRendererAccessor acc = (CloudRendererAccessor) (Object) r;
		acc.svregsky$setTexture(CloudMapGen.toTextureData(CloudMapGen.tidy(mask)));
		acc.svregsky$setNeedsRebuild(true);
		return r;
	}

	private static void build() {
		long start = System.currentTimeMillis();
		List<Layer> list = new ArrayList<>();

		// high, small, hazy puffs (rendered first: farthest from the ground)
		double[] high = CloudMapGen.density(4242, 9, 36);
		boolean[] highMask = CloudMapGen.threshold(high, 0.12);
		list.add(new Layer(newRenderer(highMask), HIGH_LAYER_OFFSET, 0.55F, 9_000_000L, 0.45F, 0.80F, false));

		// main cumulus deck: same density field, each slice keeps only the denser cores, so the stack forms domes
		double[] deck = CloudMapGen.density(7, 5, 24);
		boolean[] puffs = CloudMapGen.puffs(7, 0.035, deck, CloudMapGen.quantile(deck, 0.35));
		for (int k = DECK_COVERAGE.length - 1; k >= 0; k--) {
			boolean[] mask = CloudMapGen.threshold(deck, DECK_COVERAGE[k]);
			if (k == 0) {
				mask = CloudMapGen.or(mask, puffs);
			}
			list.add(new Layer(newRenderer(mask), k * SLICE_HEIGHT, 1.0F, 0L, 0.0F, 1.0F, true));
		}

		layers = list;
		SvregSky.LOG.info("SvregSky: built {} cloud layers in {} ms", list.size(), System.currentTimeMillis() - start);
	}

	/** called instead of the vanilla cloud render */
	public static void render(int color, CloudStatus status, float bottomY, int range, Vec3 camera, long gameTime, float partialTicks) {
		if (layers == null) {
			build();
		}
		rendering = true;
		try {
			for (Layer layer : layers) {
				if (status == CloudStatus.FAST && layer.deckSlice() && layer.yOffset() > 0) {
					continue; // flat clouds: one deck slice is enough
				}
				long t = (long) (gameTime * (double) layer.speed()) + layer.timeOffset();
				layer.renderer().render(tint(color, layer.haze(), layer.alpha()), status, bottomY + layer.yOffset(), range, camera, t, partialTicks * layer.speed());
			}
		} finally {
			rendering = false;
		}
	}

	/** fades a layer toward a cool sky blue (aerial haze) and sets its opacity */
	private static int tint(int argb, float haze, float alphaScale) {
		int a = argb >>> 24, r = argb >> 16 & 0xFF, g = argb >> 8 & 0xFF, b = argb & 0xFF;
		r = Math.round(r * (1 - haze * 0.30F));
		g = Math.round(g * (1 - haze * 0.12F));
		b = Math.round(Math.min(255, b * (1 + haze * 0.05F)));
		a = Math.round(Math.min(255, Math.max(a, 230) * alphaScale));
		return a << 24 | r << 16 | g << 8 | b;
	}

	public static void endFrame() {
		if (layers != null) {
			for (Layer layer : layers) {
				layer.renderer().endFrame();
			}
		}
	}

	public static void markForRebuild() {
		if (layers != null) {
			for (Layer layer : layers) {
				layer.renderer().markForRebuild();
			}
		}
	}

	public static void close() {
		if (layers != null) {
			List<Layer> old = layers;
			layers = null;
			for (Layer layer : old) {
				layer.renderer().close();
				OURS.remove(layer.renderer());
			}
		}
	}
}
