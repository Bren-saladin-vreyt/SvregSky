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
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * "Raymarch Ghibli" clouds (beta): instead of a voxel mesh, a noise volume is marched per pixel into a small buffer
 * (1/2 .. 1/4 of the screen), toon-lit in four tones, then upscaled with sharp pixels. The composite writes depth so
 * terrain in front still hides the clouds.
 */
public final class RaymarchClouds {
	/** the cloud layer is this tall (blocks) */
	public static final float LAYER = 125F;

	private static RenderPipeline marchPipeline, crumblePipeline, compPipeline, temporalPipeline, hillPipeline;
	/** the painted hills' silhouette, one height per compass direction (cirrus_hillmask), and which toggles it was baked for */
	private static GpuTexture hillTex;
	private static GpuTextureView hillView;
	private static int hillKey = -1;
	private static final com.mojang.blaze3d.pipeline.BindGroupLayout THREE_SAMPLERS = com.mojang.blaze3d.pipeline.BindGroupLayout.builder()
			.withSampler("Sampler0").withSampler("Sampler1").withSampler("Sampler2").build();
	private static GpuBuffer quad;
	private static net.minecraft.client.renderer.texture.DynamicTexture noiseTex, crumbleTex;
	/** two march buffers and two anti-flicker results, used alternately (this frame / last frame) */
	private static final GpuTexture[] raw = new GpuTexture[2], hist = new GpuTexture[2];
	private static final GpuTextureView[] rawView = new GpuTextureView[2], histView = new GpuTextureView[2];
	private static int frame;
	private static boolean histValid, lastCrumble;
	private static Matrix4f prevView;
	private static Vector3f prevCam;
	private static boolean broken;
	private static double windX, windZ, churn;
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
		if (marchPipeline == null) {
			marchPipeline = pipeline("cirrus_raymarch", ColorTargetState.DEFAULT, Optional.empty(), true);
			crumblePipeline = pipeline("cirrus_crumble", ColorTargetState.DEFAULT, Optional.empty(), BindGroupLayouts.SAMPLER0_SAMPLER1);
			hillPipeline = pipeline("cirrus_hillmask", ColorTargetState.DEFAULT, Optional.empty(), (com.mojang.blaze3d.pipeline.BindGroupLayout) null);
			compPipeline = pipeline("cirrus_rmcomp", ColorTargetState.DEFAULT, Optional.of(DepthStencilState.DEFAULT), true);
			temporalPipeline = pipeline("cirrus_rmtemporal", ColorTargetState.DEFAULT, Optional.empty(), THREE_SAMPLERS);
		}
		if (noiseTex == null) {
			// the erosion noise is baked once (about a quarter second) and read with bilinear filtering from then on
			byte[] a = RaymarchNoise.bake();
			com.mojang.blaze3d.platform.NativeImage img = new com.mojang.blaze3d.platform.NativeImage(RaymarchNoise.WIDTH, RaymarchNoise.HEIGHT, false);
			for (int y = 0; y < RaymarchNoise.HEIGHT; y++) {
				for (int x = 0; x < RaymarchNoise.WIDTH; x++) {
					int g = a[x + y * RaymarchNoise.WIDTH] & 255;
					img.setPixel(x, y, 0xFF000000 | g << 16 | g << 8 | g);
				}
			}
			noiseTex = new net.minecraft.client.renderer.texture.DynamicTexture(() -> "SvregSky raymarch noise", img);
		}
		if (crumbleTex == null && SkySettings.cloudCrumble()) {
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
	private static void bakeHills(boolean lake, boolean pines, Matrix4f view, com.mojang.blaze3d.textures.TextureFormat format) {
		int key = (lake ? 1 : 0) | (pines ? 2 : 0);
		if (hillTex == null) {
			hillTex = RenderSystem.getDevice().createTexture(() -> "SvregSky hill silhouette", 15, format, 1024, 1, 1, 1);
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
			double speed = 1.6 * SkySettings.cloudSpeed;
			windX = (windX + dt * speed) % 100000.0;
			windZ = (windZ + dt * speed * 0.33) % 100000.0;

			RenderTarget cloudTarget = mc.levelRenderer.cloudsTarget();
			RenderTarget target = cloudTarget != null ? cloudTarget : mc.gameRenderer.mainRenderTarget();
			int scale = Math.max(2, Math.min(8, SkySettings.raymarchScale));
			// fewer march steps at lower resolutions: the big pixels hide the coarser sampling
			int steps = scale >= 8 ? 56 : scale >= 6 ? 64 : scale >= 4 ? 80 : scale >= 3 ? 90 : 100;
			if (SkySettings.cloudCrumble()) {
				// the puff mode jumps over empty space near clouds, so its rays rarely need the full count
				steps = scale >= 8 ? 56 : scale >= 6 ? 64 : scale >= 4 ? 72 : scale >= 3 ? 80 : 96;
			}
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
			params.m03(LAYER * life[1]);
			params.m20(hzAway[0]);
			params.m21(hzAway[1]);
			params.m22(hzAway[2]);
			params.m23(Math.max(0.25F, Math.min(2.5F, SkySettings.ghibliAmount * life[0])));
			params.m30(hzSun[0]);
			params.m32(hzSun[1]);
			params.m33(hzSun[2]);
			// puffs churn slowly upward through the bodies (0..64 blocks, the detail tile height), and the afterglow
			// just after sunset; packed into one slot: 100 x afterglow (0..99) + churn
			if (SkySettings.on("living_clouds")) {
				churn = (churn + dt * 0.25) % 64.0;
			}
			// + 10000 x bits: 1 painted hills (clouds hide behind them), 2 lake, 4 pines (hill shape), 8 horizon cloud bank
			boolean hills = SkySettings.horizonMountains;
			int bits = (hills ? 1 : 0) | (hills && SkySettings.on("horizon_lake") ? 2 : 0) | (hills && SkySettings.on("horizon_pines") ? 4 : 0)
					| (SkySettings.on("horizon_bank") ? 8 : 0);
			params.m31(bits * 10000 + Math.round(Twilight.blueHour() * 99) * 100 + (float) churn);
			float org = CirrusSky.skyExtra().w;
			Vector3f camCloud = new Vector3f((float) (cam.x - windX), (float) (cam.y - bottomY), (float) (cam.z - windZ));
			Matrix4f view = new Matrix4f(RenderSystem.getModelViewMatrixCopy());
			// x: the buffer height (the puff mode sizes its detail by how many blocks one cloud pixel covers)
			var marchT = RenderSystem.getDynamicUniforms().writeTransform(view, new Vector4f(h, reach, steps, org), camCloud, params);
			boolean crumble = SkySettings.cloudCrumble();
			if (crumble) {
				bakeHills((bits & 2) != 0, (bits & 4) != 0, view, target.getColorTexture().getFormat());
			}
			// anti-flicker: last frame is usable unless the buffers, the mode or the timing just changed
			boolean steady = SkySettings.on("cloud_steady");
			boolean valid = steady && histValid && prevView != null && crumble == lastCrumble && dt > 0 && dt < 0.2;
			var steadyT = RenderSystem.getDynamicUniforms().writeTransform(view, new Vector4f(valid ? 1 : 0, 0, 0, 0),
					prevCam == null ? new Vector3f() : new Vector3f(camCloud).sub(prevCam), prevView == null ? view : prevView);
			boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
			var compT = RenderSystem.getDynamicUniforms().writeTransform(view, new Vector4f(scale, zeroToOne ? 1 : 0, 0, 0), new Vector3f(), new Matrix4f());

			RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
			GpuBuffer ib = indices.getBuffer(6);
			IndexType type = indices.type();
			try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "SvregSky raymarch clouds", curRaw, Optional.of(new Vector4f(0, 0, 0, 0)), null, OptionalDouble.empty())) {
				pass.setPipeline(crumble ? crumblePipeline : marchPipeline);
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("DynamicTransforms", marchT);
				pass.bindTexture("Sampler0", (crumble ? crumbleTex : noiseTex).getTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
				if (crumble) {
					pass.bindTexture("Sampler1", hillView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
				}
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
			lastCrumble = crumble;
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
