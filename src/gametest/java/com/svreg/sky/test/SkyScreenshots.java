package com.svreg.sky.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.minecraft.client.CloudStatus;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/** Opens a world and takes screenshots of the sky at different times, so the clouds can be checked from CI. */
public class SkyScreenshots implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			sp.getServer().runCommand("weather clear");
			// the test framework turns clouds off for deterministic screenshots; we want to see them
			context.runOnClient(client -> client.options.cloudStatus().set(CloudStatus.FANCY));
			sp.getConnection().waitForChunksRender();

			shot(context, sp, 6000, 0, -25, "a_noon_south");
			shot(context, sp, 6000, 135, -55, "b_noon_up");
			shot(context, sp, 6000, 90, -6, "c_noon_horizon");
			shot(context, sp, 12100, 90, -12, "d_golden_west");
			shot(context, sp, 12850, 90, -10, "e_sunset_west");
			shot(context, sp, 12850, -90, -12, "f_sunset_east");
			shot(context, sp, 23150, -90, -12, "g_sunrise_east");
			shot(context, sp, 18000, 180, -25, "h_night_north");
			shot(context, sp, 18000, 30, -70, "i_night_up");
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
