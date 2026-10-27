package com.svreg.sky.mixin;

import com.svreg.sky.sky.SkySettings;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Biome.class)
public abstract class BiomeWaterMixin {
	/** one Ghibli turquoise for all water ("water_color" in sky.json, empty = vanilla biome colours) */
	@Inject(method = "getWaterColor", at = @At("RETURN"), cancellable = true)
	private void svregsky$waterColor(CallbackInfoReturnable<Integer> cir) {
		int c = SkySettings.waterColor;
		if (c >= 0) {
			cir.setReturnValue((cir.getReturnValueI() & 0xFF000000) | (c & 0xFFFFFF));
		}
	}
}
