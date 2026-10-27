package com.svreg.sky.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.vertex.PoseStack;
import com.svreg.sky.sky.CirrusSky;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** hooks the body of the vanilla sky pass (the lambda inside LevelRenderer#addSkyPass) */
@Mixin(LevelRenderer.class)
public abstract class SkyPassMixin {
	private static final String PASS = "lambda$addSkyPass$0(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lnet/minecraft/client/renderer/state/level/SkyRenderState;)V";

	@Inject(method = PASS, at = @At("HEAD"))
	private void svregsky$beginSky(GpuBufferSlice skyFog, SkyRenderState state, CallbackInfo ci) {
		CirrusSky.beginFrame(state);
	}

	@WrapWithCondition(method = PASS, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SkyRenderer;renderSkyDisc(I)V"))
	private boolean svregsky$skyDisc(SkyRenderer renderer, int skyColor) {
		return !CirrusSky.drawSky();
	}

	@WrapWithCondition(method = PASS, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SkyRenderer;renderSunriseAndSunset(Lcom/mojang/blaze3d/vertex/PoseStack;FI)V"))
	private boolean svregsky$sunriseSunset(SkyRenderer renderer, PoseStack poseStack, float sunAngle, int color) {
		return !CirrusSky.skyActive();
	}

	@Inject(method = PASS, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SkyRenderer;renderSunMoonAndStars(Lcom/mojang/blaze3d/vertex/PoseStack;FFFLnet/minecraft/world/level/MoonPhase;FF)V", shift = At.Shift.AFTER))
	private void svregsky$clouds(GpuBufferSlice skyFog, SkyRenderState state, CallbackInfo ci) {
		CirrusSky.drawClouds();
	}
}
