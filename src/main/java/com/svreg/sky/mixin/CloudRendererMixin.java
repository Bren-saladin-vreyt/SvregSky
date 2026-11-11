package com.svreg.sky.mixin;

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
	/** the vanilla block clouds never draw: the raymarched puff clouds (or the sky's painted clouds) take their place */
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void svregsky$renderClouds(int color, CloudStatus cloudStatus, float bottomY, int range, Vec3 cameraPosition, long gameTime, float partialTicks, CallbackInfo ci) {
		com.svreg.sky.wind.Wind.setCamera(cameraPosition);
		com.svreg.sky.wind.WindLines.render(cameraPosition);
		com.svreg.sky.fx.Effects.render(cameraPosition);
		com.svreg.sky.look.PostLook.render();
		if (SkySettings.cloudCrumble() && SkySettings.paintedClouds) { // "painted_clouds" false = the menu's clouds Off
			com.svreg.sky.sky.RaymarchClouds.render(bottomY, cameraPosition); // raymarched Ghibli puff clouds
		}
		ci.cancel();
	}

	/** vanilla clouds reload with every resource reload: read our settings from the resource packs at the same time */
	@Inject(method = "apply(Ljava/util/Optional;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At("TAIL"))
	private void svregsky$reload(Optional<CloudRenderer.TextureData> preparations, ResourceManager manager, ProfilerFiller profiler, CallbackInfo ci) {
		SkySettings.load(manager);
	}
}
