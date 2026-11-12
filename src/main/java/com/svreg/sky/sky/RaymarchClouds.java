package com.svreg.sky.sky;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.svreg.sky.SvregSky;
import net.minecraft.client.Minecraft;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The raymarched Ghibli puff clouds: a baked noise volume (CrumbleNoise) is marched per pixel into a small buffer
 * (1/2 .. 1/8 of the screen), toon-lit, steadied against flicker, then upscaled. The composite writes depth so
 * terrain in front still hides the clouds.
 */
public final class RaymarchClouds {
	/** the cloud layer is this tall (blocks) */
	public static final float LAYER = 125F;

	private static RenderPipeline crumblePipeline, compPipeline, temporalPipeline, hillPipeline;
	/** the painted hills' silhouette, one height per compass direction (cirrus_hillmask), and which toggles it was baked for */
	private static GpuTexture hillTex;
	private static GpuTextureView hillView;
	private static int hillKey = -1;
	private static final com.mojang.blaze3d.pipeline.BindGroupLayout THREE_SAMPLERS = com.mojang.blaze3d.pipeline.BindGroupLayout.builder()
			.withSampler("Sampler0").withSampler("Sampler1").withSampler("Sampler2").build();
	private static GpuBuffer quad;
	private static net.minecraft.client.renderer.texture.DynamicTexture crumbleTex;
	/** two march buffers and two anti-flicker results, used alternately (this frame / last frame) */
	private static final GpuTexture[] raw = new GpuTexture[2], hist = new GpuTexture[2];
	private static final GpuTextureView[] rawView = new GpuTextureView[2], histView = new GpuTextureView[2];
	private static int frame;
	private static boolean histValid;
	private static Matrix4f prevView;
	private static Vector3f prevCam;
	private static boolean broken;
	private static double windX, windZ, churn;
	private static final com.svreg.sky.look.Twos.Clock TWOS = new com.svreg.sky.look.Twos.Clock();
	/** the puff clouds' weather (0 fair .. 1 storm): gathers before the rain, stays thick through it, breaks up after */
	private static float storm;
	private static float lastBottomY;

	/** weather-driven cloud state for other effects */
	/** round, smooth cloud edges when upscaling (off for the pixel looks, which want the sharp cloud pixels) */
	private static boolean smoothEdges() {
		return SkySettings.on("cloud_smooth_edges") && !SkySettings.cloudPalette.equals("mongolia_pixel") && !SkySettings.on("pixel_art");
	}

	public static float storm() {
		return storm;
	}

	public static double windX() {
		return windX;
	}

	public static double windZ() {
		return windZ;
	}

	public static float bottomY() {
		return lastBottomY;
	}

	private static int lastUntil = -1;
	private static double untilStill;

	/** follows the weather: the single-player rain timer lets the clouds gather in the last three minutes before the rain */
	private static void updateStorm(Minecraft mc, double dt) {
		float rainNow = CirrusSky.rainLevel();
		if (mc.level != null) {
			rainNow = Math.max(rainNow, mc.level.getThunderLevel(1.0F));
		}
		float target = rainNow;
		try {
			if (mc.getSingleplayerServer() != null && rainNow < 0.05F) {
				net.minecraft.server.level.ServerLevel ow = mc.getSingleplayerServer().overworld();
				net.minecraft.world.level.saveddata.WeatherData w = ow != null ? ow.getWeatherData() : null;
				int until = w == null ? 0 : (w.getClearWeatherTime() > 0 ? w.getClearWeatherTime() : w.getRainTime());
				// only a timer that is really counting down means rain is coming (a stopped weather cycle, a paused
				// game or a weather command can leave it frozen under three minutes -- that must not hold the clouds)
				untilStill = until == lastUntil ? untilStill + dt : 0;
				lastUntil = until;
				if (w != null && !w.isRaining() && until > 0 && until < 3600 && untilStill < 3.0) {
					float t = 1 - until / 3600F;
					target = Math.max(target, 0.6F * t * t * (3 - 2 * t));
				}
			}
		} catch (Throwable ignored) {
			// weather timer not reachable: the clouds just follow the rain itself
		}
		if (!SkySettings.on("cloud_weather")) {
			target = 0;
		}
		// gather over ~20 s; once the rain is over break up over about a minute, and always come back to fair
		float rate = target > storm ? 0.05F : 0.03F;
		storm += (target - storm) * (float) Math.min(1, dt * rate * 4);
		if (target < 0.01F && storm < 0.02F) {
			storm = 0;
		}
		storm = Math.max(0, Math.min(1, storm));
	}

	private static long lastNanos;
	private static int logs;

	private RaymarchClouds() {
	}

	private static RenderPipeline pipeline(String name, ColorTargetState color, Optional<DepthStencilState> depth, boolean sampler) {
		return pipeline(name, color, depth, sampler ? BindGroupLayouts.SAMPLER0 : null);
	}

	private static RenderPipeline pipeline(String name, ColorTargetState color, Optional<DepthStencilState> depth, com.mojang.blaze3d.pipeline.BindGroupLayout samplers) {
		Identifier sh = Identifier.fromNamespaceAndPath(SvregSky.MOD_ID, "core/" + name);
		var b = RenderPipeline.builder(RenderPipeline.builder().withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION).buildSnippet())
				.withLocation(Identifier.fromNamespaceAndPath(SvregSky.MOD_ID, "pipeline/" + name))
				.withVertexShader(sh)
				.withFragmentShader(sh)
				.withVertexBinding(0, DefaultVertexFormat.POSITION)
				.withPrimitiveTopology(PrimitiveTopology.QUADS)
				.withCull(false)
				.withDepthStencilState(depth)
				.withColorTargetState(color);
		if (samplers != null) {
			b = b.withBindGroupLayout(samplers);
		}
		return b.build();
	}

	private static void init() {
		if (crumblePipeline == null) {
			crumblePipeline = pipeline("cirrus_crumble", ColorTargetState.DEFAULT, Optional.empty(), BindGroupLayouts.SAMPLER0_SAMPLER1);
			hillPipeline = pipeline("cirrus_hillmask", ColorTargetState.DEFAULT, Optional.empty(), (com.mojang.blaze3d.pipeline.BindGroupLayout) null);
			compPipeline = pipeline("cirrus_rmcomp", new ColorTargetState(com.mojang.blaze3d.pipeline.BlendFunction.TRANSLUCENT), Optional.of(DepthStencilState.DEFAULT), true);
			temporalPipeline = pipeline("cirrus_rmtemporal", ColorTargetState.DEFAULT, Optional.empty(), THREE_SAMPLERS);
		}
		if (crumbleTex == null) {
			// the Ghibli puff mode: puff detail and the weather map (sharp + blurred) baked once into one atlas
			int[] a = CrumbleNoise.bake();
			com.mojang.blaze3d.platform.NativeImage img = new com.mojang.blaze3d.platform.NativeImage(CrumbleNoise.WIDTH, CrumbleNoise.HEIGHT, false);
			for (int y = 0; y < CrumbleNoise.HEIGHT; y++) {
				for (int x = 0; x < CrumbleNoise.WIDTH; x++) {
					img.setPixel(x, y, a[x + y * CrumbleNoise.WIDTH]); // ARGB
				}
			}
			crumbleTex = new net.minecraft.client.renderer.texture.DynamicTexture(() -> "SvregSky crumble noise", img);
		}
		if (quad == null) {
			try (ByteBufferBuilder bytes = new ByteBufferBuilder(DefaultVertexFormat.POSITION.getVertexSize() * 4)) {
				BufferBuilder b = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION);
				b.addVertex(-1, -1, 0);
				b.addVertex(1, -1, 0);
				b.addVertex(1, 1, 0);
				b.addVertex(-1, 1, 0);
				try (MeshData mesh = b.buildOrThrow()) {
					quad = RenderSystem.getDevice().createBuffer(() -> "SvregSky raymarch quad", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
				}
			}
		}
	}

	/** time-of-day keyframes (day ticks, amount x, height x): the clouds grow through the day and fade at night */
	private static final float[][] LIFE = {
			{0, 0.75F, 0.55F}, {3000, 0.9F, 0.8F}, {6000, 1.0F, 1.0F}, {9000, 1.08F, 1.35F},
			{11500, 1.0F, 1.15F}, {13000, 0.88F, 0.85F}, {18000, 0.75F, 0.6F}, {24000, 0.75F, 0.55F}};

	/**
	 * Today's sky (the existing "daily_variety" toggle): {amount factor, height factor}. Calm days with a few low heaps,
	 * ordinary days, days of crowded tall towers. Eases over from yesterday's sky during the first hours of the day.
	 */
	private static float[] dailyMood(Minecraft mc) {
		if (!SkySettings.on("daily_variety") || mc.level == null) {
			return new float[]{1, 1};
		}
		long time = mc.level.getOverworldClockTime();
		long d = Math.floorDiv(time, 24000L);
		float k = Math.min(1, Math.floorMod(time, 24000L) / 3000F);
		k = k * k * (3 - 2 * k);
		float[] a = moodOf(d - 1), b = moodOf(d);
		return new float[]{a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k};
	}

	private static float[] moodOf(long d) {
		double h = Math.sin(d * 9.731 + 4.17) * 31417.123, g = Math.sin(d * 5.113 + 1.93) * 27183.77;
		h -= Math.floor(h);
		g -= Math.floor(g);
		return new float[]{(float) (0.6 + 0.9 * h), (float) (0.85 + 0.4 * g)};
	}

	private static final float[] bounce = new float[3], bounceTo = new float[3];
	private static long bounceAt;

	/**
	 * The colour the land under the camera throws up onto the cloud bellies ("cloud_bounce"), eased over a few seconds
	 * and packed as 5 bits each of r, g, b (0 = none): teal over water, warm over sand, icy over snow, green elsewhere.
	 */
	private static int bounceCode(Minecraft mc, double dt) {
		if (!SkySettings.on("cloud_bounce") || mc.level == null || mc.player == null) {
			bounce[0] = bounce[1] = bounce[2] = 0;
			return 0;
		}
		long now = System.nanoTime();
		if (now - bounceAt > 1_000_000_000L) {
			bounceAt = now;
			var at = mc.player.blockPosition();
			var biome = mc.level.getBiome(at);
			float r, g, b;
			if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_BEACH) || biome.is(BiomeTags.IS_RIVER)) {
				r = 0.50F; g = 0.86F; b = 0.92F;
			} else if (biome.is(Biomes.DESERT) || biome.is(Biomes.BADLANDS) || biome.is(Biomes.ERODED_BADLANDS) || biome.is(Biomes.WOODED_BADLANDS)
					|| biome.is(Biomes.SAVANNA) || biome.is(Biomes.SAVANNA_PLATEAU) || biome.is(Biomes.WINDSWEPT_SAVANNA)) {
				r = 1.0F; g = 0.84F; b = 0.58F;
			} else if (biome.value().coldEnoughToSnow(at, mc.level.getSeaLevel())) {
				r = 0.84F; g = 0.92F; b = 1.0F;
			} else {
				r = 0.66F; g = 0.92F; b = 0.58F;
			}
			bounceTo[0] = r;
			bounceTo[1] = g;
			bounceTo[2] = b;
		}
		float k = (float) Math.max(0, Math.min(1, dt * 0.5));
		for (int i = 0; i < 3; i++) {
			bounce[i] += (bounceTo[i] - bounce[i]) * k;
		}
		return Math.round(bounce[0] * 31) | Math.round(bounce[1] * 31) << 5 | Math.round(bounce[2] * 31) << 10;
	}

	/** {amount factor, height factor} for now; {1, 1} when living clouds are off */
	private static float[] lifeCycle(Minecraft mc) {
		if (!SkySettings.on("living_clouds") || mc.level == null) {
			return new float[]{1, 1};
		}
		float t = (float) Math.floorMod(mc.level.getOverworldClockTime(), 24000L);
		for (int i = 0; i + 1 < LIFE.length; i++) {
			if (t <= LIFE[i + 1][0]) {
				float k = (t - LIFE[i][0]) / (LIFE[i + 1][0] - LIFE[i][0]);
				k = k * k * (3 - 2 * k);
				return new float[]{LIFE[i][1] + (LIFE[i + 1][1] - LIFE[i][1]) * k, LIFE[i][2] + (LIFE[i + 1][2] - LIFE[i][2]) * k};
			}
		}
		return new float[]{1, 1};
	}

	/** bakes the hill silhouette strip when it is missing or the hill shape toggles changed (lake, pines) */
	private static void bakeHills(boolean lake, boolean pines, Matrix4f view, GpuTexture like) {
		int key = (lake ? 1 : 0) | (pines ? 2 : 0);
		if (hillTex == null) {
			hillTex = RenderSystem.getDevice().createTexture(() -> "SvregSky hill silhouette", 15, like.getFormat(), 1024, 1, 1, 1);
			hillView = RenderSystem.getDevice().createTextureView(hillTex);
			hillKey = -1;
		}
		if (key == hillKey) {
			return;
		}
		hillKey = key;
		var t = RenderSystem.getDynamicUniforms().writeTransform(view, new Vector4f(lake ? 1 : 0, pines ? 1 : 0, 0, 0), new Vector3f(), new Matrix4f());
		RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "SvregSky hill silhouette", hillView, Optional.of(new Vector4f(0, 0, 0, 0)), null, OptionalDouble.empty())) {
			pass.setPipeline(hillPipeline);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DynamicTransforms", t);
			pass.setVertexBuffer(0, quad.slice());
			pass.setIndexBuffer(indices.getBuffer(6), indices.type());
			pass.drawIndexed(6, 1, 0, 0, 0);
		}
	}

	public static void render(float bottomY, Vec3 cam) {
		if (broken) {
			return;
		}
		try {
			init();
			Minecraft mc = Minecraft.getInstance();
			long now = System.nanoTime();
			double dt = lastNanos == 0 ? 0 : Math.min(0.25, (now - lastNanos) / 1e9);
			lastNanos = now;
			dt = TWOS.tick(dt); // on twos: the clouds drift and churn in 12 steps a second
			double speed = 1.6 * com.svreg.sky.wind.WindState.cloudSpeed(); // the living wind drifts them faster in a gust
			windX = (windX + dt * speed) % 100000.0;
			windZ = (windZ + dt * speed * 0.33) % 100000.0;

			RenderTarget cloudTarget = mc.levelRenderer.cloudsTarget();
			RenderTarget target = cloudTarget != null ? cloudTarget : mc.gameRenderer.mainRenderTarget();
			int scale = Math.max(2, Math.min(8, SkySettings.raymarchScale));
			// fewer march steps at lower resolutions: the big pixels hide the coarser sampling
			// (the march jumps over empty space near clouds, so its rays rarely need the full count)
			int steps = scale >= 8 ? 56 : scale >= 6 ? 64 : scale >= 4 ? 72 : scale >= 3 ? 80 : 96;
			int w = Math.max(1, (target.width + scale - 1) / scale), h = Math.max(1, (target.height + scale - 1) / scale);
			if (raw[0] == null || raw[0].getWidth(0) != w || raw[0].getHeight(0) != h) {
				for (int i = 0; i < 2; i++) {
					if (rawView[i] != null) {
						rawView[i].close();
						raw[i].close();
						histView[i].close();
						hist[i].close();
					}
					raw[i] = RenderSystem.getDevice().createTexture(() -> "SvregSky raymarch clouds", 15, target.getColorTexture().getFormat(), w, h, 1, 1);
					rawView[i] = RenderSystem.getDevice().createTextureView(raw[i]);
					hist[i] = RenderSystem.getDevice().createTexture(() -> "SvregSky raymarch steady", 15, target.getColorTexture().getFormat(), w, h, 1, 1);
					histView[i] = RenderSystem.getDevice().createTextureView(hist[i]);
				}
				histValid = false;
				if (logs++ < 2) {
					SvregSky.LOG.info("SvregSky: raymarch clouds buffer {}x{} (1/{})", w, h, scale);
				}
			}
			frame ^= 1;
			GpuTextureView curRaw = rawView[frame], lastRaw = rawView[frame ^ 1], curHist = histView[frame], lastHist = histView[frame ^ 1];

			float reach = Math.max(600, Math.min(3000, SkySettings.cloud3dDistance * 3 * 4.0F * 1.4F));
			float[] hzSun = CirrusSky.horizonToward(true);
			float[] hzAway = CirrusSky.horizonToward(false);
			Matrix4f params = CirrusSky.skyParams();
			// living clouds: small morning puffs, full at noon, towers in the afternoon, thinning into the night
			float[] life = lifeCycle(mc);
			float[] mood = dailyMood(mc);
			life[0] *= mood[0];
			life[1] *= mood[1];
			updateStorm(mc, dt);
			lastBottomY = bottomY;
			// stormy: more and taller cloud, and the palette greys before the rain starts
			params.m03(LAYER * life[1] * (1 + 0.25F * storm));
			params.m13(Math.max(params.m13(), 0.7F * storm));
			params.m20(hzAway[0]);
			params.m21(hzAway[1]);
			params.m22(hzAway[2]);
			float amt = Math.max(0.25F, Math.min(2.5F, SkySettings.ghibliAmount * life[0] * (1 + 1.1F * storm)));
			params.m23(amt);
			params.m30(hzSun[0]);
			params.m32(hzSun[1]);
			params.m33(hzSun[2]);
			// puffs churn slowly upward through the bodies (0..64 blocks, the detail tile height), and the afterglow
			// just after sunset; packed into one slot: 100 x afterglow (0..99) + churn
			if (SkySettings.on("living_clouds")) {
				churn = (churn + dt * 0.25) % 64.0;
			}
			// + 10000 x bits: 1 painted hills (clouds hide behind them), 2 lake, 4 pines (hill shape), 8 horizon cloud bank, 128 big clouds
			boolean hills = SkySettings.horizonMountains;
			int bits = (hills ? 1 : 0) | (hills && SkySettings.on("horizon_lake") ? 2 : 0) | (hills && SkySettings.on("horizon_pines") ? 4 : 0)
					| (SkySettings.on("horizon_bank") ? 8 : 0) | (!SkySettings.cloudPalette.equals("normal") ? 16 : 0) | (SkySettings.cloudPalette.equals("mongolia_pixel") ? 32 : 0)
					| (hills && SkySettings.on("giant_tree") ? 64 : 0) | (SkySettings.on("big_clouds") ? 128 : 0);
			params.m31(bits * 10000 + Math.round(Twilight.blueHour() * 99) * 100 + (float) churn);
			float org = CirrusSky.skyExtra().w;
			Vector3f camCloud = new Vector3f((float) (cam.x - windX), (float) (cam.y - bottomY), (float) (cam.z - windZ));
			boolean grow = SkySettings.on("cloud_grow") && SkySettings.on("living_clouds");
			PuffShadow.update(camCloud.x, camCloud.z, amt, grow ? (float) churn : -1);
			Matrix4f view = new Matrix4f(RenderSystem.getModelViewMatrixCopy());
			// x: the buffer height (the puff mode sizes its detail by how many blocks one cloud pixel covers)
			// z: step budget + aerial fade strength / 4 in the fraction (0..2 -> 0..0.5)
			float fade = Math.max(0, Math.min(2, SkySettings.aerialFade));
			// toon clouds: a code in the fraction of the reach slot (1 toon, 2 four bands, 4 cream day, 8 gold sunset, 16 slate
			// night, 32 round bellies) and the rim light strength / 4 in the fraction of the buffer height slot (1 = the classic rim)
			int code = (SkySettings.on("toon_clouds") ? 1 : 0) | (SkySettings.cloudBands >= 4 ? 2 : 0) | ("cream".equals(SkySettings.cloudDayTone) ? 4 : 0)
					| ("gold".equals(SkySettings.cloudSunsetTone) ? 8 : 0) | ("slate".equals(SkySettings.cloudNightTone) ? 16 : 0)
					| (SkySettings.on("round_belly") ? 32 : 0);
			float rim = Math.max(0, Math.min(2, SkySettings.cloudRim));
			// extras, packed beside what was there (see main() in cirrus_crumble.fsh):
			// x: buffer height + (rim 0..63 + 64 x lightning flash 0..63) / 4096
			// z: steps + (aerial fade 0..63 + 64 x feature bits) / 4096 -- 1 horizon layers, 2 horizon gaps, 4 sunset layers,
			//    8 silver lining, 16 growing clouds, 32 shaded valleys from above
			// w: orange sunset 0..63 + 64 x the bounce tint
			int feat = (SkySettings.on("horizon_layers") ? 1 : 0) | (SkySettings.on("horizon_gaps") ? 2 : 0) | (SkySettings.on("sunset_layers") ? 4 : 0)
					| (SkySettings.on("silver_lining") ? 8 : 0) | (grow ? 16 : 0) | (SkySettings.on("cloud_top_shade") ? 32 : 0);
			int flashQ = Math.round(Math.max(0, Math.min(1, CirrusSky.flashLevel())) * 63);
			float packX = h + (Math.round(rim * 31.5F) + 64 * flashQ) / 4096.0F;
			float packZ = steps + (Math.round(fade * 31.5F) + 64 * feat) / 4096.0F;
			float packW = Math.round(Math.max(0, Math.min(1, org)) * 63) + 64 * bounceCode(mc, dt);
			var marchT = RenderSystem.getDynamicUniforms().writeTransform(view, new Vector4f(packX, (float) Math.floor(reach) + code / 64.0F, packZ, packW), camCloud, params);
			bakeHills((bits & 2) != 0, (bits & 4) != 0, view, target.getColorTexture());
			// anti-flicker: last frame is usable unless the buffers or the timing just changed
			boolean steady = SkySettings.on("cloud_steady");
			boolean valid = steady && histValid && prevView != null && dt > 0 && dt < 0.2;
			var steadyT = RenderSystem.getDynamicUniforms().writeTransform(view, new Vector4f(valid ? 1 : 0, 0, 0, 0),
					prevCam == null ? new Vector3f() : new Vector3f(camCloud).sub(prevCam), prevView == null ? view : prevView);
			boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
			var compT = RenderSystem.getDynamicUniforms().writeTransform(view, new Vector4f(smoothEdges() ? scale : -scale, zeroToOne ? 1 : 0, fade, reach), new Vector3f(), new Matrix4f());

			RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
			GpuBuffer ib = indices.getBuffer(6);
			IndexType type = indices.type();
			try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "SvregSky raymarch clouds", curRaw, Optional.of(new Vector4f(0, 0, 0, 0)), null, OptionalDouble.empty())) {
				pass.setPipeline(crumblePipeline);
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("DynamicTransforms", marchT);
				pass.bindTexture("Sampler0", crumbleTex.getTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
				pass.bindTexture("Sampler1", hillView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
				pass.setVertexBuffer(0, quad.slice());
				pass.setIndexBuffer(ib, type);
				pass.drawIndexed(6, 1, 0, 0, 0);
			}
			GpuTextureView shown = curRaw;
			if (steady) {
				// a cloud pixel only changes once the change shows in two frames in a row (cirrus_rmtemporal)
				try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "SvregSky raymarch steady", curHist, Optional.of(new Vector4f(0, 0, 0, 0)), null, OptionalDouble.empty())) {
					pass.setPipeline(temporalPipeline);
					RenderSystem.bindDefaultUniforms(pass);
					pass.setUniform("DynamicTransforms", steadyT);
					pass.bindTexture("Sampler0", curRaw, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
					pass.bindTexture("Sampler1", lastRaw, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
					pass.bindTexture("Sampler2", lastHist, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
					pass.setVertexBuffer(0, quad.slice());
					pass.setIndexBuffer(ib, type);
					pass.drawIndexed(6, 1, 0, 0, 0);
				}
				shown = curHist;
			}
			histValid = steady;
			prevView = view;
			prevCam = camCloud;
			try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "SvregSky raymarch composite",
					target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
				pass.setPipeline(compPipeline);
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("DynamicTransforms", compT);
				pass.bindTexture("Sampler0", shown, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
				pass.setVertexBuffer(0, quad.slice());
				pass.setIndexBuffer(ib, type);
				pass.drawIndexed(6, 1, 0, 0, 0);
			}
		} catch (Throwable t) {
			broken = true;
			SvregSky.LOG.error("SvregSky: raymarch clouds failed, turning them off", t);
		}
	}
}
