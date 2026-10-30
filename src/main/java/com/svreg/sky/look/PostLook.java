package com.svreg.sky.look;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.svreg.sky.SvregSky;
import com.svreg.sky.sky.CirrusSky;
import com.svreg.sky.sky.SkySettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Optional;
import java.util.OptionalDouble;

/** Screen look over the world: time-of-day film tint, a soft vignette, and (optional) thin cartoon outlines from depth edges. */
public final class PostLook {
	private static final Identifier SHADER = Identifier.fromNamespaceAndPath(SvregSky.MOD_ID, "core/cirrus_post");
	private static RenderPipeline pipeline, copyPipeline, glowPipeline;
	private static GpuBuffer quad;
	private static boolean broken, glowBroken, logged;
	private static com.mojang.blaze3d.textures.GpuTexture sceneTex;
	private static com.mojang.blaze3d.textures.GpuTextureView sceneView;

	private PostLook() {
	}

	private static void init() {
		if (pipeline == null) {
			pipeline = RenderPipeline.builder(RenderPipeline.builder().withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION).buildSnippet())
					.withLocation(Identifier.fromNamespaceAndPath(SvregSky.MOD_ID, "pipeline/cirrus_post"))
					.withVertexShader(SHADER)
					.withFragmentShader(SHADER)
					.withVertexBinding(0, DefaultVertexFormat.POSITION)
					.withPrimitiveTopology(PrimitiveTopology.QUADS)
					.withCull(false)
					.withDepthStencilState(Optional.empty())
					.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
					.withBindGroupLayout(BindGroupLayouts.SAMPLER0)
					.build();
		}
		if (quad == null) {
			try (ByteBufferBuilder bytes = new ByteBufferBuilder(DefaultVertexFormat.POSITION.getVertexSize() * 4)) {
				BufferBuilder b = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION);
				b.addVertex(-1, -1, 0);
				b.addVertex(1, -1, 0);
				b.addVertex(1, 1, 0);
				b.addVertex(-1, 1, 0);
				try (MeshData mesh = b.buildOrThrow()) {
					quad = RenderSystem.getDevice().createBuffer(() -> "SvregSky post quad", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
				}
			}
		}
	}

	/** depth textures are not filterable on GLES (phones): a LINEAR sampler makes them read as 0 there, so always sample them NEAREST */
	private static com.mojang.blaze3d.textures.GpuSampler depthSampler() {
		return RenderSystem.getSamplerCache().getClampToEdge(com.mojang.blaze3d.textures.FilterMode.NEAREST);
	}

	private static int fixedDepthId = -1;

	/**
	 * Phones (GLES through MobileGlues) ignore sampler objects, so the depth texture uses its own filter, which defaults to
	 * LINEAR / mipmapped: a float depth texture with linear filtering is incomplete on GLES and every read returns 0.
	 * Set NEAREST on the texture itself once (desktop is unaffected, the sampler object wins there).
	 */
	private static void fixDepthFilter(RenderTarget main) {
		try {
			if (main.getDepthTexture() instanceof com.mojang.blaze3d.opengl.GlTexture gt && gt.glId() != fixedDepthId) {
				int prev = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
				com.mojang.blaze3d.opengl.GlStateManager._bindTexture(gt.glId());
				com.mojang.blaze3d.opengl.GlStateManager._texParameter(3553, 10241, 9728); // min filter: nearest
				com.mojang.blaze3d.opengl.GlStateManager._texParameter(3553, 10240, 9728); // mag filter: nearest
				com.mojang.blaze3d.opengl.GlStateManager._texParameter(3553, 34892, 0);    // no depth compare
				com.mojang.blaze3d.opengl.GlStateManager._bindTexture(prev);
				fixedDepthId = gt.glId();
				SvregSky.LOG.info("SvregSky: depth texture {} set to nearest filtering", fixedDepthId);
			}
		} catch (Throwable t) {
			fixedDepthId = -2;
			SvregSky.LOG.warn("SvregSky: could not set depth filtering", t);
		}
	}

	private static RenderPipeline simple(String name, ColorTargetState color, com.mojang.blaze3d.pipeline.BindGroupLayout samplers) {
		Identifier sh = Identifier.fromNamespaceAndPath(SvregSky.MOD_ID, "core/" + name);
		return RenderPipeline.builder(RenderPipeline.builder().withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION).buildSnippet())
				.withLocation(Identifier.fromNamespaceAndPath(SvregSky.MOD_ID, "pipeline/" + name))
				.withVertexShader(sh)
				.withFragmentShader(sh)
				.withVertexBinding(0, DefaultVertexFormat.POSITION)
				.withPrimitiveTopology(PrimitiveTopology.QUADS)
				.withCull(false)
				.withDepthStencilState(Optional.empty())
				.withColorTargetState(color)
				.withBindGroupLayout(samplers)
				.build();
	}

	/** soft glow (bloom) and photo-mode depth of field: copy the scene, then redraw it with the effect */
	private static void glow(Minecraft mc, RenderTarget main, AbstractTexture any, float bloom, float dof) {
		if (glowBroken) {
			return;
		}
		try {
			if (copyPipeline == null) {
				copyPipeline = simple("cirrus_copy", ColorTargetState.DEFAULT, BindGroupLayouts.SAMPLER0);
				glowPipeline = simple("cirrus_glow", ColorTargetState.DEFAULT, BindGroupLayouts.SAMPLER0_SAMPLER1);
			}
			int w = main.width, h = main.height;
			if (sceneTex == null || sceneTex.getWidth(0) != w || sceneTex.getHeight(0) != h) {
				if (sceneView != null) sceneView.close();
				if (sceneTex != null) sceneTex.close();
				sceneTex = RenderSystem.getDevice().createTexture(() -> "SvregSky scene copy", 15, main.getColorTexture().getFormat(), w, h, 1, 1);
				sceneView = RenderSystem.getDevice().createTextureView(sceneTex);
			}
			Matrix4f params = new Matrix4f(
					0, 0, 0, 0,
					0, 0, 0, 0,
					bloom, dof, 0.004F, 0,
					0, 0, 0, 0);
			var transforms = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(), new Vector4f(1, 1, 1, 1), new Vector3f(), params);
			RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
			GpuBuffer ib = indices.getBuffer(6);
			IndexType type = indices.type();
			try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "SvregSky scene copy", sceneView, Optional.empty(), null, OptionalDouble.empty())) {
				pass.setPipeline(copyPipeline);
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("DynamicTransforms", transforms);
				pass.bindTexture("Sampler0", main.getColorTextureView(), any.getSampler());
				pass.setVertexBuffer(0, quad.slice());
				pass.setIndexBuffer(ib, type);
				pass.drawIndexed(6, 1, 0, 0, 0);
			}
			try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "SvregSky glow", main.getColorTextureView(), Optional.empty(), null, OptionalDouble.empty())) {
				pass.setPipeline(glowPipeline);
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("DynamicTransforms", transforms);
				pass.bindTexture("Sampler0", sceneView, any.getSampler());
				pass.bindTexture("Sampler1", main.getDepthTextureView(), depthSampler());
				pass.setVertexBuffer(0, quad.slice());
				pass.setIndexBuffer(ib, type);
				pass.drawIndexed(6, 1, 0, 0, 0);
			}
		} catch (Throwable t) {
			glowBroken = true;
			SvregSky.LOG.error("SvregSky: soft glow / depth of field failed, turning it off", t);
		}
	}

	/** raindrops on the "lens": out in the rain under open sky, not when looking down at your feet */
	private static float rainOnLens() {
		if (!SkySettings.on("rain_drops")) {
			return 0;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null || mc.options.getCameraType() != net.minecraft.client.CameraType.FIRST_PERSON) {
			return 0;
		}
		float rain = CirrusSky.rainLevel();
		if (rain < 0.2F || !mc.level.canSeeSky(net.minecraft.core.BlockPos.containing(mc.player.getEyePosition()))) {
			return 0;
		}
		float pitch = mc.player.getXRot(); // negative = looking up
		float t = Math.max(0, Math.min(1, (60 - pitch) / 70F));
		return rain * t;
	}

	public static void render() {
		boolean tone = SkySettings.on("film_tone"), outline = SkySettings.on("cartoon_outline"), paper = SkySettings.on("paper_texture");
		boolean bloom = SkySettings.on("bloom"), dof = SkySettings.photoMode && SkySettings.on("photo_dof");
		Vector3f sunW = CirrusSky.sunDirection();
		float shadowK = SkySettings.on("screen_shadows") && !"world".equals(SkySettings.shadowMethod) && sunW.y > 0.04F ? SkySettings.shadowDarkness * Math.min(1, CirrusSky.dayLevel() + CirrusSky.goldLevel()) * (1 - CirrusSky.rainLevel()) : 0;
		boolean shDebug = SkySettings.on("screen_shadows") && SkySettings.on("shadow_debug") && "screen".equals(SkySettings.shadowMethod);
		float rainK = rainOnLens();
		if (broken || (!tone && !outline && !paper && !bloom && !dof && shadowK <= 0 && !shDebug && rainK <= 0)) {
			return;
		}
		try {
			init();
			if (!logged) {
				logged = true;
				SvregSky.LOG.info("SvregSky: screen look on {} (depth zero-to-one: {})", RenderSystem.getDevice().getDeviceInfo(), RenderSystem.getDevice().getDeviceInfo().isZZeroToOne());
			}
			Minecraft mc = Minecraft.getInstance();
			float day = CirrusSky.dayLevel(), gold = CirrusSky.goldLevel(), night = CirrusSky.nightLevel();
			boolean morning = CirrusSky.sunDirection().x > 0;
			// film tint: pale gold mornings, warm evenings, blue nights
			float r = 1, g = 1, bl = 1, a = 0;
			if (tone) {
				if (gold > 0.05F) {
					if (morning) { r = 1.0F; g = 0.93F; bl = 0.80F; } else { r = 1.0F; g = 0.72F; bl = 0.45F; }
					a = 0.07F * gold;
				}
				if (night > 0.05F) {
					float k = night * 0.10F;
					r = (r * a + 0.25F * k) / (a + k); g = (g * a + 0.35F * k) / (a + k); bl = (bl * a + 0.75F * k) / (a + k);
					a = a + k;
				}
			}
			// shadow quality: march steps, reach in blocks, step growth
			float shSteps = 16, shRange = 64, shGrow = 1.25F;
			switch (SkySettings.shadowQuality) {
				case "low" -> { shSteps = 10; shRange = 40; shGrow = 1.35F; }
				case "high" -> { shSteps = 22; shRange = 96; shGrow = 1.2F; }
				case "ultra" -> { shSteps = 30; shRange = 128; shGrow = 1.15F; }
				default -> { }
			}
			float shColor = switch (SkySettings.shadowColor) { case "neutral" -> 1; case "warm" -> 2; default -> 0; };
			Matrix4f params = new Matrix4f(
					r, g, bl, a,
					tone ? 0.32F : 0, outline ? 0.65F : 0, 0.0012F, paper ? 1 : 0,
					shSteps, shRange, shGrow, shColor,
					shDebug ? 1 : 0, rainK, (float) ((System.nanoTime() / 1e9) % 3600.0), 0);
			// the sun in view space (the model-view here is the camera rotation)
			Vector3f sunView = new Matrix4f(RenderSystem.getModelViewMatrixCopy()).transformDirection(new Vector3f(sunW)).normalize();
			var transforms = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(), new Vector4f(sunView.x, sunView.y, sunView.z, shadowK), new Vector3f(), params);
			RenderTarget main = mc.gameRenderer.mainRenderTarget();
			if (fixedDepthId != -2) {
				fixDepthFilter(main);
			}
			AbstractTexture any = mc.getTextureManager().getTexture(SkySettings.cloudTexture);
			if (bloom || dof) {
				glow(mc, main, any, bloom ? 0.8F : 0, dof ? 1 : 0);
			}
			if (!tone && !outline && !paper && shadowK <= 0 && !shDebug && rainK <= 0) {
				return;
			}
			RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
			GpuBuffer ib = indices.getBuffer(6);
			IndexType type = indices.type();
			try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "SvregSky look", RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : main.getColorTextureView(), Optional.empty(), null, OptionalDouble.empty())) {
				pass.setPipeline(pipeline);
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("DynamicTransforms", transforms);
				pass.bindTexture("Sampler0", main.getDepthTextureView(), depthSampler());
				pass.setVertexBuffer(0, quad.slice());
				pass.setIndexBuffer(ib, type);
				pass.drawIndexed(6, 1, 0, 0, 0);
			}
		} catch (Throwable t) {
			broken = true;
			SvregSky.LOG.error("SvregSky: screen look failed, turning it off", t);
		}
	}
}
