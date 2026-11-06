package com.svreg.sky.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.svreg.sky.SvregSky;
import com.svreg.sky.sky.SkySettings;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Lets the SvregSky menu speak its own language (Indonesian by default), whatever language the game is set to:
 * a thin layer over the game's language that answers SvregSky's own keys from the chosen language file and passes
 * everything else through. "auto" leaves the game's language alone.
 */
public final class ModLanguage extends Language {
	private final Language base;
	private final Map<String, String> mine;
	private final String code;

	private ModLanguage(Language base, String code, Map<String, String> mine) {
		this.base = base;
		this.code = code;
		this.mine = mine;
	}

	private static Map<String, String> load(String code) {
		Map<String, String> m = new HashMap<>();
		try (InputStream in = ModLanguage.class.getResourceAsStream("/assets/svregsky/lang/" + code + ".json")) {
			if (in != null) {
				JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
				o.entrySet().forEach(e -> m.put(e.getKey(), e.getValue().getAsString()));
			}
		} catch (Exception e) {
			SvregSky.LOG.warn("SvregSky: could not read the {} language file", code, e);
		}
		return m;
	}

	/** called every client tick: keeps the layer in place (the game replaces the language on every resource reload) */
	public static void tick(Minecraft mc) {
		apply();
	}

	public static void apply() {
		String want = SkySettings.modLanguage;
		Language now = Language.getInstance();
		Language base = now instanceof ModLanguage ml ? ml.base : now;
		if (want == null || want.equals("auto")) {
			if (now instanceof ModLanguage) {
				Language.inject(base);
			}
			return;
		}
		if (now instanceof ModLanguage ml && ml.code.equals(want)) {
			return;
		}
		Language.inject(new ModLanguage(base, want, load(want)));
	}

	@Override
	public String getOrDefault(String key, String fallback) {
		String v = mine.get(key);
		return v != null ? v : base.getOrDefault(key, fallback);
	}

	@Override
	public boolean has(String key) {
		return mine.containsKey(key) || base.has(key);
	}

	@Override
	public boolean isDefaultRightToLeft() {
		return base.isDefaultRightToLeft();
	}

	@Override
	public FormattedCharSequence getVisualOrder(FormattedText text) {
		return base.getVisualOrder(text);
	}
}
