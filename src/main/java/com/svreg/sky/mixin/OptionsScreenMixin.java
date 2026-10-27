package com.svreg.sky.mixin;

import com.svreg.sky.config.SkyScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin extends Screen {
	protected OptionsScreenMixin(Component title) {
		super(title);
	}

	/** a "SvregSky" button in the top-left corner of the Options screen */
	@Inject(method = "init", at = @At("TAIL"))
	private void svregsky$button(CallbackInfo ci) {
		Screen self = this;
		this.addRenderableWidget(Button.builder(Component.translatable("svregsky.options.button"),
				b -> this.minecraft.setScreen(new SkyScreen(self, this.minecraft.options))).bounds(6, 6, 98, 20).build());
	}
}
