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
			context.runOnClient(client -> {
				com.svreg.sky.sky.SkySettings.windLineAmount = 3.0F;
			});
			context.waitTicks(40);
			shot(context, sp, 6000, 0, 10, "j_meadow_wind_a");
			context.waitTicks(17);
			context.takeScreenshot("k_meadow_wind_b");
			context.runOnClient(client -> client.gui.setScreen(new com.svreg.sky.config.SkyScreen(null, client.options)));
			context.waitTicks(10);
			context.takeScreenshot("k2_settings_screen");
			context.runOnClient(client -> client.gui.setScreen(null));
			context.waitTicks(5);
			shot(context, sp, 6000, 0, -20, "l_3d_noon");
			shot(context, sp, 6000, 0, -60, "m_3d_up");
			shot(context, sp, 12100, 90, -12, "n_3d_golden");
			shot(context, sp, 12850, 90, -10, "o_3d_sunset");
			shot(context, sp, 6000, -30, 35, "o2_water");

			// pure 3D sunset clouds
			context.runOnClient(client -> {
				com.svreg.sky.sky.SkySettings.cloudMode = "3d_full";
				com.svreg.sky.sky.SkySettings.version++;
			});
			shot(context, sp, 12100, 90, -14, "p_full3d_golden");
			shot(context, sp, 12450, 90, -14, "p2_full3d_amber");
			shot(context, sp, 6000, 40, -35, "p3_full3d_noon");
			shot(context, sp, 12850, 90, -10, "p4_full3d_sunset");

			// painted clouds again: light shafts, rainbow, sparkles, fireflies, falling leaves
			context.runOnClient(client -> {
				com.svreg.sky.sky.SkySettings.cloudMode = "painted";
				com.svreg.sky.sky.SkySettings.version++;
			});
			shot(context, sp, 12100, 90, -14, "q_light_shafts");
			context.runOnClient(client -> com.svreg.sky.sky.CirrusSky.forceRainbow());
			shot(context, sp, 1000, 90, -12, "r_rainbow");
			shot(context, sp, 12300, 90, 25, "s_water_sparkles");
			shot(context, sp, 18000, 0, 8, "t_fireflies");
			context.waitTicks(60);
			context.takeScreenshot("t2_fireflies_later");
			shot(context, sp, 6000, 0, -8, "u_falling_leaves");

			// painted mountains, colour grading and morning mist
			shot(context, sp, 6000, 120, -3, "w_mountains_noon");
			shot(context, sp, 12300, 90, -3, "w2_mountains_golden");
			shot(context, sp, 23300, -60, 5, "x_morning_mist");
			context.runOnClient(client -> com.svreg.sky.sky.SkySettings.colorGrading = 0F);
			shot(context, sp, 6000, 0, 10, "y_grading_off");
			context.runOnClient(client -> com.svreg.sky.sky.SkySettings.colorGrading = 1F);
			shot(context, sp, 6000, 0, 10, "y2_grading_on");

			// new batch: Ghibli sun & moon, smoke, butterflies, window glow, look pass, photo mode
			shot(context, sp, 2500, -90, -35, "z_sun_morning");
			shot(context, sp, 18000, 0, -80, "z2_moon_night");
			sp.getServer().runCommand("execute as @p at @p run setblock ~1 ~ ~5 campfire[lit=true]");
			sp.getServer().runCommand("execute as @p at @p run fill ~-12 ~ ~8 ~-8 ~3 ~12 cobblestone hollow");
			sp.getServer().runCommand("execute as @p at @p run fill ~-8 ~1 ~9 ~-8 ~2 ~11 glass");
			sp.getServer().runCommand("execute as @p at @p run setblock ~-10 ~1 ~10 glowstone");
			context.waitTicks(80);
			shot(context, sp, 6000, 0, 5, "z3_smoke_noon");
			shot(context, sp, 6000, -35, 15, "z4_butterflies");
			shot(context, sp, 18000, 45, 5, "z5_window_glow_night");
			context.runOnClient(client -> com.svreg.sky.sky.SkySettings.FLAGS.put("cartoon_outline", true));
			shot(context, sp, 6000, 0, 10, "z6_outline_on");
			context.runOnClient(client -> com.svreg.sky.sky.SkySettings.FLAGS.put("cartoon_outline", false));
			shot(context, sp, 12300, 90, 0, "z7_film_tone_golden");
			context.runOnClient(client -> {
				com.svreg.sky.sky.SkySettings.photoMode = true;
				com.svreg.sky.sky.SkySettings.photoTime = 12700;
			});
			shot(context, sp, 6000, 90, -10, "z8_photo_mode_sunset");
			context.runOnClient(client -> com.svreg.sky.sky.SkySettings.photoMode = false);
			sp.getServer().runCommand("weather thunder");
			context.waitTicks(100);
			shot(context, sp, 6000, 0, -20, "z9_storm");
			context.waitTicks(15);
			context.takeScreenshot("z9b_storm_later");
			sp.getServer().runCommand("weather clear");

			// batch 3: towers, seasons, puddles, glow, paper, birds, reflection path, depth of field
			context.runOnClient(client -> {
				com.svreg.sky.sky.SkySettings.FLAGS.put("auto_fps", false);
				com.svreg.sky.sky.SkySettings.FLAGS.put("bloom", true);
				com.svreg.sky.sky.SkySettings.FLAGS.put("paper_texture", true);
			});
			sp.getServer().runCommand("time add 24000"); // a different day (towers are on most summer days)
			shot(context, sp, 9000, 120, -4, "za_towers_afternoon");
			shot(context, sp, 9000, -60, -4, "za2_towers_other_side");
			shot(context, sp, 12300, 90, 8, "zb_sun_path_on_water");
			shot(context, sp, 6000, 0, 10, "zc_bloom_paper");
			context.runOnClient(client -> {
				com.svreg.sky.sky.SkySettings.FLAGS.put("bloom", false);
				com.svreg.sky.sky.SkySettings.FLAGS.put("paper_texture", false);
			});
			sp.getServer().runCommand("time add 384000"); // 16 days later: autumn
			shot(context, sp, 6000, 0, -5, "zd_autumn");
			sp.getServer().runCommand("time add 192000"); // winter
			shot(context, sp, 6000, 0, -5, "ze_winter");
			sp.getServer().runCommand("time add 192000"); // spring
			shot(context, sp, 6000, 0, -5, "zf_spring");
			sp.getServer().runCommand("weather rain");
			context.waitTicks(200);
			sp.getServer().runCommand("weather clear");
			context.waitTicks(60);
			shot(context, sp, 6000, 0, 30, "zg_wet_ground_puddles");
			context.runOnClient(client -> {
				com.svreg.sky.sky.SkySettings.photoMode = true;
				com.svreg.sky.sky.SkySettings.photoTime = 6000;
			});
			shot(context, sp, 6000, 0, 10, "zh_photo_dof");
			context.runOnClient(client -> com.svreg.sky.sky.SkySettings.photoMode = false);

			// cloud shadows seen from above the meadow
			sp.getServer().runCommand("gamemode spectator @a");
			sp.getServer().runCommand("execute as @p at @p run tp @p ~ ~40 ~-10");
			context.waitTicks(40);
			shot(context, sp, 6000, 0, 55, "v_cloud_shadows");
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
