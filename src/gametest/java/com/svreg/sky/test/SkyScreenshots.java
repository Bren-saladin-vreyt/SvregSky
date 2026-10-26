package com.svreg.sky.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/** Opens a world and takes screenshots of the sky at different times, so the clouds can be checked from CI. */
public class SkyScreenshots implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			sp.getServer().runCommand("weather clear");
			sp.getConnection().waitForChunksRender();

			shot(context, sp, 6000, 0, -25, "noon_north");
			shot(context, sp, 6000, 135, -55, "noon_up");
			shot(context, sp, 6000, 90, -8, "noon_horizon");
			shot(context, sp, 12000, 90, -15, "sunset_west");
			shot(context, sp, 18000, 0, -30, "night_north");
		}
	}

	private static void shot(ClientGameTestContext context, TestSingleplayerContext sp, int time, float yaw, float pitch, String name) {
		sp.getServer().runCommand("time set " + time);
		context.runOnClient(client -> {
			client.player.setYRot(yaw);
			client.player.setXRot(pitch);
		});
		context.waitTicks(30);
		context.takeScreenshot(name);
	}
}
