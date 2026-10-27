package com.svreg.sky.mixin.sodium;

import com.llamalad7.mixinextras.sugar.Local;
import com.svreg.sky.wind.Wind;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** marks foliage vertices with a sway weight (in the vertex colour's alpha) while Sodium meshes a chunk */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer", remap = false)
public abstract class BlockRendererMixin {
	@Unique
	private int svregsky$kind;
	@Unique
	private boolean svregsky$upper;
	@Unique
	private float svregsky$originY;

	@Inject(method = "renderModel", at = @At("HEAD"), require = 0)
	private void svregsky$begin(@Local(argsOnly = true) BlockState state, @Local(argsOnly = true, ordinal = 1) BlockPos origin, CallbackInfo ci) {
		this.svregsky$kind = Wind.kind(state);
		this.svregsky$upper = this.svregsky$kind == 1 && Wind.upperHalf(state);
		this.svregsky$originY = origin.getY();
	}

	@Inject(method = "bufferQuad", at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/builder/ChunkMeshBufferBuilder;push([Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;I)V"), require = 0)
	private void svregsky$weights(CallbackInfo ci, @Local ChunkVertexEncoder.Vertex[] vertices) {
		int kind = this.svregsky$kind;
		if (kind == 0) {
			return;
		}
		for (ChunkVertexEncoder.Vertex v : vertices) {
			if ((v.color >>> 24) != 0xFF) {
				continue;
			}
			int a = Wind.alpha(kind, this.svregsky$upper, v.y - this.svregsky$originY);
			v.color = (v.color & 0x00FFFFFF) | (a << 24);
		}
	}
}
