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
	private static RenderPipeline pipeline;
	private static GpuBuffer quad;
	private static boolean broken;

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

	public static void render() {
		boolean tone = SkySettings.on("film_tone"), outline = SkySettings.on("cartoon_outline");
		if (broken || (!tone && !outline)) {
			return;
		}
		try {
			init();
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
			Matrix4f params = new Matrix4f(
					r, g, bl, a,
					tone ? 0.32F : 0, outline ? 0.65F : 0, 0.0012F, 0,
					0, 0, 0, 0,
					0, 0, 0, 0);
			var transforms = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(), new Vector4f(1, 1, 1, 1), new Vector3f(), params);
			RenderTarget main = mc.gameRenderer.mainRenderTarget();
			AbstractTexture any = mc.getTextureManager().getTexture(SkySettings.cloudTexture);
			RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
			GpuBuffer ib = indices.getBuffer(6);
			IndexType type = indices.type();
			try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "SvregSky look", RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : main.getColorTextureView(), Optional.empty(), null, OptionalDouble.empty())) {
				pass.setPipeline(pipeline);
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("DynamicTransforms", transforms);
				pass.bindTexture("Sampler0", main.getDepthTextureView(), any.getSampler());
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
