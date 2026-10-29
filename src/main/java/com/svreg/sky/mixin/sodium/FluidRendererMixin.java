package com.svreg.sky.mixin.sodium;

import com.llamalad7.mixinextras.sugar.Local;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** marks water vertices (alpha 255 - 28) so the terrain shader knows exactly which faces are water */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer", remap = false)
public abstract class FluidRendererMixin {
	@Unique
	private boolean svregsky$water;
	@Unique
	private static boolean svregsky$logged;

	@Inject(method = "render", at = @At("HEAD"), require = 0)
	private void svregsky$begin(CallbackInfo ci, @Local(argsOnly = true) FluidState fluidState) {
		this.svregsky$water = fluidState.is(FluidTags.WATER);
	}

	@Inject(method = "writeQuad", at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/builder/ChunkMeshBufferBuilder;push([Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;I)V"), require = 0)
	private void svregsky$mark(CallbackInfo ci, @Local ChunkVertexEncoder.Vertex[] vertices) {
		if (!this.svregsky$water) {
			return;
		}
		if (!svregsky$logged) {
			svregsky$logged = true;
			com.svreg.sky.SvregSky.LOG.info("SvregSky: marking water for the sky mirror");
		}
		for (ChunkVertexEncoder.Vertex v : vertices) {
			v.color = (v.color & 0x00FFFFFF) | ((255 - 28) << 24);
		}
	}
}
