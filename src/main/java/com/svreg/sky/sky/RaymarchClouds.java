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
	public static final float LAYER = 140F;

	private static RenderPipeline marchPipeline, compPipeline;
	private static GpuBuffer quad;
	private static GpuTexture buf;
	private static GpuTextureView bufView;
	private static boolean broken;
	private static double windX, windZ;
	private static long lastNanos;
	private static int logs;

	private RaymarchClouds() {
	}

	private static RenderPipeline pipeline(String name, ColorTargetState color, Optional<DepthStencilState> depth, boolean sampler) {
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
		if (sampler) {
			b = b.withBindGroupLayout(BindGroupLayouts.SAMPLER0);
		}
		return b.build();
	}

	private static void init() {
		if (marchPipeline == null) {
			marchPipeline = pipeline("cirrus_raymarch", ColorTargetState.DEFAULT, Optional.empty(), false);
			compPipeline = pipeline("cirrus_rmcomp", ColorTargetState.DEFAULT, Optional.of(DepthStencilState.DEFAULT), true);
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
			int scale = Math.max(2, Math.min(4, SkySettings.raymarchScale));
			int w = Math.max(1, (target.width + scale - 1) / scale), h = Math.max(1, (target.height + scale - 1) / scale);
			if (buf == null || buf.getWidth(0) != w || buf.getHeight(0) != h) {
				if (bufView != null) {
					bufView.close();
				}
				if (buf != null) {
					buf.close();
				}
				buf = RenderSystem.getDevice().createTexture(() -> "SvregSky raymarch clouds", 15, target.getColorTexture().getFormat(), w, h, 1, 1);
				bufView = RenderSystem.getDevice().createTextureView(buf);
				if (logs++ < 2) {
					SvregSky.LOG.info("SvregSky: raymarch clouds buffer {}x{} (1/{})", w, h, scale);
				}
			}

			float reach = Math.max(600, Math.min(3000, SkySettings.cloud3dDistance * 3 * 4.0F * 1.4F));
			float[] hzSun = CirrusSky.horizonToward(true);
			float[] hzAway = CirrusSky.horizonToward(false);
			Matrix4f params = CirrusSky.skyParams();
			params.m03(LAYER);
			params.m20(hzAway[0]);
			params.m21(hzAway[1]);
			params.m22(hzAway[2]);
			params.m23(Math.max(0.25F, Math.min(2.5F, SkySettings.ghibliAmount)));
			params.m30(hzSun[0]);
			params.m32(hzSun[1]);
			params.m33(hzSun[2]);
			float org = CirrusSky.skyExtra().w;
			Vector3f camCloud = new Vector3f((float) (cam.x - windX), (float) (cam.y - bottomY), (float) (cam.z - windZ));
			Matrix4f view = new Matrix4f(RenderSystem.getModelViewMatrixCopy());
			var marchT = RenderSystem.getDynamicUniforms().writeTransform(view, new Vector4f(params.m03(), reach, 0, org), camCloud, params);
			boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
			var compT = RenderSystem.getDynamicUniforms().writeTransform(view, new Vector4f(scale, zeroToOne ? 1 : 0, 0, 0), new Vector3f(), new Matrix4f());

			RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
			GpuBuffer ib = indices.getBuffer(6);
			IndexType type = indices.type();
			try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "SvregSky raymarch clouds", bufView, Optional.of(new Vector4f(0, 0, 0, 0)), null, OptionalDouble.empty())) {
				pass.setPipeline(marchPipeline);
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("DynamicTransforms", marchT);
				pass.setVertexBuffer(0, quad.slice());
				pass.setIndexBuffer(ib, type);
				pass.drawIndexed(6, 1, 0, 0, 0);
			}
			try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "SvregSky raymarch composite",
					target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
				pass.setPipeline(compPipeline);
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("DynamicTransforms", compT);
				pass.bindTexture("Sampler0", bufView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
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
