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

	private static net.minecraft.client.gui.components.AbstractWidget flag(String key) {
		return bool(key, SkySettings.chosen(key), v -> SkySettings.FLAGS.put(key, v));
	}

	private static String clock(int ticks) {
		int mins = ((ticks + 6000) % 24000) * 60 / 1000;
		return String.format("%02d:%02d", mins / 60, mins % 60);
	}

	/** the option's longer explanation (lang key "<option>.tooltip"), shown when the button is hovered or tapped */
	private static <T> OptionInstance.TooltipSupplier<T> tip(String key) {
		String k = "svregsky.options." + key + ".tooltip";
		return net.minecraft.locale.Language.getInstance().has(k) ? OptionInstance.cachedConstantTooltip(Component.translatable(k)) : OptionInstance.noTooltip();
	}

	/** "ON  name" / "OFF  name": the state comes first, so it can be read at once even when a long name has to scroll */
	private static Component boolText(String key, boolean v) {
		return Component.empty()
				.append(Component.literal(v ? "ON " : "OFF ").withStyle(v ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.RED, net.minecraft.ChatFormatting.BOLD))
				.append(Component.translatable("svregsky.options." + key));
	}

	private static net.minecraft.client.gui.components.AbstractWidget bool(String key, boolean value, Consumer<Boolean> set) {
		boolean[] state = {value};
		net.minecraft.client.gui.components.Button b = net.minecraft.client.gui.components.Button.builder(boolText(key, value), btn -> {
			state[0] = !state[0];
			set.accept(state[0]);
			btn.setMessage(boolText(key, state[0]));
		}).width(150).build();
		String k = "svregsky.options." + key + ".tooltip";
		if (net.minecraft.locale.Language.getInstance().has(k)) {
			b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable(k)));
		}
		return b;
	}

	/** adds a row group: switches are our own buttons, sliders and choices come from their OptionInstance */
	private void small(Object... items) {
		List<net.minecraft.client.gui.components.AbstractWidget> ws = new ArrayList<>();
		for (Object o : items) {
			if (o instanceof OptionInstance<?> oi) {
				ws.add(oi.createButton(this.options, 0, 0, 150));
			} else {
				ws.add((net.minecraft.client.gui.components.AbstractWidget) o);
			}
		}
		this.list.addSmall(ws);
	}

	private static OptionInstance<Integer> slider(String key, int min, int max, int value, IntFunction<String> label, OptionInstance.ValueUpdateListener<Integer> set) {
		return new OptionInstance<>("svregsky.options." + key, tip(key),
				(caption, v) -> Component.empty().append(Component.literal(label.apply(v) + " ").withStyle(net.minecraft.ChatFormatting.YELLOW, net.minecraft.ChatFormatting.BOLD)).append(caption),
				new OptionInstance.IntRange(min, max, true), Math.max(min, Math.min(max, value)), set);
	}

	private static OptionInstance<Integer> percent(String key, int max, float value, Consumer<Float> set) {
		return slider(key, 0, max, Math.round(value * 100), v -> v == 0 ? "OFF" : v + "%", v -> set.accept(v / 100F));
	}

	private static OptionInstance<String> choice(String key, List<String> values, String value, OptionInstance.ValueUpdateListener<String> set) {
		return new OptionInstance<>("svregsky.options." + key, tip(key),
				(caption, v) -> Component.translatable("svregsky.options." + key + "." + v), // the cycle button adds the caption itself
				new OptionInstance.Enum<>(values, Codec.STRING), values.contains(value) ? value : values.get(0), set);
	}

	/** turns the built-in cel-shading pack on or off to match the menu, reloading resources when it changed */
	public static void syncCelShading() {
		try {
			net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
			var repo = mc.getResourcePackRepository();
			String id = null;
			for (String s : repo.getAvailableIds()) {
				if (s.contains("cel_shading")) {
					id = s;
				}
			}
			if (id == null) {
				return;
			}
			boolean want = SkySettings.on("cel_shading"), has = repo.getSelectedIds().contains(id);
			if (want != has) {
				java.util.List<String> sel = new java.util.ArrayList<>(repo.getSelectedIds());
				if (want) sel.add(id); else sel.remove(id);
				repo.setSelected(sel);
				mc.options.updateResourcePacks(repo);
			}
		} catch (Throwable t) {
			com.svreg.sky.SvregSky.LOG.warn("SvregSky: could not switch the cel-shading pack", t);
		}
	}

	@Override
	protected void addOptions() {
		if (this.list == null) {
			return;
		}

		this.list.addHeader(Component.translatable("svregsky.options.header.presets"));
		small(
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
		small(
				bool("cirrus_sky", SkySettings.cirrusSky, v -> SkySettings.cirrusSky = v),
				choice("sunset_style", List.of("auto", "orange", "magenta"), SkySettings.sunsetStyle, v -> SkySettings.sunsetStyle = v),
				bool("sun_rays", SkySettings.sunRays, v -> SkySettings.sunRays = v),
				bool("horizon_mountains", SkySettings.horizonMountains, v -> SkySettings.horizonMountains = v),
				flag("hill_wind"),
				flag("horizon_train"),
				flag("horizon_village"),
				flag("horizon_birds"),
				flag("airships"),
				flag("waterfall_far"),
				flag("horizon_lake"),
				flag("far_shower"),
				flag("horizon_pines"),
				flag("lone_tree"),
				flag("power_poles"),
				flag("hill_meadow"),
				flag("hill_torii"),
				flag("big_moon"),
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
				flag("distant_storm"),
				flag("sun_beams"),
				flag("cloud_rows"),
				flag("cirrus_layer"),
				flag("light_rays"),
				flag("valley_fog"),
				flag("cloud_sea"),
				flag("blue_hour"),
				flag("silver_clouds"),
				flag("sky_lanterns"));

		this.list.addHeader(Component.translatable("svregsky.options.header.clouds"));
		small(
				choice("cloud_mode", List.of("3d_crumble", "off"), SkySettings.paintedClouds ? "3d_crumble" : "off", v -> {
					SkySettings.cloudMode = "3d_crumble";
					SkySettings.paintedClouds = !v.equals("off");
					SkySettings.version++;
				}),
				choice("raymarch_scale", List.of("2", "3", "4", "6", "8"), String.valueOf(SkySettings.raymarchScale), v -> SkySettings.raymarchScale = Integer.parseInt(v)),
				slider("ghibli_amount", 25, 250, Math.round(SkySettings.ghibliAmount * 100), v -> v + "%", v -> SkySettings.ghibliAmount = v / 100F),
				percent("cloud_speed", 300, SkySettings.cloudSpeed, v -> SkySettings.cloudSpeed = v),
				slider("cloud_3d_distance", 8, 96, SkySettings.cloud3dDistance, v -> String.valueOf(v), v -> {
					SkySettings.cloud3dDistance = v;
					SkySettings.version++;
				}),
				flag("cloud_steady"),
				flag("living_clouds"),
				flag("cloud_weather"),
				flag("horizon_bank"),
				slider("aerial_fade", 0, 200, Math.round(SkySettings.aerialFade * 100), v -> v + "%", v -> SkySettings.aerialFade = v / 100F));

		small(
				bool("cloud_god_rays", SkySettings.cloudGodRays, v -> SkySettings.cloudGodRays = v),
				percent("cloud_shadows", 100, SkySettings.cloudShadows, v -> SkySettings.cloudShadows = v),
				bool("rainbow", SkySettings.rainbow, v -> SkySettings.rainbow = v));

		this.list.addHeader(Component.translatable("svregsky.options.header.night"));
		small(
				bool("stars", SkySettings.stars, v -> SkySettings.stars = v),
				bool("shooting_stars", SkySettings.shootingStars, v -> SkySettings.shootingStars = v),
				bool("fireflies", SkySettings.fireflies, v -> SkySettings.fireflies = v),
				percent("milky_way", 200, SkySettings.milkyWay, v -> SkySettings.milkyWay = v),
				percent("aurora", 200, SkySettings.aurora, v -> SkySettings.aurora = v));

		this.list.addHeader(Component.translatable("svregsky.options.header.nature"));
		small(
				percent("wind_strength", 400, SkySettings.windStrength, v -> SkySettings.windStrength = v),
				percent("wind_speed", 300, SkySettings.windSpeed, v -> SkySettings.windSpeed = v),
				flag("living_wind"),
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
				flag("rain_drops"),
				flag("cel_shading"),
				flag("snow_cover"),
				flag("waterfall_foam"),
				flag("underwater_light"),
				flag("shore_foam"),
				flag("grass_bend"),
				flag("grass_kick"),
				flag("walk_ripples"),
				flag("startle_birds"),
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
		small(
				bool("ambient_sounds", SkySettings.ambientSounds, v -> SkySettings.ambientSounds = v),
				percent("ambient_volume", 100, SkySettings.ambientVolume, v -> SkySettings.ambientVolume = v),
				flag("weather_sounds"),
				flag("place_sounds"),
				flag("ghibli_music"),
				flag("crickets"),
				flag("leaf_rustle"));

		this.list.addHeader(Component.translatable("svregsky.options.header.look"));
		small(
				flag("film_tone"),
				flag("anime_twos"),
				flag("colored_shadows"),
				slider("anime_shadow", 10, 100, Math.round(SkySettings.animeShadow * 100), v -> v + "%", v -> SkySettings.animeShadow = v / 100F),
				flag("anime_outline"),
				slider("outline_width", 1, 3, SkySettings.outlineWidth, v -> v + " px", v -> SkySettings.outlineWidth = v),
				slider("outline_strength", 10, 100, Math.round(SkySettings.outlineStrength * 100), v -> v + "%", v -> SkySettings.outlineStrength = v / 100F),
				choice("outline_color", List.of("auto", "black", "brown", "navy"), SkySettings.outlineColor, v -> SkySettings.outlineColor = v),
				flag("outline_boil"),
				flag("dream_glow"),
				slider("dream_glow_strength", 10, 100, Math.round(SkySettings.dreamGlow * 100), v -> v + "%", v -> SkySettings.dreamGlow = v / 100F),
				flag("layered_haze"),
				slider("layered_haze_strength", 10, 100, Math.round(SkySettings.layeredHaze * 100), v -> v + "%", v -> SkySettings.layeredHaze = v / 100F),
				flag("painted_grass"),
				flag("dream_sleep"),
				flag("window_light"),
				flag("rare_comet"),
				flag("cartoon_outline"),
				flag("cozy_home"),
				flag("warm_lamps"),
				flag("window_glow"),
				flag("gentle_bob"),
				flag("water_reflection"),
				flag("water_sunset"),
				flag("water_sparkle_dots"),
				flag("anime_water"),
				flag("shore_waves"),
				flag("face_light"),
				flag("screen_shadows"),
				choice("shadow_method", List.of("world", "screen", "both"), SkySettings.shadowMethod, v -> SkySettings.shadowMethod = v),
				choice("shadow_quality", List.of("low", "medium", "high", "ultra"), SkySettings.shadowQuality, v -> SkySettings.shadowQuality = v),
				slider("shadow_darkness", 10, 90, Math.round(SkySettings.shadowDarkness * 100), v -> v + "%", v -> SkySettings.shadowDarkness = v / 100F),
				choice("shadow_color", List.of("anime", "neutral", "warm"), SkySettings.shadowColor, v -> SkySettings.shadowColor = v),
				flag("shadow_debug"),
				flag("torch_glow"),
				flag("paper_texture"),
				flag("painterly"),
				slider("painterly_strength", 10, 100, Math.round(SkySettings.painterlyStrength * 100), v -> v + "%", v -> SkySettings.painterlyStrength = v / 100F),
				flag("bloom"));

		this.list.addHeader(Component.translatable("svregsky.options.header.photo"));
		small(
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
		syncCelShading();
	}
}
