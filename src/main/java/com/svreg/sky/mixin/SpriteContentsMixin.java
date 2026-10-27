package com.svreg.sky.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.platform.NativeImage;
import com.svreg.sky.items.ToonItems;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpriteContents.class)
public abstract class SpriteContentsMixin {
	/** cartoon item textures: edits the loaded image in place before it is uploaded to the atlas */
	@Inject(method = "<init>", at = @At("TAIL"))
	private void svregsky$toon(CallbackInfo ci, @Local(argsOnly = true) Identifier name, @Local(argsOnly = true) NativeImage image) {
		ToonItems.process(name, image);
	}
}
