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
	private final int waterBefore = SkySettings.waterColor;

	public SkyScreen(Screen lastScreen, Options options) {
		super(lastScreen, options, Component.translatable("svregsky.options.title"));
	}

	private static OptionInstance<Boolean> bool(String key, boolean value, Consumer<Boolean> set) {
		return new OptionInstance<>("svregsky.options." + key, OptionInstance.noTooltip(), OptionInstance.BOOLEAN_TO_STRING,
				OptionInstance.BOOLEAN_VALUES, value, set);
	}

	private static OptionInstance<Integer> slider(String key, int min, int max, int value, IntFunction<String> label, Consumer<Integer> set) {
		return new OptionInstance<>("svregsky.options." + key, OptionInstance.noTooltip(),
				(caption, v) -> Options.genericValueLabel(caption, Component.literal(label.apply(v))),
				new OptionInstance.IntRange(min, max, true), Math.max(min, Math.min(max, value)), set);
	}

	private static OptionInstance<Integer> percent(String key, int max, float value, Consumer<Float> set) {
		return slider(key, 0, max, Math.round(value * 100), v -> v == 0 ? "OFF" : v + "%", v -> set.accept(v / 100F));
	}

	private static OptionInstance<String> choice(String key, List<String> values, String value, Consumer<String> set) {
		return new OptionInstance<>("svregsky.options." + key, OptionInstance.noTooltip(),
				(caption, v) -> Options.genericValueLabel(caption, Component.translatable("svregsky.options." + key + "." + v)),
				new OptionInstance.Enum<>(values, Codec.STRING), values.contains(value) ? value : values.get(0), set);
	}

	@Override
	protected void addOptions() {
		if (this.list == null) {
			return;
		}
		String mode = !SkySettings.paintedClouds && !SkySettings.cloud3d() ? "off" : SkySettings.cloud3d() ? "3d" : "painted";

		this.list.addHeader(Component.translatable("svregsky.options.header.sky"));
		this.list.addSmall(
				bool("cirrus_sky", SkySettings.cirrusSky, v -> SkySettings.cirrusSky = v),
				choice("sunset_style", List.of("auto", "orange", "magenta"), SkySettings.sunsetStyle, v -> SkySettings.sunsetStyle = v),
				bool("sun_rays", SkySettings.sunRays, v -> SkySettings.sunRays = v),
				percent("fog_tint", 100, SkySettings.fogTint, v -> SkySettings.fogTint = v));

		this.list.addHeader(Component.translatable("svregsky.options.header.clouds"));
		this.list.addSmall(
				choice("cloud_mode", List.of("painted", "3d", "off"), mode, v -> {
					SkySettings.cloudMode = v.equals("3d") ? "3d" : "painted";
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

		this.list.addHeader(Component.translatable("svregsky.options.header.night"));
		this.list.addSmall(
				bool("stars", SkySettings.stars, v -> SkySettings.stars = v),
				bool("shooting_stars", SkySettings.shootingStars, v -> SkySettings.shootingStars = v),
				percent("milky_way", 200, SkySettings.milkyWay, v -> SkySettings.milkyWay = v),
				percent("aurora", 200, SkySettings.aurora, v -> SkySettings.aurora = v));

		this.list.addHeader(Component.translatable("svregsky.options.header.nature"));
		this.list.addSmall(
				percent("wind_strength", 400, SkySettings.windStrength, v -> SkySettings.windStrength = v),
				percent("wind_speed", 300, SkySettings.windSpeed, v -> SkySettings.windSpeed = v),
				bool("wind_lines", SkySettings.windLines, v -> SkySettings.windLines = v),
				percent("wind_line_amount", 300, SkySettings.windLineAmount, v -> SkySettings.windLineAmount = v),
				percent("far_grass_tint", 100, SkySettings.farGrassTint, v -> SkySettings.farGrassTint = v),
				bool("turquoise_water", SkySettings.waterColor >= 0, v -> SkySettings.waterColor = v ? 0x4FD6E3 : -1));
	}

	@Override
	public void removed() {
		super.removed();
		SkyConfig.save();
		if (SkySettings.waterColor != waterBefore && this.minecraft != null && this.minecraft.levelRenderer != null) {
			this.minecraft.levelRenderer.allChanged(); // re-mesh chunks so the water colour updates
		}
	}
}
