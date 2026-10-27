package com.svreg.sky.sky;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
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
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.InputStream;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Real 3D Cirrus clouds: stepped voxel cumulus built from the same density map as the painted clouds.
 * A mesh of the cells around the camera is rebuilt whenever the camera or the wind moves by a whole cell;
 * colours are computed in cirrus_clouds3d.fsh from the sky state, so they follow day, golden hour and night.
 */
public final class CloudVoxels {
	private static final Identifier SHADER = Identifier.fromNamespaceAndPath(SvregSky.MOD_ID, "core/cirrus_clouds3d");
	private static final float THRESHOLD = 0.545F;
	private static final float LEVEL_STEP = 0.035F;

	/** shape settings of the two 3D modes: hybrid (stepped cumulus + painted far layers) and full (flat sunset slabs to the horizon) */
	private record Style(float cell, int radius, int levels, float levelHeight, float texX, float texZ, float threshold, float levelStep) {
	}

	private static Style style() {
		if (SkySettings.cloud3dFull()) {
			// like the sunset reference: many small, thin, stretched slabs reaching the horizon
			return new Style(Math.max(3, SkySettings.cloud3dCell - 1), Math.min(140, SkySettings.cloud3dDistance * 3), Math.min(2, SkySettings.cloud3dLevels),
					3.0F, 10.0F, 4.0F, 0.515F, 0.06F);
		}
		return new Style(SkySettings.cloud3dCell, SkySettings.cloud3dDistance, SkySettings.cloud3dLevels, 4.0F, 7.0F, 5.0F, THRESHOLD, LEVEL_STEP);
	}

	private static Style built;

	private static RenderPipeline pipeline;
	private static boolean broken;

	private static float[] density;
	private static int texW, texH;

	private static GpuBuffer mesh;
	private static int quadCount;
	private static int builtCx = Integer.MIN_VALUE, builtCz, builtVersion = -1;
	private static float builtCov;

	private static double windX, windZ;
	private static int logs;
	private static long lastNanos;

	private CloudVoxels() {
	}

	/** reads the density map on resource reload (red channel = density) */
	public static void reload(ResourceManager manager) {
		try (InputStream in = manager.open(SkySettings.cloudTexture); NativeImage img = NativeImage.read(in)) {
			int w = img.getWidth(), h = img.getHeight();
			float[] d = new float[w * h];
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					d[x + y * w] = (img.getPixel(x, y) >> 16 & 0xFF) / 255.0F;
				}
			}
			density = d;
			texW = w;
			texH = h;
			builtVersion = -1;
		} catch (Exception e) {
			SvregSky.LOG.warn("SvregSky: 3D clouds could not read {} ({})", SkySettings.cloudTexture, e.toString());
			density = null;
		}
	}

	private static int level(Style st, int cx, int cz, float cov) {
		int tx = Math.floorMod((int) Math.floor(cx * st.texX()), texW);
		int tz = Math.floorMod((int) Math.floor(cz * st.texZ()), texH);
		float d = density[tx + tz * texW];
		if (d < cov) {
			return 0;
		}
		return Math.min(st.levels(), 1 + (int) ((d - cov) / st.levelStep()));
	}

	private static RenderPipeline pipeline() {
		if (pipeline == null) {
			pipeline = RenderPipeline.builder(RenderPipeline.builder().withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION).buildSnippet())
					.withLocation(Identifier.fromNamespaceAndPath(SvregSky.MOD_ID, "pipeline/cirrus_clouds3d"))
					.withVertexShader(SHADER)
					.withFragmentShader(SHADER)
					.withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
					.withPrimitiveTopology(PrimitiveTopology.QUADS)
					.withCull(false)
					.withDepthStencilState(DepthStencilState.DEFAULT)
					.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
					.build();
		}
		return pipeline;
	}

	private static void face(BufferBuilder b, int face, float g, float... v) {
		int r = face * 40;
		int gi = Math.round(g * 255);
		for (int i = 0; i < 12; i += 3) {
			b.addVertex(v[i], v[i + 1], v[i + 2]).setColor(r, gi, 0, 255);
		}
	}

	/** builds the cells within the radius around (ccx, ccz); vertex positions are relative to that cell's corner at the cloud base */
	private static void build(Style st, int ccx, int ccz, float cov) {
		int radius = st.radius();
		int maxLv = st.levels();
		float cell = st.cell();
		float levelHeight = st.levelHeight();
		int side = radius * 2 + 1;
		int[] lv = new int[(side + 2) * (side + 2)];
		int stride = side + 2;
		for (int z = -radius - 1; z <= radius + 1; z++) {
			for (int x = -radius - 1; x <= radius + 1; x++) {
				lv[(x + radius + 1) + (z + radius + 1) * stride] = level(st, ccx + x, ccz + z, cov);
			}
		}
		int r2 = radius * radius;
		int quads = 0;
		try (ByteBufferBuilder bytes = new ByteBufferBuilder(DefaultVertexFormat.POSITION_COLOR.getVertexSize() * 4 * 4096)) {
			BufferBuilder b = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_COLOR);
			for (int z = -radius; z <= radius; z++) {
				for (int x = -radius; x <= radius; x++) {
					if (x * x + z * z > r2) {
						continue;
					}
					int l = lv[(x + radius + 1) + (z + radius + 1) * stride];
					if (l == 0) {
						continue;
					}
					float x0 = x * cell, x1 = x0 + cell, z0 = z * cell, z1 = z0 + cell;
					float top = l * levelHeight;
					float g = l / (float) maxLv;
					face(b, 0, g, x0, top, z0, x0, top, z1, x1, top, z1, x1, top, z0);
					face(b, 1, g, x0, 0, z0, x1, 0, z0, x1, 0, z1, x0, 0, z1);
					quads += 2;
					int ln = lv[(x + 1 + radius + 1) + (z + radius + 1) * stride];
					if (ln < l) {
						float y0 = ln * levelHeight;
						face(b, 2, g, x1, y0, z0, x1, top, z0, x1, top, z1, x1, y0, z1);
						quads++;
					}
					ln = lv[(x - 1 + radius + 1) + (z + radius + 1) * stride];
					if (ln < l) {
						float y0 = ln * levelHeight;
						face(b, 3, g, x0, y0, z0, x0, y0, z1, x0, top, z1, x0, top, z0);
						quads++;
					}
					ln = lv[(x + radius + 1) + (z + 1 + radius + 1) * stride];
					if (ln < l) {
						float y0 = ln * levelHeight;
						face(b, 4, g, x0, y0, z1, x1, y0, z1, x1, top, z1, x0, top, z1);
						quads++;
					}
					ln = lv[(x + radius + 1) + (z - 1 + radius + 1) * stride];
					if (ln < l) {
						float y0 = ln * levelHeight;
						face(b, 5, g, x0, y0, z0, x0, top, z0, x1, top, z0, x1, y0, z0);
						quads++;
					}
				}
			}
			if (mesh != null) {
				mesh.close();
				mesh = null;
			}
			quadCount = 0;
			try (MeshData data = b.build()) {
				if (data != null) {
					mesh = RenderSystem.getDevice().createBuffer(() -> "SvregSky 3D clouds", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_VERTEX, data.vertexBuffer());
					quadCount = quads;
				}
			}
		}
	}

	/** replaces the vanilla cloud render in "3d" cloud mode */
	public static void render(float bottomY, Vec3 cam) {
		if (broken || density == null) {
			return;
		}
		try {
			long now = System.nanoTime();
			double dt = lastNanos == 0 ? 0 : Math.min(0.25, (now - lastNanos) / 1e9);
			lastNanos = now;
			Style st = style();
			float cell = st.cell();
			double speed = 1.6 * SkySettings.cloudSpeed; // blocks per second
			double periodX = (double) texW * cell, periodZ = (double) texH * cell; // the map repeats after this many cells
			windX = (windX + dt * speed) % periodX;
			windZ = (windZ + dt * speed * 0.33) % periodZ;

			// cloud space = world shifted back by the wind; the mesh is rebuilt only after moving a few cells
			double sx = cam.x - windX, sz = cam.z - windZ;
			int ccx = (int) Math.floor(sx / cell), ccz = (int) Math.floor(sz / cell);
			float cov = st.threshold() - SkySettings.cloudCoverage - 0.12F * CirrusSky.rainLevel();
			int step = Math.max(1, st.radius() / 12);
			if (!st.equals(built) || Math.abs(ccx - builtCx) >= step || Math.abs(ccz - builtCz) >= step
					|| builtVersion != SkySettings.version || Math.abs(cov - builtCov) > 0.01F) {
				build(st, ccx, ccz, cov);
				built = st;
				builtCx = ccx;
				builtCz = ccz;
				builtVersion = SkySettings.version;
				builtCov = cov;
				if (logs++ < 2) {
					SvregSky.LOG.info("SvregSky: 3D clouds built {} faces around cell {},{} (camera y {}, cloud base {})", quadCount, ccx, ccz, (int) cam.y, (int) bottomY);
				}
			}
			if (mesh == null || quadCount == 0) {
				return;
			}
			Vector3f offset = new Vector3f((float) (builtCx * (double) cell + windX - cam.x), (float) (bottomY - cam.y), (float) (builtCz * (double) cell + windZ - cam.z));
			float reach = (st.radius() - step) * cell;
			boolean full = SkySettings.cloud3dFull();
			// colours of the sky's horizon toward and away from the sun, for the distance haze
			float[] hzSun = CirrusSky.horizonToward(true);
			float[] hzAway = CirrusSky.horizonToward(false);
			Matrix4f params = CirrusSky.skyParams();
			params.m03(st.levels() * st.levelHeight());
			params.m20(hzAway[0]);
			params.m21(hzAway[1]);
			params.m22(hzAway[2]);
			params.m23(full ? 1 : 0);
			params.m30(hzSun[0]);
			params.m32(hzSun[1]);
			params.m33(hzSun[2]);
			org.joml.Vector4f extra = CirrusSky.skyExtra();
			extra.y = reach * (full ? 0.75F : 0.55F);
			extra.z = reach;
			Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrixCopy()).translate(offset);
			var transforms = RenderSystem.getDynamicUniforms().writeTransform(modelView, extra, offset, params);

			Minecraft mc = Minecraft.getInstance();
			// same targets as vanilla clouds: the separate clouds target when "improved transparency" is on, else the main one
			RenderTarget cloudTarget = mc.levelRenderer.cloudsTarget();
			RenderTarget target = cloudTarget != null ? cloudTarget : mc.gameRenderer.mainRenderTarget();
			GpuTextureView color = target.getColorTextureView();
			GpuTextureView depth = target.getDepthTextureView();
			RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
			GpuBuffer indexBuffer = indices.getBuffer(quadCount * 6);
			IndexType indexType = indices.type();
			try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "SvregSky 3D clouds", color, Optional.empty(), depth, OptionalDouble.empty())) {
				pass.setPipeline(pipeline());
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("DynamicTransforms", transforms);
				pass.setVertexBuffer(0, mesh.slice());
				pass.setIndexBuffer(indexBuffer, indexType);
				pass.drawIndexed(quadCount * 6, 1, 0, 0, 0);
			}
		} catch (Throwable t) {
			broken = true;
			SvregSky.LOG.error("SvregSky: 3D clouds failed, turning them off", t);
		}
	}

	public static void close() {
		if (mesh != null) {
			mesh.close();
			mesh = null;
		}
		builtCx = Integer.MIN_VALUE;
	}
}
