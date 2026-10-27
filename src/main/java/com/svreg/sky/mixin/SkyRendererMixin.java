package com.svreg.sky.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.svreg.sky.sky.CirrusSky;
import com.svreg.sky.sky.SkySettings;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {
	/** the Cirrus sky draws its own (soft, twinkling) stars */
	@Inject(method = "renderStars", at = @At("HEAD"), cancellable = true)
	private void svregsky$stars(float starBrightness, PoseStack poseStack, CallbackInfo ci) {
		if (CirrusSky.skyActive() && SkySettings.stars) {
			ci.cancel();
		}
	}
}
