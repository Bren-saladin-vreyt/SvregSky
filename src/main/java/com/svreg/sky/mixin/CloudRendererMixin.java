package com.svreg.sky.mixin;

import com.svreg.sky.clouds.CloudLayers;
import com.svreg.sky.sky.CloudVoxels;
import com.svreg.sky.sky.SkySettings;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.Optional;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CloudRenderer.class)
public abstract class CloudRendererMixin {
	/** the vanilla instance renders our layers instead of its own single layer */
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void svregsky$renderLayers(int color, CloudStatus cloudStatus, float bottomY, int range, Vec3 cameraPosition, long gameTime, float partialTicks, CallbackInfo ci) {
		if (CloudLayers.isRendering() || CloudLayers.isOurs((CloudRenderer) (Object) this)) {
			return;
		}
		com.svreg.sky.wind.Wind.setCamera(cameraPosition);
		com.svreg.sky.wind.WindLines.render(cameraPosition);
		com.svreg.sky.fx.Effects.render(cameraPosition);
		com.svreg.sky.look.PostLook.render();
		if (SkySettings.cloud3d()) {
			CloudVoxels.render(bottomY, cameraPosition); // real 3D Cirrus clouds
			ci.cancel();
			return;
		}
		if (!SkySettings.blockyClouds) {
			ci.cancel(); // the painted Cirrus clouds replace the block clouds
			return;
		}
		CloudLayers.render(color, cloudStatus, bottomY, range, cameraPosition, gameTime, partialTicks);
		ci.cancel();
	}

	/** vanilla clouds reload with every resource reload: read our layer setup from the resource packs at the same time */
	@Inject(method = "apply(Ljava/util/Optional;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At("TAIL"))
	private void svregsky$reload(Optional<CloudRenderer.TextureData> preparations, ResourceManager manager, ProfilerFiller profiler, CallbackInfo ci) {
		if (!CloudLayers.isOurs((CloudRenderer) (Object) this)) {
			SkySettings.load(manager);
			CloudVoxels.reload(manager);
			CloudLayers.reload(manager);
		}
	}

	@Inject(method = "endFrame", at = @At("TAIL"))
	private void svregsky$endFrame(CallbackInfo ci) {
		if (!CloudLayers.isOurs((CloudRenderer) (Object) this)) {
			CloudLayers.endFrame();
		}
	}

	@Inject(method = "markForRebuild", at = @At("TAIL"))
	private void svregsky$markForRebuild(CallbackInfo ci) {
		if (!CloudLayers.isOurs((CloudRenderer) (Object) this)) {
			CloudLayers.markForRebuild();
		}
	}

	@Inject(method = "close", at = @At("TAIL"))
	private void svregsky$close(CallbackInfo ci) {
		if (!CloudLayers.isOurs((CloudRenderer) (Object) this)) {
			CloudLayers.close();
			CloudVoxels.close();
		}
	}
}
