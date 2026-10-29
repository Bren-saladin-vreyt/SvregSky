package com.svreg.sky.config;

import com.mojang.serialization.Codec;
import com.svreg.sky.sky.SkySettings;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/** SvregSky settings, opened from the "SvregSky" button in the Options screen. Changes apply live and are saved on close. */
public class SkyScreen extends OptionsSubScreen {
	private final List<Runnable> afterClose = new ArrayList<>();

	public SkyScreen(Screen lastScreen, Options options) {
		super(lastScreen, options, Component.translatable("svregsky.options.title")
				.append(Component.literal("  (" + net.minecraft.client.Minecraft.getInstance().getFps() + " FPS)").withStyle(net.minecraft.ChatFormatting.GRAY)));
	}

	private static OptionInstance<Boolean> flag(String key) {
		return bool(key, SkySettings.chosen(key), v -> SkySettings.FLAGS.put(key, v));
	}

	private static String clock(int ticks) {
		int mins = ((ticks + 6000) % 24000) * 60 / 1000;
		return String.format("%02d:%02d", mins / 60, mins % 60);
	}

	private static OptionInstance<Boolean> bool(String key, boolean value, OptionInstance.ValueUpdateListener<Boolean> set) {
		return new OptionInstance<>("svregsky.options." + key, OptionInstance.noTooltip(), OptionInstance.BOOLEAN_TO_STRING,
				OptionInstance.BOOLEAN_VALUES, value, set);
	}

	private static OptionInstance<Integer> slider(String key, int min, int max, int value, IntFunction<String> label, OptionInstance.ValueUpdateListener<Integer> set) {
		return new OptionInstance<>("svregsky.options." + key, OptionInstance.noTooltip(),
				(caption, v) -> Options.genericValueLabel(caption, Component.literal(label.apply(v))),
				new OptionInstance.IntRange(min, max, true), Math.max(min, Math.min(max, value)), set);
	}

	private static OptionInstance<Integer> percent(String key, int max, float value, Consumer<Float> set) {
		return slider(key, 0, max, Math.round(value * 100), v -> v == 0 ? "OFF" : v + "%", v -> set.accept(v / 100F));
	}

	private static OptionInstance<String> choice(String key, List<String> values, String value, OptionInstance.ValueUpdateListener<String> set) {
		return new OptionInstance<>("svregsky.options." + key, OptionInstance.noTooltip(),
				(caption, v) -> Component.translatable("svregsky.options." + key + "." + v), // the cycle button adds the caption itself
				new OptionInstance.Enum<>(values, Codec.STRING), values.contains(value) ? value : values.get(0), set);
	}

	@Override
	protected void addOptions() {
		if (this.list == null) {
			return;
		}
		String mode = !SkySettings.paintedClouds ? "off" : SkySettings.ghibliMix() ? "3d_ghibli_mix" : SkySettings.cloudGhibli() ? "3d_ghibli" : SkySettings.cloud3dFull() ? "3d_full" : SkySettings.cloud3d() ? "3d" : "painted";

		this.list.addHeader(Component.translatable("svregsky.options.header.presets"));
		this.list.addSmall(
				choice("preset", List.of("custom", "light", "balanced", "full"), Presets.current, v -> {
					Presets.current = v;
					if (!v.equals("custom")) {
						Presets.apply(v);
						SkyConfig.save();
						// reopen the screen (after this click is handled) so every switch shows the preset's values
						net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
						Screen back = this.lastScreen;
						mc.execute(() -> mc.gui.setScreen(new SkyScreen(back, mc.options)));
					}
				}),
				flag("per_world"),
				flag("auto_fps"));

		this.list.addHeader(Component.translatable("svregsky.options.header.sky"));
		this.list.addSmall(
				bool("cirrus_sky", SkySettings.cirrusSky, v -> SkySettings.cirrusSky = v),
				choice("sunset_style", List.of("auto", "orange", "magenta"), SkySettings.sunsetStyle, v -> SkySettings.sunsetStyle = v),
				bool("sun_rays", SkySettings.sunRays, v -> SkySettings.sunRays = v),
				bool("horizon_mountains", SkySettings.horizonMountains, v -> SkySettings.horizonMountains = v),
				percent("color_grading", 100, SkySettings.colorGrading, v -> SkySettings.colorGrading = v),
				percent("morning_mist", 100, SkySettings.morningMist, v -> SkySettings.morningMist = v),
				percent("fog_tint", 100, SkySettings.fogTint, v -> SkySettings.fogTint = v),
				flag("ghibli_sun_moon"),
				flag("daily_variety"),
				flag("lightning_glow"),
				flag("ghibli_snow"),
				flag("seasons"),
				flag("cloud_towers"),
				flag("cloud_mega"),
				flag("tower_lightning"),
				flag("sun_beams"),
				flag("cloud_rows"),
				flag("cirrus_layer"),
				flag("light_rays"),
				flag("valley_fog"));

		this.list.addHeader(Component.translatable("svregsky.options.header.clouds"));
		this.list.addSmall(
				choice("cloud_mode", List.of("painted", "3d_ghibli", "3d_ghibli_mix", "3d", "3d_full", "off"), mode, v -> {
					SkySettings.cloudMode = v.equals("off") ? "painted" : v;
					SkySettings.paintedClouds = !v.equals("off");
					SkySettings.version++;
				}),
				slider("cloud_layers", 1, 3, SkySettings.cloudLayers, v -> String.valueOf(v), v -> SkySettings.cloudLayers = v),
				slider("cloud_coverage", -20, 20, Math.round(SkySettings.cloudCoverage * 100), v -> (v > 0 ? "+" : "") + v, v -> {
					SkySettings.cloudCoverage = v / 100F;
				}),
				percent("cloud_speed", 300, SkySettings.cloudSpeed, v -> SkySettings.cloudSpeed = v),
				slider("cloud_3d_distance", 8, 96, SkySettings.cloud3dDistance, v -> String.valueOf(v), v -> {
					SkySettings.cloud3dDistance = v;
					SkySettings.version++;
				}),
				slider("cloud_3d_cell", 3, 16, SkySettings.cloud3dCell, v -> v + "m", v -> {
					SkySettings.cloud3dCell = v;
					SkySettings.version++;
				}),
				slider("cloud_3d_levels", 1, 6, SkySettings.cloud3dLevels, v -> String.valueOf(v), v -> {
					SkySettings.cloud3dLevels = v;
					SkySettings.version++;
				}));

		this.list.addSmall(
				bool("cloud_god_rays", SkySettings.cloudGodRays, v -> SkySettings.cloudGodRays = v),
				percent("cloud_shadows", 100, SkySettings.cloudShadows, v -> SkySettings.cloudShadows = v),
				bool("rainbow", SkySettings.rainbow, v -> SkySettings.rainbow = v));

		this.list.addHeader(Component.translatable("svregsky.options.header.night"));
		this.list.addSmall(
				bool("stars", SkySettings.stars, v -> SkySettings.stars = v),
				bool("shooting_stars", SkySettings.shootingStars, v -> SkySettings.shootingStars = v),
				bool("fireflies", SkySettings.fireflies, v -> SkySettings.fireflies = v),
				percent("milky_way", 200, SkySettings.milkyWay, v -> SkySettings.milkyWay = v),
				percent("aurora", 200, SkySettings.aurora, v -> SkySettings.aurora = v));

		this.list.addHeader(Component.translatable("svregsky.options.header.nature"));
		this.list.addSmall(
				percent("wind_strength", 400, SkySettings.windStrength, v -> SkySettings.windStrength = v),
				percent("wind_speed", 300, SkySettings.windSpeed, v -> SkySettings.windSpeed = v),
				bool("wind_lines", SkySettings.windLines, v -> SkySettings.windLines = v),
				bool("falling_leaves", SkySettings.fallingLeaves, v -> SkySettings.fallingLeaves = v),
				bool("water_sparkles", SkySettings.waterSparkles, v -> SkySettings.waterSparkles = v),
				percent("wind_line_amount", 300, SkySettings.windLineAmount, v -> SkySettings.windLineAmount = v),
				percent("far_grass_tint", 100, SkySettings.farGrassTint, v -> SkySettings.farGrassTint = v),
				bool("turquoise_water", SkySettings.waterColor >= 0, v -> SkySettings.waterColor = v ? 0x4FD6E3 : -1),
				bool("ghibli_biome_colors", SkySettings.ghibliBiomes, v -> SkySettings.ghibliBiomes = v),
				flag("butterflies"),
				flag("light_motes"),
				flag("rain_ripples"),
				flag("shore_foam"),
				flag("grass_bend"),
				flag("dappled_light"),
				flag("water_steam"),
				flag("smoke_wisps"),
				flag("bird_flocks"),
				flag("dragonflies"),
				flag("dandelion_seeds"),
				flag("fish_jumps"),
				flag("sky_reflection"),
				flag("wet_ground"),
				flag("puddles"));

		this.list.addHeader(Component.translatable("svregsky.options.header.sound"));
		this.list.addSmall(
				bool("ambient_sounds", SkySettings.ambientSounds, v -> SkySettings.ambientSounds = v),
				percent("ambient_volume", 100, SkySettings.ambientVolume, v -> SkySettings.ambientVolume = v),
				flag("weather_sounds"),
				flag("place_sounds"),
				flag("ghibli_music"),
				flag("crickets"),
				flag("leaf_rustle"));

		this.list.addHeader(Component.translatable("svregsky.options.header.look"));
		this.list.addSmall(
				flag("film_tone"),
				flag("cartoon_outline"),
				flag("warm_lamps"),
				flag("window_glow"),
				flag("gentle_bob"),
				flag("water_reflection"),
				flag("water_sunset"),
				flag("water_sparkle_dots"),
				flag("face_light"),
				flag("torch_glow"),
				flag("paper_texture"),
				flag("bloom"));

		this.list.addHeader(Component.translatable("svregsky.options.header.photo"));
		this.list.addSmall(
				bool("photo_mode", SkySettings.photoMode, v -> SkySettings.photoMode = v),
				choice("photo_weather", List.of("clear", "rain", "thunder"), SkySettings.photoWeather, v -> SkySettings.photoWeather = v),
				choice("photo_timelapse", List.of("off", "slow", "fast"), SkySettings.photoTimelapse, v -> SkySettings.photoTimelapse = v),
				flag("photo_dof"),
				new OptionInstance<>("svregsky.options.photo_time", OptionInstance.noTooltip(),
						(caption, v) -> Options.genericValueLabel(caption, Component.literal(clock(v))),
						new OptionInstance.IntRange(0, 239, true), Math.floorMod(SkySettings.photoTime, 24000) / 100, v -> SkySettings.photoTime = v * 100));
	}

	@Override
	public void removed() {
		super.removed();
		SkyConfig.save();
	}
}
