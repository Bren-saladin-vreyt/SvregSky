package com.svreg.sky.clouds;

import com.svreg.sky.SvregSky;
import com.svreg.sky.mixin.CloudRendererAccessor;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replaces the single vanilla cloud layer with several vanilla CloudRenderer instances, one per slice.
 * The slices come from resource packs (see {@link CloudPack}); the mod jar ships a default setup,
 * and if no usable setup is found the built-in procedural clouds are used.
 * Every slice uses the vanilla cloud mesh, shader and pipeline, so each costs about as much as vanilla clouds.
 */
public final class CloudLayers {
	private static final Map<CloudRenderer, Boolean> OURS = Collections.synchronizedMap(new IdentityHashMap<>());
	private static List<Layer> layers;
	private static volatile List<CloudPack.SliceDef> pending;
	private static volatile boolean pendingSet;
	private static boolean rendering;

	private record Layer(CloudRenderer renderer, CloudPack.SliceDef def) {
	}

	private CloudLayers() {
	}

	public static boolean isOurs(CloudRenderer renderer) {
		return OURS.containsKey(renderer);
	}

	public static boolean isRendering() {
		return rendering;
	}

	/** called after every resource reload (vanilla clouds reload hook): read the pack setup, build it on the next frame */
	public static void reload(ResourceManager manager) {
		pending = CloudPack.load(manager);
		pendingSet = true;
	}

	private static CloudRenderer newRenderer(CloudPack.SliceDef def) {
		CloudRenderer r = new CloudRenderer();
		OURS.put(r, Boolean.TRUE);
		CloudRendererAccessor acc = (CloudRendererAccessor) (Object) r;
		acc.svregsky$setTexture(CloudMapGen.toTextureData(CloudMapGen.tidy(def.mask(), def.width(), def.height()), def.width(), def.height()));
		acc.svregsky$setNeedsRebuild(true);
		return r;
	}

	/** built-in clouds, used only when no resource pack setup could be read */
	private static List<CloudPack.SliceDef> builtIn() {
		int s = CloudMapGen.SIZE;
		List<CloudPack.SliceDef> list = new ArrayList<>();
		double[] high = CloudMapGen.density(4242, 9, 36);
		list.add(new CloudPack.SliceDef(CloudMapGen.threshold(high, 0.12), s, s, 96, 0.55F, 9_000_000L, 0.45F, 0.80F, 0xFFFFFF, false));
		double[] deck = CloudMapGen.density(7, 5, 24);
		boolean[] puffs = CloudMapGen.puffs(7, 0.035, deck, CloudMapGen.quantile(deck, 0.35));
		double[] coverage = {0.26, 0.19, 0.13, 0.08, 0.04};
		for (int k = coverage.length - 1; k >= 0; k--) {
			boolean[] mask = CloudMapGen.threshold(deck, coverage[k]);
			if (k == 0) {
				mask = CloudMapGen.or(mask, puffs);
			}
			list.add(new CloudPack.SliceDef(mask, s, s, k * 4, 1.0F, 0L, 0F, 1F, 0xFFFFFF, k > 0));
		}
		return list;
	}

	private static void build(List<CloudPack.SliceDef> defs) {
		closeLayers();
		long start = System.currentTimeMillis();
		List<Layer> list = new ArrayList<>();
		for (CloudPack.SliceDef def : defs) {
			list.add(new Layer(newRenderer(def), def));
		}
		layers = list;
		SvregSky.LOG.info("SvregSky: built {} cloud slices in {} ms", list.size(), System.currentTimeMillis() - start);
	}

	/** called instead of the vanilla cloud render */
	public static void render(int color, CloudStatus status, float bottomY, int range, Vec3 camera, long gameTime, float partialTicks) {
		if (pendingSet) {
			pendingSet = false;
			List<CloudPack.SliceDef> defs = pending;
			build(defs != null ? defs : builtIn());
		} else if (layers == null) {
			build(builtIn());
		}
		rendering = true;
		try {
			for (Layer layer : layers) {
				CloudPack.SliceDef d = layer.def();
				if (status == CloudStatus.FAST && d.extraSlice()) {
					continue; // flat clouds: the bottom slice of each stack is enough
				}
				long t = (long) (gameTime * (double) d.speed()) + d.timeOffset();
				layer.renderer().render(tint(color, d), status, bottomY + d.yOffset(), range, camera, t, partialTicks * d.speed());
			}
		} finally {
			rendering = false;
		}
	}

	/** applies the layer colour, fades it toward a cool sky blue (aerial haze) and sets its opacity */
	private static int tint(int argb, CloudPack.SliceDef d) {
		int a = argb >>> 24, r = argb >> 16 & 0xFF, g = argb >> 8 & 0xFF, b = argb & 0xFF;
		r = r * (d.color() >> 16 & 0xFF) / 255;
		g = g * (d.color() >> 8 & 0xFF) / 255;
		b = b * (d.color() & 0xFF) / 255;
		float haze = d.haze();
		r = Math.round(r * (1 - haze * 0.30F));
		g = Math.round(g * (1 - haze * 0.12F));
		b = Math.round(Math.min(255, b * (1 + haze * 0.05F)));
		a = Math.round(Math.min(255, Math.max(a, 230) * Math.max(0, Math.min(1, d.opacity()))));
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

	private static void closeLayers() {
		if (layers != null) {
			List<Layer> old = layers;
			layers = null;
			for (Layer layer : old) {
				layer.renderer().close();
				OURS.remove(layer.renderer());
			}
		}
	}

	public static void close() {
		closeLayers();
	}
}
