package com.svreg.sky.mixin;

import com.svreg.sky.sky.SkySettings;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ghibli greens: fresher, more saturated grass and leaf colours in every biome (toggle: ghibli_biome_colors, then F3+A) */
@Mixin(Biome.class)
public abstract class BiomeColorMixin {
	@Unique
	private static int svregsky$ghibli(int argb, float sat, float val, float hueTarget, float huePull) {
		float r = (argb >> 16 & 0xFF) / 255F, g = (argb >> 8 & 0xFF) / 255F, b = (argb & 0xFF) / 255F;
		float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
		float h = 0;
		if (d > 1e-5F) {
			if (max == r) h = ((g - b) / d) % 6;
			else if (max == g) h = (b - r) / d + 2;
			else h = (r - g) / d + 4;
			h *= 60;
			if (h < 0) h += 360;
		}
		float s = max <= 0 ? 0 : d / max, v = max;
		// pull greens toward a fresh yellow-green, leave reds/oranges (savanna, badlands) mostly alone
		if (h > 60 && h < 170) {
			float dh = hueTarget - h;
			h += dh * huePull;
		}
		s = Math.min(1, s * sat);
		v = Math.min(1, v * val);
		float c = v * s, x = c * (1 - Math.abs((h / 60) % 2 - 1)), m = v - c;
		float rr, gg, bb;
		int seg = (int) (h / 60) % 6;
		switch (seg) {
			case 0 -> { rr = c; gg = x; bb = 0; }
			case 1 -> { rr = x; gg = c; bb = 0; }
			case 2 -> { rr = 0; gg = c; bb = x; }
			case 3 -> { rr = 0; gg = x; bb = c; }
			case 4 -> { rr = x; gg = 0; bb = c; }
			default -> { rr = c; gg = 0; bb = x; }
		}
		int ri = Math.round((rr + m) * 255), gi = Math.round((gg + m) * 255), bi = Math.round((bb + m) * 255);
		return (argb & 0xFF000000) | ri << 16 | gi << 8 | bi;
	}

	@Inject(method = "getGrassColor", at = @At("RETURN"), cancellable = true)
	private void svregsky$grass(double x, double z, CallbackInfoReturnable<Integer> cir) {
		if (SkySettings.ghibliBiomes) {
			cir.setReturnValue(svregsky$ghibli(cir.getReturnValueI(), 1.22F, 1.07F, 98F, 0.25F));
		}
	}

	@Inject(method = "getFoliageColor", at = @At("RETURN"), cancellable = true)
	private void svregsky$foliage(CallbackInfoReturnable<Integer> cir) {
		if (SkySettings.ghibliBiomes) {
			cir.setReturnValue(svregsky$ghibli(cir.getReturnValueI(), 1.18F, 1.05F, 110F, 0.2F));
		}
	}
}
