package com.svreg.sky.mixin;

import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CloudRenderer.class)
public interface CloudRendererAccessor {
	@Accessor("texture")
	void svregsky$setTexture(CloudRenderer.TextureData texture);

	@Accessor("needsRebuild")
	void svregsky$setNeedsRebuild(boolean needsRebuild);
}
