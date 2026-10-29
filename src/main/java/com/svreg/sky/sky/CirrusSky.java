package com.svreg.sky.sky;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.svreg.sky.SvregSky;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Draws the Cirrus sky: one camera-centred cube, coloured per pixel by cirrus_sky.fsh.
 * Pass 1 (sky) replaces the vanilla sky disc and sunrise fan; pass 2 (clouds) runs after the sun and moon so clouds cover them.
 * All sky parameters ride along in the DynamicTransforms uniform (TextureMat + ColorModulator), so no custom uniform blocks are needed.
 */
public final class CirrusSky {
	private static final Identifier SHADER = Identifier.fromNamespaceAndPath(SvregSky.MOD_ID, "core/cirrus_sky");

	private static final RenderPipeline.Snippet SNIPPET = RenderPipeline.builder()
			.withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
			.buildSnippet();

	private static final int F_GRADIENT = 1, F_CLOUDS = 2, F_STARS = 4, F_MILKYWAY = 8, F_AURORA = 16, F_SHOOTING = 32, F_RAYS = 64;

	private static RenderPipeline skyPipeline;
	private static RenderPipeline cloudPipeline;
	private static GpuBuffer cube;
	private static boolean broken;

	private static SkyRenderState state;
	private static boolean overworld;
	private static long lastDrawNanos;
	private static long lastFrameNanos;
	private static final long START = System.nanoTime();
	private static double windX, windZ;

	// last frame's sky factors (also used for the fog colour)
	private static final Vector3f sunDir = new Vector3f(0, 1, 0);
	private static float day = 1, gold, night, rain, rise, org;
	private static float rainbowLevel, flash, dailyCoverage, towerAmt, towerSeed, wet;
	private static long wetNanos;
	private static final Identifier TOWERS = Identifier.fromNamespaceAndPath(SvregSky.MOD_ID, "textures/sky/towers.png");
	/** test hook: towers every day */
	public static boolean forceTowers;
	private static boolean megaToday;
	private static int moonPhase;
	private static boolean rainbowArmed;
	private static long rainbowStart;

	private CirrusSky() {
	}

	private static final com.mojang.blaze3d.pipeline.BindGroupLayout THREE_SAMPLERS = com.mojang.blaze3d.pipeline.BindGroupLayout.builder()
			.withSampler("Sampler0").withSampler("Sampler1").withSampler("Sampler2").build();
	private static net.minecraft.client.renderer.texture.DynamicTexture footTex;
	private static int footVersion = -1;

	/** which grid columns hold 3D Ghibli cloud, as a 256 x 256 texture for the light rays */
	private static AbstractTexture footprint(AbstractTexture fallback) {
		if (!SkySettings.cloudGhibli()) {
			return footTex != null ? footTex : fallback;
		}
		int v = GhibliClouds.gridVersion();
		if (v != footVersion) {
			byte[] f = GhibliClouds.footprint();
			if (f != null) {
				com.mojang.blaze3d.platform.NativeImage img = new com.mojang.blaze3d.platform.NativeImage(GhibliClouds.NX, GhibliClouds.NZ, false);
				for (int z = 0; z < GhibliClouds.NZ; z++) {
					for (int x = 0; x < GhibliClouds.NX; x++) {
						img.setPixel(x, z, f[x + z * GhibliClouds.NX] != 0 ? 0xFFFFFFFF : 0xFF000000);
					}
				}
				if (footTex == null) {
					footTex = new net.minecraft.client.renderer.texture.DynamicTexture(() -> "SvregSky cloud footprint", img);
				} else {
					footTex.setPixels(img);
					footTex.upload();
				}
				footVersion = v;
			}
		}
		return footTex != null ? footTex : fallback;
	}

	private static RenderPipeline build(String name, ColorTargetState color) {
		return RenderPipeline.builder(SNIPPET)
				.withLocation(Identifier.fromNamespaceAndPath(SvregSky.MOD_ID, "pipeline/" + name))
				.withVertexShader(SHADER)
				.withFragmentShader(SHADER)
				.withVertexBinding(0, DefaultVertexFormat.POSITION)
				.withPrimitiveTopology(PrimitiveTopology.QUADS)
				.withCull(false)
				.withDepthStencilState(Optional.empty())
				.withColorTargetState(color)
				.withBindGroupLayout(THREE_SAMPLERS)
				.build();
	}

	private static boolean init() {
		if (broken) {
			return false;
		}
		if (cube != null) {
			return true;
		}
		try {
			skyPipeline = build("cirrus_sky", ColorTargetState.DEFAULT);
			cloudPipeline = build("cirrus_clouds", new ColorTargetState(BlendFunction.TRANSLUCENT));
			float s = 10.0F;
			try (ByteBufferBuilder bytes = new ByteBufferBuilder(DefaultVertexFormat.POSITION.getVertexSize() * 24)) {
				BufferBuilder b = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION);
				// +Y, -Y, +X, -X, +Z, -Z
				quad(b, -s, s, -s, s, s, -s, s, s, s, -s, s, s);
				quad(b, -s, -s, -s, -s, -s, s, s, -s, s, s, -s, -s);
				quad(b, s, -s, -s, s, -s, s, s, s, s, s, s, -s);
				quad(b, -s, -s, -s, -s, s, -s, -s, s, s, -s, -s, s);
				quad(b, -s, -s, s, -s, s, s, s, s, s, s, -s, s);
				quad(b, -s, -s, -s, s, -s, -s, s, s, -s, -s, s, -s);
				try (MeshData mesh = b.buildOrThrow()) {
					cube = RenderSystem.getDevice().createBuffer(() -> "SvregSky sky cube", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
				}
			}
			SvregSky.LOG.info("SvregSky: Cirrus sky ready");
			return true;
		} catch (Throwable t) {
			broken = true;
			SvregSky.LOG.error("SvregSky: Cirrus sky could not start, falling back to the vanilla sky", t);
			return false;
		}
	}

	private static void quad(BufferBuilder b, float... v) {
		for (int i = 0; i < 12; i += 3) {
			b.addVertex(v[i], v[i + 1], v[i + 2]);
		}
	}

	private static float smooth(float e0, float e1, float x) {
		float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
		return t * t * (3 - 2 * t);
	}

	/** start of the vanilla sky pass lambda (overworld-style skies only reach the sky disc call) */
	public static void beginFrame(SkyRenderState skyState) {
		state = skyState;
		Minecraft mcw = Minecraft.getInstance();
		if (mcw.player != null) {
			com.svreg.sky.wind.Wind.setCamera(mcw.player.getEyePosition()); // fallback when vanilla clouds are off
			com.svreg.sky.wind.Wind.setPlayer(mcw.player.position());
		}
		overworld = false;
		float a = skyState.sunAngle;
		if (SkySettings.photoMode) {
			// photo mode: the sky shows the chosen time of day (client-side only)
			double f = SkySettings.photoTime / 24000.0 - 0.25;
			f -= Math.floor(f);
			double d = 0.5 - Math.cos(f * Math.PI) / 2.0;
			a = (float) ((f * 2.0 + d) / 3.0 * Math.PI * 2.0);
		}
		sunDir.set(-(float) Math.sin(a), (float) Math.cos(a), 0);
		float sh = sunDir.y;
		day = smooth(-0.10F, 0.30F, sh);
		gold = 1 - smooth(0.0F, 0.42F, Math.abs(sh - 0.02F));
		night = 1 - smooth(-0.22F, 0.02F, sh);
		rain = Math.max(0, Math.min(1, 1 - skyState.rainBrightness));
		float thunder = mcw.level != null ? mcw.level.getThunderLevel(1.0F) : 0;
		if (SkySettings.photoMode) {
			rain = SkySettings.photoWeather.equals("clear") ? 0 : 1;
			thunder = SkySettings.photoWeather.equals("thunder") ? 1 : 0;
		}
		moonPhase = skyState.moonPhase != null ? skyState.moonPhase.index() : 0;
		// lightning glow: random flashes inside the clouds during thunderstorms
		flash *= 0.86F;
		if (SkySettings.on("lightning_glow") && thunder > 0.5F && Math.random() < 0.006) {
			flash = 0.7F + (float) Math.random() * 0.3F;
		}
		// daily variety: each day gets its own amount of cloud
		dailyCoverage = 0;
		if (SkySettings.on("daily_variety") && mcw.level != null) {
			long dayIndex = Math.floorDiv(mcw.level.getOverworldClockTime(), 24000L);
			double h = Math.sin(dayIndex * 12.9898 + 78.233) * 43758.5453;
			h -= Math.floor(h);
			dailyCoverage = (float) ((h - 0.45) * 0.16);
		}
		// towering cumulonimbus on the horizon: most summer days, some spring days, building up through the afternoon
		towerAmt = 0;
		if (SkySettings.on("cloud_towers") && mcw.level != null) {
			long dayIndex = Math.floorDiv(mcw.level.getOverworldClockTime(), 24000L);
			double h = Math.sin(dayIndex * 7.233 + 11.17) * 15731.743;
			h -= Math.floor(h);
			float chance = Seasons.summer() * 0.75F + Seasons.spring() * 0.35F + Seasons.autumn() * 0.2F + Seasons.winter() * 0.05F;
			if (h < chance || forceTowers) {
				float afternoon = sunDir.x < 0 ? 1.0F : 0.55F; // grow taller after noon (sun in the west)
				towerAmt = afternoon * Math.max(0.35F, day) * (1 - rain * 0.6F);
			}
			towerSeed = (float) (dayIndex % 97) * 1.37F + 3.1F;
			double hm = Math.sin(dayIndex * 3.913 + 2.71) * 24634.6345;
			megaToday = SkySettings.on("cloud_mega") && (hm - Math.floor(hm) < 0.4 || forceTowers);
		}
		// ground wetness: up while it rains, drying over a couple of minutes after
		long wn = System.nanoTime();
		float wdt = wetNanos == 0 ? 0 : (float) Math.min(0.2, (wn - wetNanos) / 1e9);
		wetNanos = wn;
		float rainNow = mcw.level != null ? Math.max(rain, mcw.level.getRainLevel(1.0F)) : rain;
		wet = Math.max(rainNow, wet - wdt / 150F);
		// rainbow: shows up for a while after a rain shower ends, while the sun is up
		long nowMs = System.currentTimeMillis();
		if (rain > 0.35F) {
			rainbowArmed = true;
			rainbowStart = 0;
		} else if (rainbowArmed && rain < 0.25F) {
			rainbowArmed = false;
			rainbowStart = nowMs;
		}
		float rbT = rainbowStart == 0 ? -1 : (nowMs - rainbowStart) / 1000F;
		rainbowLevel = !SkySettings.rainbow || rbT < 0 || rbT > 120 ? 0
				: Math.min(1, rbT / 10F) * Math.min(1, (120 - rbT) / 35F) * smooth(0.05F, 0.3F, sunDir.y);
		rise = smooth(-0.2F, 0.2F, sunDir.x);
		org = switch (SkySettings.sunsetStyle) {
			case "orange" -> 1.0F;
			case "magenta" -> 0.0F;
			default -> smooth(0.0F, 0.14F, sh); // amber while the sun is still up, magenta as it touches the horizon
		};

		long now = System.nanoTime();
		double dt = lastFrameNanos == 0 ? 0 : Math.min(0.25, (now - lastFrameNanos) / 1e9);
		lastFrameNanos = now;
		double speed = 0.02 * SkySettings.cloudSpeed;
		windX = (windX + dt * speed) % 40.0;
		windZ = (windZ + dt * speed * 0.33) % 40.0;
	}

	/** sky colour parameters for other Cirrus shaders (3D clouds): same layout as the sky shader's TextureMat */
	public static Matrix4f skyParams() {
		float time = (float) (((System.nanoTime() - START) / 1e9) % 3600.0);
		return new Matrix4f(
				sunDir.x, sunDir.y, sunDir.z, time,
				day, gold, night, rain,
				0, 0, 0, 0,
				0, coverage(), 0, 0);
	}

	public static Vector4f skyExtra() {
		return new Vector4f(rise, 0, 0, org);
	}

	/** sky colour at the horizon toward the sun (true) or straight away from it (false) */
	public static float[] horizonToward(boolean towardSun) {
		float sl = (float) Math.sqrt(sunDir.x * sunDir.x + sunDir.z * sunDir.z) + 1e-5F;
		float s = towardSun ? 1 : -1;
		float[] c = horizon(new Vector3f(s * sunDir.x / sl, 0, s * sunDir.z / sl));
		return new float[]{Math.min(1, c[0]), Math.min(1, c[1]), Math.min(1, c[2])};
	}

	public static Vector3f sunDirection() {
		return sunDir;
	}

	/** for tests: show the rainbow now */
	public static void forceRainbow() {
		rainbowStart = System.currentTimeMillis() - 30_000;
	}

	/** cloud amount: the setting plus today's variation */
	public static float coverage() {
		return SkySettings.cloudCoverage + dailyCoverage;
	}

	public static float flashLevel() {
		return flash;
	}

	public static float dayLevel() {
		return day;
	}

	public static float goldLevel() {
		return gold;
	}

	public static float nightLevel() {
		return night;
	}

	private static float towerStyleIndex() {
		return switch (SkySettings.towerStyle) {
			case "tower" -> 1;
			case "wide" -> 2;
			case "anvil" -> 3;
			default -> 0;
		};
	}

	/** how wet the ground is (rises with rain, dries slowly after) */
	public static float wetness() {
		return wet;
	}

	public static float rainLevel() {
		return rain;
	}

	public static boolean recentlyDrawn() {
		return !broken && System.nanoTime() - lastDrawNanos < 1_000_000_000L;
	}

	public static boolean skyActive() {
		return overworld && SkySettings.cirrusSky;
	}

	/** replaces SkyRenderer#renderSkyDisc; returns true if the Cirrus sky was drawn */
	public static boolean drawSky() {
		overworld = true;
		if (!SkySettings.cirrusSky || state == null || !init()) {
			overworld = SkySettings.cirrusSky && !broken && state != null;
			return false;
		}
		int flags = F_GRADIENT;
		if (SkySettings.stars) flags |= F_STARS;
		if (SkySettings.milkyWay > 0) flags |= F_MILKYWAY;
		if (SkySettings.aurora > 0) flags |= F_AURORA;
		if (SkySettings.shootingStars) flags |= F_SHOOTING;
		if (SkySettings.sunRays) flags |= F_RAYS;
		if (SkySettings.horizonMountains) flags |= 256;
		boolean ok = draw(skyPipeline, flags, 0.0F, "SvregSky sky");
		if (ok) {
			lastDrawNanos = System.nanoTime();
		}
		return ok;
	}

	/** after the sun, moon (and stars): the painted clouds */
	public static void drawClouds() {
		if (!SkySettings.paintedClouds || SkySettings.cloud3dFull() || !overworld || state == null || !init()) {
			return;
		}
		boolean ghibli = SkySettings.cloudGhibli();
		int f = F_CLOUDS | (SkySettings.cloudGodRays && !ghibli ? 128 : 0) | (SkySettings.horizonMountains ? 256 : 0) | (towerAmt > 0.01F ? 512 : 0);
		if (megaToday) f |= 1024;
		if (SkySettings.on("sun_beams")) f |= 2048;
		if (SkySettings.on("tower_lightning")) f |= 4096;
		if (SkySettings.on("cloud_rows")) f |= 8192;
		if (SkySettings.on("cirrus_layer")) f |= 16384;
		if (ghibli) {
			if (!SkySettings.ghibliMix()) f |= 32768; // the 3D clouds replace the painted cumulus (the mix keeps the far painted layers)
			if (SkySettings.on("light_rays") && GhibliClouds.gridVersion() > 0) f |= 65536;
		}
		draw(cloudPipeline, f, 1.0F, "SvregSky clouds");
	}

	private static boolean draw(RenderPipeline pipeline, int flags, float mode, String label) {
		try {
			double px = 0, pz = 0;
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null) {
				px = mc.player.getX() / 400.0;
				pz = mc.player.getZ() / 400.0;
			}
			float offX = (float) (((windX + px) % 40.0 + 40.0) % 40.0);
			float offZ = (float) (((windZ + pz) % 40.0 + 40.0) % 40.0);
			float time = (float) (((System.nanoTime() - START) / 1e9) % 3600.0);

			float slotC = SkySettings.shootingStarPeriod;
			if (mode > 0.5F && SkySettings.cloudGhibli() && mc.player != null) {
				// light rays: camera position in footprint texels, cloud base height above the camera
				net.minecraft.world.phys.Vec3 eye = mc.player.getEyePosition();
				offX = (float) (((eye.x - GhibliClouds.windX()) / GhibliClouds.CELL) % GhibliClouds.NX);
				offZ = (float) (((eye.z - GhibliClouds.windZ()) / GhibliClouds.CELL) % GhibliClouds.NZ);
				slotC = (float) (GhibliClouds.baseY() - eye.y);
			}
			Matrix4f params = new Matrix4f(
					sunDir.x, sunDir.y, sunDir.z, time,
					day, gold, night, rain,
					offX, offZ, rainbowLevel, SkySettings.aurora,
					flags, coverage(), slotC, mode);
			// in 3D cloud mode the near painted layer is skipped (negative layer count): the 3D clouds take its place
			float layers = SkySettings.cloud3d() || SkySettings.ghibliMix() ? -Math.max(2, SkySettings.cloudLayers) : SkySettings.cloudLayers;
			Vector4f extra = new Vector4f(rise, SkySettings.milkyWay, layers, org);
			// a big cube (far inside the far plane) so the walking bob's small camera shift can't make the sky wobble
			int chunks = Math.max(2, mc.options.getEffectiveRenderDistance());
			float scale = Math.max(3.0F, Math.min(48.0F, chunks * 16 * 0.9F / 10.0F));
			Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrixCopy()).setTranslation(0, 0, 0).scale(scale);
			var transforms = RenderSystem.getDynamicUniforms().writeTransform(modelView, extra,
					mode > 0.5F ? new Vector3f(flash, Math.min(towerAmt, 1.0F), towerSeed) : new Vector3f(flash, moonPhase, SkySettings.on("ghibli_sun_moon") ? 1 : 0), params);

			AbstractTexture tex = mc.getTextureManager().getTexture(SkySettings.cloudTexture);
			AbstractTexture towerTex = mc.getTextureManager().getTexture(TOWERS);
			RenderTarget target = mc.gameRenderer.mainRenderTarget();
			GpuTextureView color = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : target.getColorTextureView();
			GpuTextureView depth = target.useDepth ? (RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : target.getDepthTextureView()) : null;

			RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
			GpuBuffer indexBuffer = indices.getBuffer(36);
			IndexType indexType = indices.type();
			try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> label, color, Optional.empty(), depth, OptionalDouble.empty())) {
				pass.setPipeline(pipeline);
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("DynamicTransforms", transforms);
				pass.bindTexture("Sampler0", tex.getTextureView(), tex.getSampler());
				pass.bindTexture("Sampler1", towerTex.getTextureView(), towerTex.getSampler());
				AbstractTexture foot = footprint(tex);
				pass.bindTexture("Sampler2", foot.getTextureView(), foot.getSampler());
				pass.setVertexBuffer(0, cube.slice());
				pass.setIndexBuffer(indexBuffer, indexType);
				pass.drawIndexed(36, 1, 0, 0, 0);
			}
			return true;
		} catch (Throwable t) {
			broken = true;
			SvregSky.LOG.error("SvregSky: drawing the Cirrus sky failed, falling back to the vanilla sky", t);
			return false;
		}
	}

	// ------------------------------------------------------------------ fog colour (CPU copy of the shader's horizon colour)
	private static float[] mix(float[] a, float[] b, float t) {
		return new float[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
	}

	/** the sky colour right at the horizon in the direction the camera looks */
	private static float[] horizon(Vector3fc look) {
		float lx = look.x(), lz = look.z();
		float ll = (float) Math.sqrt(lx * lx + lz * lz) + 1e-5F;
		lx /= ll;
		lz /= ll;
		float sl = (float) Math.sqrt(sunDir.x * sunDir.x + sunDir.z * sunDir.z) + 1e-5F;
		float side = (float) Math.pow(Math.max(0, Math.min(1, 0.5F + 0.5F * (lx * sunDir.x + lz * sunDir.z) / sl)), 1.4);
		float[] dayC = {0.80F, 0.96F, 0.99F};
		float[] nightC = {0.22F, 0.20F, 0.66F};
		float[] hz = mix(new float[]{0.97F, 0.55F, 0.16F}, new float[]{1.00F, 0.68F, 0.32F}, rise);
		float[] ev = mix(new float[]{0.78F, 0.34F, 0.46F}, hz, side);
		ev = mix(ev, mix(new float[]{0.95F, 0.62F, 0.14F}, new float[]{1.00F, 0.86F, 0.42F}, side), org);
		float[] c = mix(mix(nightC, dayC, day), ev, gold);
		float sd = Math.max(0, lx * sunDir.x + lz * sunDir.z);
		float glow = (float) (Math.pow(sd, 12) * 0.55 + Math.pow(sd, 90) * 0.6) * gold * (1 - rain);
		c[0] += 1.0F * glow;
		c[1] += 0.72F * glow;
		c[2] += 0.38F * glow;
		float lum = c[0] * 0.30F + c[1] * 0.59F + c[2] * 0.11F;
		c = mix(c, new float[]{lum * 0.92F, lum * 0.95F, lum * 1.02F}, rain * 0.8F);
		return c;
	}

	/** blends the vanilla fog colour toward the Cirrus horizon so distant terrain melts into the sky */
	public static int tintFog(int vanilla, Vector3fc look) {
		if (!SkySettings.cirrusSky || SkySettings.fogTint <= 0 || broken || System.nanoTime() - lastDrawNanos > 1_000_000_000L) {
			return vanilla;
		}
		float[] h = horizon(look);
		float amount = SkySettings.fogTint * (0.5F + 0.5F * Math.max(day, gold));
		float r = ARGB.redFloat(vanilla), g = ARGB.greenFloat(vanilla), b = ARGB.blueFloat(vanilla);
		return ARGB.colorFromFloat(1.0F,
				Math.min(1, r + (h[0] - r) * amount),
				Math.min(1, g + (h[1] - g) * amount),
				Math.min(1, b + (h[2] - b) * amount));
	}
}
