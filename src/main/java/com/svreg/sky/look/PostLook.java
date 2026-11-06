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
	private static RenderPipeline pipeline, copyPipeline, glowPipeline, paintPipeline, animePipeline, pixelPipeline;
	private static GpuBuffer quad;
	private static boolean broken, glowBroken, paintBroken, animeBroken, pixelBroken, logged;
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

	/** the scene colour copied into sceneTex (sized to the main target) */
	private static void copyScene(RenderTarget main, AbstractTexture any, com.mojang.blaze3d.buffers.GpuBufferSlice transforms) {
		if (copyPipeline == null) {
			copyPipeline = simple("cirrus_copy", ColorTargetState.DEFAULT, BindGroupLayouts.SAMPLER0);
		}
		int w = main.width, h = main.height;
		if (sceneTex == null || sceneTex.getWidth(0) != w || sceneTex.getHeight(0) != h) {
			if (sceneView != null) sceneView.close();
			if (sceneTex != null) sceneTex.close();
			sceneTex = RenderSystem.getDevice().createTexture(() -> "SvregSky scene copy", 15, main.getColorTexture().getFormat(), w, h, 1, 1);
			sceneView = RenderSystem.getDevice().createTextureView(sceneTex);
		}
		RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "SvregSky scene copy", sceneView, Optional.empty(), null, OptionalDouble.empty())) {
			pass.setPipeline(copyPipeline);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DynamicTransforms", transforms);
			pass.bindTexture("Sampler0", main.getColorTextureView(), any.getSampler());
			pass.setVertexBuffer(0, quad.slice());
			pass.setIndexBuffer(indices.getBuffer(6), indices.type());
			pass.drawIndexed(6, 1, 0, 0, 0);
		}
	}

	/** painterly filter: a soft Kuwahara over a copy of the scene (colour only, so it works on phones) */
	private static void paint(RenderTarget main, AbstractTexture any, float strength) {
		if (paintBroken) {
			return;
		}
		try {
			if (paintPipeline == null) {
				paintPipeline = simple("cirrus_paint", ColorTargetState.DEFAULT, BindGroupLayouts.SAMPLER0);
			}
			Matrix4f params = new Matrix4f(
					0, 0, 0, 0,
					0, 0, 0, 0,
					strength, (float) ((System.nanoTime() / 1e9) % 3600.0), 0, 0,
					0, 0, 0, 0);
			var transforms = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(), new Vector4f(1, 1, 1, 1), new Vector3f(), params);
			copyScene(main, any, transforms);
			RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
			try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "SvregSky painterly", main.getColorTextureView(), Optional.empty(), null, OptionalDouble.empty())) {
				pass.setPipeline(paintPipeline);
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("DynamicTransforms", transforms);
				pass.bindTexture("Sampler0", sceneView, RenderSystem.getSamplerCache().getClampToEdge(com.mojang.blaze3d.textures.FilterMode.LINEAR));
				pass.setVertexBuffer(0, quad.slice());
				pass.setIndexBuffer(indices.getBuffer(6), indices.type());
				pass.drawIndexed(6, 1, 0, 0, 0);
			}
		} catch (Throwable t) {
			paintBroken = true;
			SvregSky.LOG.error("SvregSky: painterly filter failed, turning it off", t);
		}
	}

	/** anime look over a copy of the scene: coloured shadows, dream glow, colour-edge outline (colour only, phone-safe) */
	private static void anime(RenderTarget main, AbstractTexture any, boolean shadows, boolean outline, boolean dream) {
		if (animeBroken) {
			return;
		}
		try {
			if (animePipeline == null) {
				animePipeline = simple("cirrus_anime", ColorTargetState.DEFAULT, BindGroupLayouts.SAMPLER0);
			}
			// shadow colour by the time of day: sky blue by day, violet at golden hour, deep blue at night
			float day = CirrusSky.dayLevel(), gold = CirrusSky.goldLevel(), night = CirrusSky.nightLevel();
			float sum = Math.max(1e-3F, day + gold + night);
			float tr = (0.80F * day + 0.94F * gold + 0.70F * night) / sum;
			float tg = (0.88F * day + 0.78F * gold + 0.80F * night) / sum;
			float tb = (1.18F * day + 1.16F * gold + 1.26F * night) / sum;
			float[] lc = switch (SkySettings.outlineColor) {
				case "black" -> new float[]{0.06F, 0.05F, 0.07F};
				case "brown" -> new float[]{0.24F, 0.15F, 0.10F};
				case "navy" -> new float[]{0.10F, 0.12F, 0.26F};
				default -> null;
			};
			float time = (float) ((System.nanoTime() / 1e9) % 3600.0);
			Matrix4f params = new Matrix4f(
					tr, tg, tb, shadows ? SkySettings.animeShadow : 0,
					outline ? Math.max(1, Math.min(3, SkySettings.outlineWidth)) : 0, SkySettings.outlineStrength, SkySettings.on("outline_boil") ? 1 : 0, lc != null ? 1 : 0,
					dream ? SkySettings.dreamGlow : 0, time, 0, 0,
					lc != null ? lc[0] : 0, lc != null ? lc[1] : 0, lc != null ? lc[2] : 0, 0);
			var transforms = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(), new Vector4f(1, 1, 1, 1), new Vector3f(), params);
			copyScene(main, any, transforms);
			RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
			try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "SvregSky anime look", main.getColorTextureView(), Optional.empty(), null, OptionalDouble.empty())) {
				pass.setPipeline(animePipeline);
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("DynamicTransforms", transforms);
				pass.bindTexture("Sampler0", sceneView, RenderSystem.getSamplerCache().getClampToEdge(com.mojang.blaze3d.textures.FilterMode.LINEAR));
				pass.setVertexBuffer(0, quad.slice());
				pass.setIndexBuffer(indices.getBuffer(6), indices.type());
				pass.drawIndexed(6, 1, 0, 0, 0);
			}
		} catch (Throwable t) {
			animeBroken = true;
			SvregSky.LOG.error("SvregSky: anime look failed, turning it off", t);
		}
	}

	/** pixel art: big pixels, brightness bands, dithering at the band steps (colour only, phone-safe) */
	private static void pixel(RenderTarget main, AbstractTexture any) {
		if (pixelBroken) {
			return;
		}
		try {
			if (pixelPipeline == null) {
				pixelPipeline = simple("cirrus_pixel", ColorTargetState.DEFAULT, BindGroupLayouts.SAMPLER0);
			}
			float px = Math.max(1, Math.round(SkySettings.pixelSize * Math.max(1, main.height / 720F)));
			Matrix4f params = new Matrix4f(
					px, SkySettings.pixelBands, SkySettings.pixelDither, 0,
					0, 0, 0, 0,
					0, 0, 0, 0,
					0, 0, 0, 0);
			var transforms = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(), new Vector4f(1, 1, 1, 1), new Vector3f(), params);
			copyScene(main, any, transforms);
			RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
			try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "SvregSky pixel art", main.getColorTextureView(), Optional.empty(), null, OptionalDouble.empty())) {
				pass.setPipeline(pixelPipeline);
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("DynamicTransforms", transforms);
				pass.bindTexture("Sampler0", sceneView, RenderSystem.getSamplerCache().getClampToEdge(com.mojang.blaze3d.textures.FilterMode.LINEAR));
				pass.setVertexBuffer(0, quad.slice());
				pass.setIndexBuffer(indices.getBuffer(6), indices.type());
				pass.drawIndexed(6, 1, 0, 0, 0);
			}
		} catch (Throwable t) {
			pixelBroken = true;
			SvregSky.LOG.error("SvregSky: pixel art failed, turning it off", t);
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
		float rain = mc.level.getRainLevel(1.0F);
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
		// cozy home: misted window edges while it rains outside, warm indoor light and firelight
		float mist = Cozy.mist(), cozyIn = Cozy.indoor(), cozyFire = Cozy.fire();
		boolean cozy = mist > 0.01F || cozyIn > 0.01F || cozyFire > 0.01F;
		// sleeping: a pale lavender dream haze, then waking into a soft white-gold glow
		float dreamS = Dream.sleep(), dreamW = Dream.wake();
		cozy |= dreamS > 0.01F || dreamW > 0.01F;
		boolean painterly = SkySettings.on("painterly");
		boolean animeSh = SkySettings.on("colored_shadows"), animeOl = SkySettings.on("anime_outline"), dream = SkySettings.on("dream_glow");
		boolean animeAny = animeSh || animeOl || dream;
		boolean pixelArt = SkySettings.on("pixel_art");
		if (broken || (!tone && !outline && !paper && !bloom && !dof && shadowK <= 0 && !shDebug && rainK <= 0 && !cozy && !painterly && !animeAny && !pixelArt)) {
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
			if (cozy) {
				// indoors a soft warm grade (stronger at night and in the rain); near a fire a flickering amber glow
				float warmIn = cozyIn * (0.04F + 0.05F * Math.max(night, CirrusSky.rainLevel()));
				float warmFire = cozyFire * 0.10F * Cozy.flicker() * (0.5F + 0.5F * Math.max(night, cozyIn));
				float k = warmIn + warmFire;
				if (k > 0.001F) {
					float wr = 1.0F, wg = 0.70F, wb = 0.38F;
					r = (r * a + wr * k) / (a + k); g = (g * a + wg * k) / (a + k); bl = (bl * a + wb * k) / (a + k);
					a = a + k;
				}
			}
			if (dreamS > 0.01F || dreamW > 0.01F) {
				float ks = 0.55F * dreamS, kw = 0.9F * dreamW * dreamW;
				float k = ks + kw;
				float dr = (0.88F * ks + 1.0F * kw) / k, dg = (0.84F * ks + 0.97F * kw) / k, db = (1.0F * ks + 0.88F * kw) / k;
				k = Math.min(0.92F, k);
				// the dream covers whatever tint was there
				r = r * a * (1 - k) + dr * k; g = g * a * (1 - k) + dg * k; bl = bl * a * (1 - k) + db * k;
				float na = a * (1 - k) + k;
				r /= na; g /= na; bl /= na;
				a = na;
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
					shDebug ? 1 : 0, rainK, (float) Twos.step((System.nanoTime() / 1e9) % 3600.0), mist);
			// the sun in view space (the model-view here is the camera rotation)
			Vector3f sunView = new Matrix4f(RenderSystem.getModelViewMatrixCopy()).transformDirection(new Vector3f(sunW)).normalize();
			var transforms = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(), new Vector4f(sunView.x, sunView.y, sunView.z, shadowK), new Vector3f(), params);
			RenderTarget main = mc.gameRenderer.mainRenderTarget();
			if (fixedDepthId != -2) {
				fixDepthFilter(main);
			}
			AbstractTexture any = mc.getTextureManager().getTexture(SkySettings.cloudTexture);
			if (painterly) {
				paint(main, any, Math.max(0.1F, Math.min(1, SkySettings.painterlyStrength)));
			}
			if (animeAny) {
				anime(main, any, animeSh, animeOl, dream);
			}
			if (bloom || dof) {
				glow(mc, main, any, bloom ? 0.8F : 0, dof ? 1 : 0);
			}
			if (tone || outline || paper || shadowK > 0 || shDebug || rainK > 0 || cozy) {
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
			}
			// pixel art goes last, so the whole picture (tint, glow, mist) becomes pixels
			if (pixelArt) {
				pixel(main, any);
			}
		} catch (Throwable t) {
			broken = true;
			SvregSky.LOG.error("SvregSky: screen look failed, turning it off", t);
		}
	}
}
