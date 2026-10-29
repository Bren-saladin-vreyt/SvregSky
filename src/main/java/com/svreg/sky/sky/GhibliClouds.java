package com.svreg.sky.sky;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
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

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

/**
 * "3D Ghibli" clouds: big puffy cumulus built from spheres (a wide flat-bottomed body, domes climbing up, many small
 * cauliflower bumps), stored in a repeating voxel grid. Every cell remembers the normal of the puff it belongs to, so the
 * toon light bands in cirrus_ghibli.fsh follow each rounded puff. The mesh of the cells around the camera is built on a
 * worker thread and swapped in when ready, so building never stalls a frame.
 */
public final class GhibliClouds {
	private static final Identifier SHADER = Identifier.fromNamespaceAndPath(SvregSky.MOD_ID, "core/cirrus_ghibli");
	public static final int NX = 256, NY = 22, NZ = 256;
	public static final float CELL = 4.0F;

	private static RenderPipeline pipeline;
	private static boolean broken;

	// grid: occupancy + puff normal (3 signed bytes) per cell
	private static volatile byte[] occ;
	private static volatile byte[] nrm;
	private static long gridSeed = Long.MIN_VALUE;
	private static volatile int gridVersion;
	private static volatile float lastBase = 192.0F;
	private static CompletableFuture<Void> gridJob;

	// mesh
	private static GpuBuffer mesh;
	private static int quadCount;
	private static int builtCx = Integer.MIN_VALUE, builtCz, builtRadius, builtVersion = -1;
	private static float builtSunX, builtSunY, builtSunZ;
	private static CompletableFuture<Built> meshJob;

	private static double windX, windZ;
	private static long lastNanos;
	private static int logs;

	private record Built(MeshData data, ByteBufferBuilder bytes, int quads, int cx, int cz, int radius, float sx, float sy, float sz, int version) {
	}

	private GhibliClouds() {
	}

	private static int idx(int x, int y, int z) {
		return (Math.floorMod(x, NX) * NY + y) * NZ + Math.floorMod(z, NZ);
	}

	public static boolean filled(int x, int y, int z) {
		byte[] o = occ;
		return o != null && y >= 0 && y < NY && o[idx(x, y, z)] != 0;
	}

	// ------------------------------------------------------------------ grid
	private static void generate(long seed) {
		GhibliGen g = new GhibliGen(NX, NY, NZ, seed).generate();
		nrm = g.nrm;
		occ = g.occ;
		gridVersion++;
	}

	// ------------------------------------------------------------------ mesh
	private static RenderPipeline pipeline() {
		if (pipeline == null) {
			pipeline = RenderPipeline.builder(RenderPipeline.builder().withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION).buildSnippet())
					.withLocation(Identifier.fromNamespaceAndPath(SvregSky.MOD_ID, "pipeline/cirrus_ghibli"))
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

	/** self-shadow: how many cells toward the sun are filled (0..6) */
	private static int shadow(int x, int y, int z, float sx, float sy, float sz) {
		int s = 0;
		for (int k = 1; k <= 6; k++) {
			float t = k * 1.5F;
			if (filled(x + Math.round(sx * t), y + Math.round(sy * t), z + Math.round(sz * t))) {
				s++;
			}
		}
		return s;
	}

	private static Built buildMesh(int ccx, int ccz, int radius, float sx, float sy, float sz, int version) {
		byte[] o = occ, nn = nrm;
		int r2 = radius * radius;
		ByteBufferBuilder bytes = new ByteBufferBuilder(DefaultVertexFormat.POSITION_COLOR.getVertexSize() * 4 * 8192);
		BufferBuilder b = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_COLOR);
		int quads = 0;
		int[][] dirs = {{0, 1, 0}, {0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
		for (int dz = -radius; dz <= radius; dz++) {
			for (int dx = -radius; dx <= radius; dx++) {
				if (dx * dx + dz * dz > r2) {
					continue;
				}
				int gx = ccx + dx, gz = ccz + dz;
				for (int y = 1; y < NY; y++) {
					int i = idx(gx, y, gz);
					if (o[i] == 0) {
						continue;
					}
					int nr = nn[i * 3] + 128, ng = nn[i * 3 + 1] + 128, nb = nn[i * 3 + 2] + 128;
					int sh = -1;
					for (int f = 0; f < 6; f++) {
						int[] d = dirs[f];
						if (filled(gx + d[0], y + d[1], gz + d[2])) {
							continue;
						}
						if (sh < 0) {
							sh = shadow(gx, y, gz, sx, sy, sz);
						}
						int a = f * 40 + sh * 5;
						float x0 = dx * CELL, x1 = x0 + CELL, y0 = y * CELL, y1 = y0 + CELL, z0 = dz * CELL, z1 = z0 + CELL;
						switch (f) {
							case 0 -> quad(b, nr, ng, nb, a, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
							case 1 -> quad(b, nr, ng, nb, a, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
							case 2 -> quad(b, nr, ng, nb, a, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
							case 3 -> quad(b, nr, ng, nb, a, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
							case 4 -> quad(b, nr, ng, nb, a, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
							default -> quad(b, nr, ng, nb, a, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
						}
						quads++;
					}
				}
			}
		}
		MeshData data = b.build();
		return new Built(data, bytes, data == null ? 0 : quads, ccx, ccz, radius, sx, sy, sz, version);
	}

	private static void quad(BufferBuilder b, int r, int g, int bl, int a, float... v) {
		for (int i = 0; i < 12; i += 3) {
			b.addVertex(v[i], v[i + 1], v[i + 2]).setColor(r, g, bl, a);
		}
	}

	private static void swapIn(Built built) {
		try {
			if (built.data() != null) {
				GpuBuffer next = RenderSystem.getDevice().createBuffer(() -> "SvregSky Ghibli clouds", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_VERTEX,
						built.data().vertexBuffer());
				if (mesh != null) {
					mesh.close();
				}
				mesh = next;
				quadCount = built.quads();
				builtCx = built.cx();
				builtCz = built.cz();
				builtRadius = built.radius();
				builtSunX = built.sx();
				builtSunY = built.sy();
				builtSunZ = built.sz();
				builtVersion = built.version();
				if (logs++ < 3) {
					SvregSky.LOG.info("SvregSky: Ghibli clouds built {} faces (radius {} cells)", quadCount, builtRadius);
				}
			}
		} finally {
			if (built.data() != null) {
				built.data().close();
			}
			built.bytes().close();
		}
	}

	/** columns that hold cloud, for the light rays in the sky shader (256 x 256, 1 = cloud) */
	public static byte[] footprint() {
		byte[] o = occ;
		if (o == null) {
			return null;
		}
		byte[] f = new byte[NX * NZ];
		for (int x = 0; x < NX; x++) {
			for (int z = 0; z < NZ; z++) {
				for (int y = 0; y < NY; y++) {
					if (o[idx(x, y, z)] != 0) {
						f[x + z * NX] = 1;
						break;
					}
				}
			}
		}
		return f;
	}

	public static double windX() {
		return windX;
	}

	public static double windZ() {
		return windZ;
	}

	public static int gridVersion() {
		return gridVersion;
	}

	/** world height of the cloud bases, from the last frame */
	public static float baseY() {
		return lastBase;
	}

	public static int radiusCells() {
		return Math.max(48, Math.min(240, SkySettings.cloud3dDistance * 3));
	}

	// ------------------------------------------------------------------ render
	public static void render(float bottomY, Vec3 cam) {
		if (broken) {
			return;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			lastBase = bottomY;
			long now = System.nanoTime();
			double dt = lastNanos == 0 ? 0 : Math.min(0.25, (now - lastNanos) / 1e9);
			lastNanos = now;
			double speed = 1.6 * SkySettings.cloudSpeed;
			windX = (windX + dt * speed) % (NX * (double) CELL);
			windZ = (windZ + dt * speed * 0.33) % (NZ * (double) CELL);

			// a new set of clouds every in-game day
			long day = mc.level == null ? 0 : Math.floorDiv(mc.level.getOverworldClockTime(), 24000L);
			long seed = day * 7919L + 17;
			if (seed != gridSeed && (gridJob == null || gridJob.isDone())) {
				gridSeed = seed;
				gridJob = CompletableFuture.runAsync(() -> generate(seed));
				builtVersion = -1;
			}
			if (occ == null) {
				return;
			}

			double sx = cam.x - windX, sz = cam.z - windZ;
			int ccx = (int) Math.floor(sx / CELL), ccz = (int) Math.floor(sz / CELL);
			int radius = radiusCells();
			Vector3f sun = CirrusSky.sunDirection();
			float sunLen = sun.length() + 1e-5F;
			float ux = sun.x / sunLen, uy = Math.max(0.05F, sun.y / sunLen), uz = sun.z / sunLen;
			int step = Math.max(2, radius / 10);
			boolean stale = Math.abs(ccx - builtCx) >= step || Math.abs(ccz - builtCz) >= step || radius != builtRadius
					|| builtVersion != SkySettings.version
					|| Math.abs(ux - builtSunX) + Math.abs(uy - builtSunY) + Math.abs(uz - builtSunZ) > 0.08F;
			if (meshJob != null && meshJob.isDone()) {
				Built done = meshJob.join();
				meshJob = null;
				swapIn(done);
			}
			if (stale && meshJob == null && gridJob != null && gridJob.isDone()) {
				int v = SkySettings.version;
				meshJob = CompletableFuture.supplyAsync(() -> buildMesh(ccx, ccz, radius, ux, uy, uz, v));
			}
			if (mesh == null || quadCount == 0) {
				return;
			}
			Vector3f offset = new Vector3f((float) (builtCx * (double) CELL + windX - cam.x), (float) (bottomY - CELL - cam.y),
					(float) (builtCz * (double) CELL + windZ - cam.z));
			float reach = (builtRadius - step) * CELL;
			float[] hzSun = CirrusSky.horizonToward(true);
			float[] hzAway = CirrusSky.horizonToward(false);
			Matrix4f params = CirrusSky.skyParams();
			params.m03(NY * CELL);
			params.m20(hzAway[0]);
			params.m21(hzAway[1]);
			params.m22(hzAway[2]);
			params.m23(0);
			params.m30(hzSun[0]);
			params.m32(hzSun[1]);
			params.m33(hzSun[2]);
			org.joml.Vector4f extra = CirrusSky.skyExtra();
			extra.y = reach * 0.6F;
			extra.z = reach;
			Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrixCopy()).translate(offset);
			var transforms = RenderSystem.getDynamicUniforms().writeTransform(modelView, extra, offset, params);

			RenderTarget cloudTarget = mc.levelRenderer.cloudsTarget();
			RenderTarget target = cloudTarget != null ? cloudTarget : mc.gameRenderer.mainRenderTarget();
			RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
			GpuBuffer indexBuffer = indices.getBuffer(quadCount * 6);
			IndexType indexType = indices.type();
			try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "SvregSky Ghibli clouds",
					target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
				pass.setPipeline(pipeline());
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("DynamicTransforms", transforms);
				pass.setVertexBuffer(0, mesh.slice());
				pass.setIndexBuffer(indexBuffer, indexType);
				pass.drawIndexed(quadCount * 6, 1, 0, 0, 0);
			}
		} catch (Throwable t) {
			broken = true;
			SvregSky.LOG.error("SvregSky: Ghibli clouds failed, turning them off", t);
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
