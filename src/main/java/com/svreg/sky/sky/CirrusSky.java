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
	private static float day = 1, gold, night, rain, rise;

	private CirrusSky() {
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
				.withBindGroupLayout(BindGroupLayouts.SAMPLER0)
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
		overworld = false;
		float a = skyState.sunAngle;
		sunDir.set(-(float) Math.sin(a), (float) Math.cos(a), 0);
		float sh = sunDir.y;
		day = smooth(-0.10F, 0.30F, sh);
		gold = 1 - smooth(0.0F, 0.35F, Math.abs(sh - 0.02F));
		night = 1 - smooth(-0.22F, 0.02F, sh);
		rain = Math.max(0, Math.min(1, 1 - skyState.rainBrightness));
		rise = smooth(-0.2F, 0.2F, sunDir.x);

		long now = System.nanoTime();
		double dt = lastFrameNanos == 0 ? 0 : Math.min(0.25, (now - lastFrameNanos) / 1e9);
		lastFrameNanos = now;
		double speed = 0.02 * SkySettings.cloudSpeed;
		windX = (windX + dt * speed) % 40.0;
		windZ = (windZ + dt * speed * 0.33) % 40.0;
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
		boolean ok = draw(skyPipeline, flags, 0.0F, "SvregSky sky");
		if (ok) {
			lastDrawNanos = System.nanoTime();
		}
		return ok;
	}

	/** after the sun, moon (and stars): the painted clouds */
	public static void drawClouds() {
		if (!SkySettings.paintedClouds || !overworld || state == null || !init()) {
			return;
		}
		draw(cloudPipeline, F_CLOUDS, 1.0F, "SvregSky clouds");
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

			Matrix4f params = new Matrix4f(
					sunDir.x, sunDir.y, sunDir.z, time,
					day, gold, night, rain,
					offX, offZ, 0.0F, SkySettings.aurora,
					flags, SkySettings.cloudCoverage, SkySettings.shootingStarPeriod, mode);
			Vector4f extra = new Vector4f(rise, SkySettings.milkyWay, SkySettings.cloudLayers, 1.0F);
			Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrixCopy()).setTranslation(0, 0, 0);
			var transforms = RenderSystem.getDynamicUniforms().writeTransform(modelView, extra, new Vector3f(), params);

			AbstractTexture tex = mc.getTextureManager().getTexture(SkySettings.cloudTexture);
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
