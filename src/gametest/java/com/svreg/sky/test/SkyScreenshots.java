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
			String[] items = {"diamond_sword", "iron_sword", "diamond_sword", "netherite_sword", "apple", "bow", "golden_carrot", "ender_pearl", "torch"};
			for (int i = 0; i < items.length; i++) {
				sp.getServer().runCommand("item replace entity @a hotbar." + i + " with " + items[i]);
			}

			shot(context, sp, 6000, 0, -25, "a_noon_south");
			shot(context, sp, 6000, 135, -55, "b_noon_up");
			shot(context, sp, 6000, 90, -6, "c_noon_horizon");
			shot(context, sp, 12100, 90, -12, "d_golden_west");
			shot(context, sp, 12450, 90, -12, "d2_amber_west");
			shot(context, sp, 12850, 90, -10, "e_sunset_west");
			shot(context, sp, 12850, -90, -12, "f_sunset_east");
			shot(context, sp, 23150, -90, -12, "g_sunrise_east");
			shot(context, sp, 18000, 180, -25, "h_night_north");
			shot(context, sp, 18000, 30, -70, "i_night_up");

			// scenery for wind / grass / water: a meadow, a tree and a pond in front of the player
			sp.getServer().runCommand("execute as @p at @p run fill ~-14 ~ ~3 ~14 ~ ~40 short_grass replace air");
			sp.getServer().runCommand("execute as @p at @p run fill ~-3 ~ ~6 ~3 ~1 ~9 tall_grass replace short_grass");
			sp.getServer().runCommand("execute as @p at @p run fill ~4 ~ ~5 ~7 ~ ~8 poppy replace short_grass");
			sp.getServer().runCommand("execute as @p at @p run fill ~-6 ~ ~4 ~-1 ~ ~12 air");
			sp.getServer().runCommand("execute as @p at @p run fill ~-6 ~-1 ~4 ~-1 ~-1 ~12 water");
			sp.getServer().runCommand("execute as @p at @p run place feature minecraft:oak ~6 ~ ~14");
			context.runOnClient(client -> {
				com.svreg.sky.sky.SkySettings.windStrength = 3.0F;
				com.svreg.sky.sky.SkySettings.cloudMode = "3d";
				com.svreg.sky.sky.SkySettings.version++;
			});
			context.waitTicks(40);
			shot(context, sp, 6000, 0, 10, "j_meadow_wind_a");
			context.waitTicks(17);
			context.takeScreenshot("k_meadow_wind_b");
			shot(context, sp, 6000, 0, -20, "l_3d_noon");
			shot(context, sp, 6000, 0, -60, "m_3d_up");
			shot(context, sp, 12100, 90, -12, "n_3d_golden");
			shot(context, sp, 12850, 90, -10, "o_3d_sunset");
			shot(context, sp, 6000, -30, 35, "o2_water");
			sp.getServer().runCommand("gamemode spectator @a");
			sp.getServer().runCommand("execute as @p at @p run tp @p ~ 215 ~");
			context.waitTicks(40);
			shot(context, sp, 6000, 90, 30, "p_3d_above_noon");
			shot(context, sp, 12100, 90, 15, "q_3d_above_golden");
			sp.getServer().runCommand("execute as @p at @p run tp @p ~ 170 ~");
			context.waitTicks(30);
			shot(context, sp, 6000, 90, -20, "r_3d_below_near");
			shot(context, sp, 6000, 90, -90, "s_3d_below_straight_up");
			sp.getServer().runCommand("execute as @p at @p run tp @p ~ 120 ~");
			context.waitTicks(30);
			shot(context, sp, 6000, 90, -60, "t_3d_from_120");
			sp.getServer().runCommand("execute as @p at @p run tp @p ~ 60 ~");
			context.waitTicks(30);
			shot(context, sp, 6000, 90, -60, "u_3d_from_60");
			context.runOnClient(client -> com.svreg.sky.sky.CloudVoxels.DEBUG = true);
			shot(context, sp, 6000, 90, -60, "v_3d_debug_red");
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
