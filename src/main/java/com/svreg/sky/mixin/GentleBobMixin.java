package com.svreg.sky.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.svreg.sky.sky.SkySettings;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GentleBobMixin {
	/** a softer, smaller walking bob for the view and the item in hand */
	@Inject(method = "bobView", at = @At("HEAD"), cancellable = true, require = 0)
	private void svregsky$gentleBob(CameraRenderState cameraState, PoseStack poseStack, CallbackInfo ci) {
		if (!SkySettings.on("gentle_bob") || !cameraState.entityRenderState.isPlayer) {
			return;
		}
		float w = cameraState.entityRenderState.backwardsInterpolatedWalkDistance;
		float bob = cameraState.entityRenderState.bob * 0.55F;
		float s = Mth.sin(w * (float) Math.PI);
		float c = Mth.cos(w * (float) Math.PI);
		poseStack.translate(s * bob * 0.4F, -Math.abs(c * bob) * 0.7F, 0.0F);
		poseStack.mulPose(Axis.ZP.rotationDegrees(s * bob * 1.6F));
		poseStack.mulPose(Axis.XP.rotationDegrees(Math.abs(Mth.cos(w * (float) Math.PI - 0.2F) * bob) * 2.5F));
		ci.cancel();
	}
}
