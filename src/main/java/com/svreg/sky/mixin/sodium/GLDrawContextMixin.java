package com.svreg.sky.mixin.sodium;

import com.svreg.sky.wind.Wind;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** feeds the wind uniforms to Sodium's terrain program next to its own per-region uniforms */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gpu.device.context.GLDrawContext", remap = false)
public abstract class GLDrawContextMixin {
	@Inject(method = "updateData", at = @At("TAIL"), require = 0)
	private void svregsky$wind(CallbackInfo ci) {
		Wind.upload();
	}
}
