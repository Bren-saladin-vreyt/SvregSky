package com.svreg.sky.mixin;

import com.svreg.sky.sky.CirrusSky;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AtmosphericFogEnvironment.class)
public abstract class FogColorMixin {
	/** distance fog takes the Cirrus horizon colour, so terrain fades into the sky without a seam */
	@Inject(method = "getBaseColor", at = @At("TAIL"), cancellable = true)
	private void svregsky$fogColor(ClientLevel level, Camera camera, int renderDistance, float partialTicks, CallbackInfoReturnable<Integer> cir) {
		cir.setReturnValue(CirrusSky.tintFog(cir.getReturnValueI(), camera.forwardVector()));
	}
}
