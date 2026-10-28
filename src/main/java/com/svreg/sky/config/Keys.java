package com.svreg.sky.config;

import com.mojang.blaze3d.platform.InputConstants;
import com.svreg.sky.sky.SkySettings;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Hotkeys: open the SvregSky menu, toggle photo mode, step the photo time-lapse. Rebind them in Controls. */
public final class Keys {
	private static KeyMapping menu, photo, timelapse;

	private Keys() {
	}

	public static void register() {
		menu = KeyBindingHelper.registerKeyBinding(new KeyMapping("svregsky.key.menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, KeyMapping.Category.MISC));
		photo = KeyBindingHelper.registerKeyBinding(new KeyMapping("svregsky.key.photo", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, KeyMapping.Category.MISC));
		timelapse = KeyBindingHelper.registerKeyBinding(new KeyMapping("svregsky.key.timelapse", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, KeyMapping.Category.MISC));
	}

	public static void tick(Minecraft mc) {
		if (menu == null) {
			return;
		}
		while (menu.consumeClick()) {
			mc.gui.setScreen(new SkyScreen(null, mc.options));
		}
		while (photo.consumeClick()) {
			SkySettings.photoMode = !SkySettings.photoMode;
			if (mc.player != null) {
				mc.player.displayClientMessage(Component.translatable(SkySettings.photoMode ? "svregsky.photo.on" : "svregsky.photo.off"), true);
			}
		}
		while (timelapse.consumeClick()) {
			SkySettings.photoTimelapse = switch (SkySettings.photoTimelapse) {
				case "off" -> "slow";
				case "slow" -> "fast";
				default -> "off";
			};
			SkySettings.photoMode = SkySettings.photoMode || !"off".equals(SkySettings.photoTimelapse);
			if (mc.player != null) {
				mc.player.displayClientMessage(Component.translatable("svregsky.options.photo_timelapse." + SkySettings.photoTimelapse), true);
			}
		}
	}
}
