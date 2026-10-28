package com.svreg.sky.mixin;

import com.mojang.blaze3d.shaders.ShaderType;
import com.svreg.sky.wind.Wind;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ShaderManager.class)
public abstract class ShaderManagerMixin {
	/** patches the wind + far grass code into Sodium's terrain vertex shader (left alone if Sodium changes its shader) */
	@Inject(method = "getShader", at = @At("RETURN"), cancellable = true)
	private void svregsky$patch(Identifier id, ShaderType type, CallbackInfoReturnable<String> cir) {
		String src = cir.getReturnValue();
		if (src != null && type == ShaderType.VERTEX && "sodium".equals(id.getNamespace()) && id.getPath().equals("blocks/block_layer_opaque")) {
			String patched = Wind.patchVertexShader(src);
			if (patched != src) {
				com.svreg.sky.SvregSky.LOG.info("SvregSky: wind added to Sodium terrain shader");
			}
			cir.setReturnValue(patched);
		} else if (src != null && type == ShaderType.FRAGMENT && "sodium".equals(id.getNamespace()) && id.getPath().equals("blocks/block_layer_opaque")) {
			String vsh = ((ShaderManager) (Object) this).getShader(id, ShaderType.VERTEX);
			if (vsh != null && vsh.contains("v_svMist")) {
				cir.setReturnValue(Wind.patchFragmentShader(src));
			}
		}
	}
}
