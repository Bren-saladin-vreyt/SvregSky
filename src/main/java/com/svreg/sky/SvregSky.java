package com.svreg.sky;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SvregSky implements ClientModInitializer {
	public static final String MOD_ID = "svregsky";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		LOG.info("SvregSky loaded");
		com.svreg.sky.config.Keys.register();
		// built-in resource pack (on by default, can be turned off in Resource Packs): soft rain instead of the harsh vanilla rain
		net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(MOD_ID).ifPresent(mod ->
				net.fabricmc.fabric.api.resource.v1.ResourceLoader.registerBuiltinPack(
						net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, "soft_rain"), mod,
						net.minecraft.network.chat.Component.literal("SvregSky: Hujan Lembut"),
						net.fabricmc.fabric.api.resource.v1.pack.PackActivationType.DEFAULT_ENABLED));
		// anime cel-shading for mobs, players and items (a shader include override), switched from the SvregSky menu
		net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(MOD_ID).ifPresent(mod ->
				net.fabricmc.fabric.api.resource.v1.ResourceLoader.registerBuiltinPack(
						net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, "cel_shading"), mod,
						net.minecraft.network.chat.Component.literal("SvregSky: Cel-Shading Anime"),
						net.fabricmc.fabric.api.resource.v1.pack.PackActivationType.DEFAULT_ENABLED));
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(com.svreg.sky.sound.Ambience::tick);
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
			com.svreg.sky.sky.AutoFps.tick();
			com.svreg.sky.sky.Seasons.update();
			com.svreg.sky.sky.SkySettings.tickPhoto();
			com.svreg.sky.config.Keys.tick(client);
		});
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(com.svreg.sky.config.SkyConfig::onJoin));
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(com.svreg.sky.config.SkyConfig::onLeave));
	}
}
