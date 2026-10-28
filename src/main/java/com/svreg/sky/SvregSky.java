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
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(com.svreg.sky.sound.Ambience::tick);
	}
}
