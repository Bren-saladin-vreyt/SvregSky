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
	/** the sky shader's view of the field: 256 x 256 columns of 4 blocks (the real grid may be finer) */
	public static final int NX = 256, NY = 22, NZ = 256;
	public static final float CELL = 4.0F;
	/** the field always tiles every 1024 blocks */
	private static final double SPAN = NX * (double) CELL;

	private static RenderPipeline pipeline;
	private static boolean broken;

	/** the voxel field: occupancy, normal (3 signed bytes) and brightness per cell; cell = blocks per cell (4 or 2) */
	private record Grid(int nx, int ny, int nz, float cell, byte[] occ, byte[] nrm, byte[] light) {
		int idx(int x, int y, int z) {
			return (Math.floorMod(x, nx) * ny + y) * nz + Math.floorMod(z, nz);
		}

		boolean filled(int x, int y, int z) {
			return y >= 0 && y < ny && occ[idx(x, y, z)] != 0;
		}
	}

	private static volatile Grid grid;
	private static long gridSeed = Long.MIN_VALUE;
	private static volatile int gridVersion;
	private static volatile float lastBase = 192.0F;
	private static CompletableFuture<Void> gridJob;

	// mesh
	private static GpuBuffer mesh;
	private static int quadCount;
	private static int builtCx = Integer.MIN_VALUE, builtCz, builtRadius, builtVersion = -1;
	private static float builtCell = CELL;
	private static float builtSunX, builtSunY, builtSunZ;
	private static CompletableFuture<Built> meshJob;

	private static double windX, windZ;
	private static long lastNanos;
	private static int logs;

	private record Built(MeshData data, ByteBufferBuilder bytes, int quads, int cx, int cz, int radius, float sx, float sy, float sz, int version, float cell) {
	}

	private GhibliClouds() {
	}

	// ------------------------------------------------------------------ grid
	private static void generate(long seed, String style, int cell) {
		if ("anime".equals(style)) {
			// anime cumulus (Perlin-warped Worley bubbles), laid out in blocks so 4- and 2-block cells give the same sky
			CumulusGen g = new CumulusGen(cell, seed);
			g.countMul = SkySettings.ghibliAmount;
			g.sizeMul = SkySettings.ghibliSize;
			g.generate();
			grid = new Grid(g.nx, g.ny, g.nz, g.cell, g.occ, g.nrm, g.light);
		} else {
			GhibliGen g = new GhibliGen(NX, NY, NZ, seed);
			g.style = style;
			g.countMul = SkySettings.ghibliAmount;
			g.sizeMul = SkySettings.ghibliSize;
			g.generate();
			grid = new Grid(NX, NY, NZ, CELL, g.occ, g.nrm, openness(g.occ));
		}
		gridVersion++;
	}

	/** brightness for the older looks: thin, open parts of a cloud are lighter than its packed middle */
	private static byte[] openness(byte[] o) {
		Grid t = new Grid(NX, NY, NZ, CELL, o, null, null);
		byte[] l = new byte[o.length];
		for (int x = 0; x < NX; x++) {
			for (int y = 0; y < NY; y++) {
				for (int z = 0; z < NZ; z++) {
					int i = t.idx(x, y, z);
					if (o[i] == 0) {
						continue;
					}
					int open = 0;
					for (int s = 1; s <= 2; s++) {
						open += (t.filled(x + s, y, z) ? 0 : 1) + (t.filled(x - s, y, z) ? 0 : 1) + (t.filled(x, y + s, z) ? 0 : 1)
								+ (t.filled(x, y - s, z) ? 0 : 1) + (t.filled(x, y, z + s) ? 0 : 1) + (t.filled(x, y, z - s) ? 0 : 1);
					}
					l[i] = (byte) Math.round(Math.min(1, 0.25F + open / 12F) * 255);
				}
			}
		}
		return l;
	}

	// ------------------------------------------------------------------ mesh
	private static RenderPipeline pipeline() {
		if (pipeline == null) {
			pipeline = RenderPipeline.builder(RenderPipeline.builder().withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION).buildSnippet())
					.withLocation(Identifier.fromNamespaceAndPath(SvregSky.MOD_ID, "pipeline/cirrus_ghibli"))
					.withVertexShader(SHADER)
					.withFragmentShader(SHADER)
					.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
					.withPrimitiveTopology(PrimitiveTopology.QUADS)
					.withCull(false)
					.withDepthStencilState(DepthStencilState.DEFAULT)
					.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
					.build();
		}
		return pipeline;
	}

	/** self-shadow: how many of 6 samples toward the sun (6 blocks apart) are inside cloud (0..6) */
	private static int shadow(Grid g, int x, int y, int z, float sx, float sy, float sz) {
		int s = 0;
		float per = 6F / g.cell();
		for (int k = 1; k <= 6; k++) {
			float t = k * per;
			if (g.filled(x + Math.round(sx * t), y + Math.round(sy * t), z + Math.round(sz * t))) {
				s++;
			}
		}
		return s;
	}

	private static Built buildMesh(Grid g, int ccx, int ccz, int radius, float sx, float sy, float sz, int version) {
		byte[] o = g.occ(), nn = g.nrm(), lt = g.light();
		int ny = g.ny();
		float cell = g.cell();
		int r2 = radius * radius;
		ByteBufferBuilder bytes = new ByteBufferBuilder(DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize() * 4 * 8192);
		BufferBuilder b = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
		int quads = 0;
		int[][] dirs = {{0, 1, 0}, {0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
		for (int dz = -radius; dz <= radius; dz++) {
			for (int dx = -radius; dx <= radius; dx++) {
				if (dx * dx + dz * dz > r2) {
					continue;
				}
				int gx = ccx + dx, gz = ccz + dz;
				for (int y = 0; y < ny; y++) {
					int i = g.idx(gx, y, gz);
					if (o[i] == 0) {
						continue;
					}
					int nr = nn[i * 3] + 128, ng = nn[i * 3 + 1] + 128, nb = nn[i * 3 + 2] + 128;
					int a = lt[i] & 255;
					int sh = -1;
					for (int f = 0; f < 6; f++) {
						int[] d = dirs[f];
						if (g.filled(gx + d[0], y + d[1], gz + d[2])) {
							continue;
						}
						if (sh < 0) {
							sh = shadow(g, gx, y, gz, sx, sy, sz);
						}
						float u = f, w = sh;
						float x0 = dx * cell, x1 = x0 + cell, y0 = y * cell, y1 = y0 + cell, z0 = dz * cell, z1 = z0 + cell;
						switch (f) {
							case 0 -> quad(b, nr, ng, nb, a, u, w, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
							case 1 -> quad(b, nr, ng, nb, a, u, w, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
							case 2 -> quad(b, nr, ng, nb, a, u, w, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
							case 3 -> quad(b, nr, ng, nb, a, u, w, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
							case 4 -> quad(b, nr, ng, nb, a, u, w, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
							default -> quad(b, nr, ng, nb, a, u, w, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
						}
						quads++;
					}
				}
			}
		}
		MeshData data = b.build();
		return new Built(data, bytes, data == null ? 0 : quads, ccx, ccz, radius, sx, sy, sz, version, cell);
	}

	/** colour = cell normal + brightness, uv = (face, self-shadow) */
	private static void quad(BufferBuilder b, int r, int g, int bl, int a, float u, float w, float... v) {
		for (int i = 0; i < 12; i += 3) {
			b.addVertex(v[i], v[i + 1], v[i + 2]).setUv(u, w).setColor(r, g, bl, a);
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
				builtCell = built.cell();
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
		Grid g = grid;
		if (g == null) {
			return null;
		}
		int k = Math.max(1, g.nx() / NX); // grid cells per 4-block column
		byte[] f = new byte[NX * NZ];
		for (int x = 0; x < g.nx(); x++) {
			for (int z = 0; z < g.nz(); z++) {
				int fi = x / k + (z / k) * NX;
				if (f[fi] != 0) {
					continue;
				}
				for (int y = 0; y < g.ny(); y++) {
					if (g.occ()[g.idx(x, y, z)] != 0) {
						f[fi] = 1;
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
			windX = (windX + dt * speed) % SPAN;
			windZ = (windZ + dt * speed * 0.33) % SPAN;

			// a new set of clouds every in-game day
			long day = mc.level == null ? 0 : Math.floorDiv(mc.level.getOverworldClockTime(), 24000L);
			String style = SkySettings.ghibliStyle;
			int cellSize = "anime".equals(style) ? SkySettings.ghibliCell : 4;
			long seed = day * 7919L + 17 + style.hashCode() * 31L + Math.round(SkySettings.ghibliAmount * 100) * 977L + Math.round(SkySettings.ghibliSize * 100) * 6007L
					+ cellSize * 104729L;
			if (seed != gridSeed && (gridJob == null || gridJob.isDone())) {
				gridSeed = seed;
				long genSeed = day * 7919L + 17 + style.hashCode() * 31L; // same sky at either cell size
				gridJob = CompletableFuture.runAsync(() -> generate(genSeed + Math.round(SkySettings.ghibliAmount * 100) * 977L
						+ Math.round(SkySettings.ghibliSize * 100) * 6007L, style, cellSize));
				builtVersion = -1;
			}
			Grid g = grid;
			if (g == null) {
				return;
			}
			float cell = g.cell();

			double sx = cam.x - windX, sz = cam.z - windZ;
			int ccx = (int) Math.floor(sx / cell), ccz = (int) Math.floor(sz / cell);
			int radius = Math.round(radiusCells() * CELL / cell);
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
				meshJob = CompletableFuture.supplyAsync(() -> buildMesh(g, ccx, ccz, radius, ux, uy, uz, v));
			}
			if (mesh == null || quadCount == 0) {
				return;
			}
			float bcell = builtCell;
			Vector3f offset = new Vector3f((float) (builtCx * (double) bcell + windX - cam.x), (float) (bottomY - 4.0F - cam.y),
					(float) (builtCz * (double) bcell + windZ - cam.z));
			float reach = (builtRadius - step) * bcell;
			float[] hzSun = CirrusSky.horizonToward(true);
			float[] hzAway = CirrusSky.horizonToward(false);
			Matrix4f params = CirrusSky.skyParams();
			params.m03(g.ny() * g.cell());
			params.m20(hzAway[0]);
			params.m21(hzAway[1]);
			params.m22(hzAway[2]);
			params.m23(DistantStorm.packed());
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
