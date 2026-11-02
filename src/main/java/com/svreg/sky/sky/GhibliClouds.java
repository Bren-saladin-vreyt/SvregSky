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
	private record Grid(int nx, int ny, int nz, float cell, byte[] occ, byte[] nrm, byte[] light, double ox, double oz, boolean wrap) {
		Grid(int nx, int ny, int nz, float cell, byte[] occ, byte[] nrm, byte[] light) {
			this(nx, ny, nz, cell, occ, nrm, light, 0, 0, true);
		}

		int idx(int x, int y, int z) {
			return wrap ? (Math.floorMod(x, nx) * ny + y) * nz + Math.floorMod(z, nz) : (x * ny + y) * nz + z;
		}

		boolean filled(int x, int y, int z) {
			if (y < 0 || y >= ny || !wrap && (x < 0 || z < 0 || x >= nx || z >= nz)) {
				return false;
			}
			return occ[idx(x, y, z)] != 0;
		}
	}

	/** "Fine near" (LOD): a 2-block patch around the camera on top of the 4-block sky */
	private static final float FINE_HALF = 160F, FINE_NEAR = 112F, FINE_MOVE = 40F;
	private static volatile Grid fine;
	private static volatile int fineVersion;
	private static CompletableFuture<Void> fineJob;
	private static double fineCx = Double.NaN, fineCz;
	private static long fineSeed = Long.MIN_VALUE;
	private static volatile long mainGenSeed = Long.MIN_VALUE + 1;

	private static volatile Grid grid;
	private static long gridSeed = Long.MIN_VALUE;
	private static volatile int gridVersion;
	private static volatile float lastBase = 192.0F;
	private static CompletableFuture<Void> gridJob;

	// mesh
	private static GpuBuffer mesh;
	private static int quadCount;
	private static double builtAx = Double.NaN, builtAz;
	private static float builtReach;
	private static int builtVersion = -1, builtFine = -1, builtRadius;
	private static boolean builtGreedy;
	private static float builtSunX, builtSunY, builtSunZ;
	private static CompletableFuture<Built> meshJob;

	private static double windX, windZ;
	private static long lastNanos;
	private static int logs;

	private record Built(MeshData data, ByteBufferBuilder bytes, int quads, double ax, double az, float reach, int radius, float sx, float sy, float sz, int version, int fineVer) {
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
			mainGenSeed = seed;
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

	/** the 2-block patch for "fine near": same sky as the 4-block grid, only around (cx, cz) in the drifting cloud space */
	private static void generateFine(long seed, double cx, double cz) {
		double ox = Math.floor((cx - FINE_HALF) / 2) * 2, oz = Math.floor((cz - FINE_HALF) / 2) * 2;
		float fx = (float) (ox - Math.floor(ox / SPAN) * SPAN), fz = (float) (oz - Math.floor(oz / SPAN) * SPAN);
		CumulusGen g = new CumulusGen(2, seed, fx, fz, FINE_HALF * 2);
		g.countMul = SkySettings.ghibliAmount;
		g.sizeMul = SkySettings.ghibliSize;
		g.generate();
		fine = new Grid(g.nx, g.ny, g.nz, 2, g.occ, g.nrm, g.light, ox, oz, false);
		fineVersion++;
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

	private static final int[][] DIRS = {{0, 1, 0}, {0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};

	private static Built buildMesh(Grid g, Grid fg, double ax, double az, float reach, int radius, float sx, float sy, float sz, int version, int fineVer, boolean greedy) {
		ByteBufferBuilder bytes = new ByteBufferBuilder(DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize() * 4 * 8192);
		BufferBuilder b = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
		int quads = 0;
		if (fg != null) {
			quads += meshRegion(b, fg, ax, az, 0, FINE_NEAR, sx, sy, sz, greedy);
			quads += meshRegion(b, g, ax, az, FINE_NEAR, reach, sx, sy, sz, greedy);
		} else {
			quads += meshRegion(b, g, ax, az, 0, reach, sx, sy, sz, greedy);
		}
		MeshData data = b.build();
		return new Built(data, bytes, data == null ? 0 : quads, ax, az, reach, radius, sx, sy, sz, version, fineVer);
	}

	/**
	 * Faces of the cells whose column centre lies between rMin and rMax blocks of (ax, az); vertices are relative to (ax, az).
	 * Greedy: neighbouring faces in one plane with the same (rounded) look are merged into one rectangle.
	 */
	private static int meshRegion(BufferBuilder b, Grid g, double ax, double az, float rMin, float rMax, float sx, float sy, float sz, boolean greedy) {
		float cell = g.cell();
		int ny = g.ny();
		int x0 = (int) Math.floor((ax - rMax - g.ox()) / cell), x1 = (int) Math.floor((ax + rMax - g.ox()) / cell);
		int z0 = (int) Math.floor((az - rMax - g.oz()) / cell), z1 = (int) Math.floor((az + rMax - g.oz()) / cell);
		if (!g.wrap()) {
			x0 = Math.max(0, x0);
			z0 = Math.max(0, z0);
			x1 = Math.min(g.nx() - 1, x1);
			z1 = Math.min(g.nz() - 1, z1);
		}
		if (x1 < x0 || z1 < z0) {
			return 0;
		}
		int w = x1 - x0 + 1, h = z1 - z0 + 1;
		boolean[] col = new boolean[w * h];
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				double dx = g.ox() + (x + 0.5) * cell - ax, dz = g.oz() + (z + 0.5) * cell - az;
				double d = Math.sqrt(dx * dx + dz * dz);
				col[(x - x0) * h + (z - z0)] = d >= rMin && d < rMax;
			}
		}
		float offX = (float) (g.ox() - ax), offZ = (float) (g.oz() - az);
		byte[] nn = g.nrm(), lt = g.light();
		int quads = 0;
		if (!greedy) {
			for (int x = x0; x <= x1; x++) {
				for (int z = z0; z <= z1; z++) {
					if (!col[(x - x0) * h + (z - z0)]) {
						continue;
					}
					for (int y = 0; y < ny; y++) {
						int i = g.idx(x, y, z);
						if (g.occ()[i] == 0) {
							continue;
						}
						int sh = -1;
						for (int f = 0; f < 6; f++) {
							int[] d = DIRS[f];
							if (g.filled(x + d[0], y + d[1], z + d[2])) {
								continue;
							}
							if (sh < 0) {
								sh = shadow(g, x, y, z, sx, sy, sz);
							}
							emitBox(b, g, f, x, y, z, 1, 1, 1, offX, offZ, nn[i * 3] + 128, nn[i * 3 + 1] + 128, nn[i * 3 + 2] + 128, lt[i] & 255, sh);
							quads++;
						}
					}
				}
			}
			return quads;
		}
		for (int f = 0; f < 6; f++) {
			int[] d = DIRS[f];
			int axis = d[0] != 0 ? 0 : (d[1] != 0 ? 1 : 2);
			// slice axis s; in-plane axes u (rows) and v (columns)
			int sN = axis == 0 ? w : (axis == 1 ? ny : h);
			int uN = axis == 0 ? ny : w, vN = axis == 2 ? ny : h;
			int[] key = new int[uN * vN];
			for (int s = 0; s < sN; s++) {
				for (int u = 0; u < uN; u++) {
					for (int v = 0; v < vN; v++) {
						int x, y, z;
						if (axis == 0) {
							x = x0 + s;
							y = u;
							z = z0 + v;
						} else if (axis == 1) {
							x = x0 + u;
							y = s;
							z = z0 + v;
						} else {
							x = x0 + u;
							y = v;
							z = z0 + s;
						}
						int k = -1;
						if (col[(x - x0) * h + (z - z0)] && g.filled(x, y, z) && !g.filled(x + d[0], y + d[1], z + d[2])) {
							int i = g.idx(x, y, z);
							// rounded look: normal 3 bits per axis, brightness 4 bits, self-shadow 0..6
							k = ((nn[i * 3] + 128) >> 5) | ((nn[i * 3 + 1] + 128) >> 5) << 3 | ((nn[i * 3 + 2] + 128) >> 5) << 6
									| ((lt[i] & 255) >> 4) << 9 | shadow(g, x, y, z, sx, sy, sz) << 13;
						}
						key[u * vN + v] = k;
					}
				}
				for (int u = 0; u < uN; u++) {
					for (int v = 0; v < vN; v++) {
						int k = key[u * vN + v];
						if (k < 0) {
							continue;
						}
						int cw = 1;
						while (v + cw < vN && key[u * vN + v + cw] == k) {
							cw++;
						}
						int ch = 1;
						grow:
						while (u + ch < uN) {
							for (int t = 0; t < cw; t++) {
								if (key[(u + ch) * vN + v + t] != k) {
									break grow;
								}
							}
							ch++;
						}
						for (int q = 0; q < ch; q++) {
							for (int t = 0; t < cw; t++) {
								key[(u + q) * vN + v + t] = -1;
							}
						}
						int x, y, z, ex, ey, ez;
						if (axis == 0) {
							x = x0 + s;
							y = u;
							z = z0 + v;
							ex = 1;
							ey = ch;
							ez = cw;
						} else if (axis == 1) {
							x = x0 + u;
							y = s;
							z = z0 + v;
							ex = ch;
							ey = 1;
							ez = cw;
						} else {
							x = x0 + u;
							y = v;
							z = z0 + s;
							ex = ch;
							ey = cw;
							ez = 1;
						}
						emitBox(b, g, f, x, y, z, ex, ey, ez, offX, offZ, ((k & 7) << 5) + 16, ((k >> 3 & 7) << 5) + 16, ((k >> 6 & 7) << 5) + 16,
								((k >> 9 & 15) << 4) + 8, k >> 13 & 7);
						quads++;
					}
				}
			}
		}
		return quads;
	}

	/** face f of the box of ex x ey x ez cells starting at cell (x, y, z) */
	private static void emitBox(BufferBuilder b, Grid g, int f, int x, int y, int z, int ex, int ey, int ez, float offX, float offZ,
			int r, int gg, int bl, int a, int sh) {
		float c = g.cell();
		float x0 = offX + x * c, x1 = x0 + ex * c, y0 = y * c, y1 = y0 + ey * c, z0 = offZ + z * c, z1 = z0 + ez * c;
		float u = f, w = sh;
		switch (f) {
			case 0 -> quad(b, r, gg, bl, a, u, w, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
			case 1 -> quad(b, r, gg, bl, a, u, w, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
			case 2 -> quad(b, r, gg, bl, a, u, w, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
			case 3 -> quad(b, r, gg, bl, a, u, w, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
			case 4 -> quad(b, r, gg, bl, a, u, w, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
			default -> quad(b, r, gg, bl, a, u, w, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
		}
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
				builtAx = built.ax();
				builtAz = built.az();
				builtReach = built.reach();
				builtRadius = built.radius();
				builtFine = built.fineVer();
				builtSunX = built.sx();
				builtSunY = built.sy();
				builtSunZ = built.sz();
				builtVersion = built.version();
				if (logs++ < 3) {
					SvregSky.LOG.info("SvregSky: Ghibli clouds built {} faces (reach {} blocks)", quadCount, builtReach);
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
			boolean anime = "anime".equals(style);
			boolean lod = anime && SkySettings.ghibliCell == 0;
			int cellSize = anime && !lod ? SkySettings.ghibliCell : 4;
			long genSeed = day * 7919L + 17 + style.hashCode() * 31L + Math.round(SkySettings.ghibliAmount * 100) * 977L + Math.round(SkySettings.ghibliSize * 100) * 6007L;
			long seed = genSeed + cellSize * 104729L;
			if (seed != gridSeed && (gridJob == null || gridJob.isDone())) {
				gridSeed = seed;
				gridJob = CompletableFuture.runAsync(() -> generate(genSeed, style, cellSize)); // same sky at either cell size
				builtVersion = -1;
			}
			Grid g = grid;
			if (g == null) {
				return;
			}

			double sx = cam.x - windX, sz = cam.z - windZ; // camera in the drifting cloud space
			// "fine near": a 2-block patch follows the camera on top of the 4-block sky
			if (!lod) {
				fine = null;
				fineCx = Double.NaN;
			} else if ((fineJob == null || fineJob.isDone()) && mainGenSeed == genSeed) {
				boolean far = Double.isNaN(fineCx) || Math.hypot(sx - fineCx, sz - fineCz) > FINE_MOVE;
				if (far || fineSeed != genSeed) {
					fineSeed = genSeed;
					fineCx = sx;
					fineCz = sz;
					double fcx = sx, fcz = sz;
					fineJob = CompletableFuture.runAsync(() -> generateFine(genSeed, fcx, fcz));
				}
			}
			Grid fg = lod && fineSeed == genSeed ? fine : null;
			int fv = fg == null ? -1 : fineVersion;

			int radius = radiusCells();
			float reachBlocks = radius * CELL;
			Vector3f sun = CirrusSky.sunDirection();
			float sunLen = sun.length() + 1e-5F;
			float ux = sun.x / sunLen, uy = Math.max(0.05F, sun.y / sunLen), uz = sun.z / sunLen;
			float stepBlocks = Math.max(8, reachBlocks / 10);
			boolean greedy = SkySettings.on("cloud_merge");
			boolean stale = Double.isNaN(builtAx) || Math.abs(sx - builtAx) >= stepBlocks || Math.abs(sz - builtAz) >= stepBlocks || radius != builtRadius
					|| builtVersion != SkySettings.version || fv != builtFine || greedy != builtGreedy
					|| Math.abs(ux - builtSunX) + Math.abs(uy - builtSunY) + Math.abs(uz - builtSunZ) > 0.08F;
			if (meshJob != null && meshJob.isDone()) {
				Built done = meshJob.join();
				meshJob = null;
				swapIn(done);
			}
			if (stale && meshJob == null && gridJob != null && gridJob.isDone()) {
				int v = SkySettings.version;
				double ax = Math.floor(sx / 4) * 4, az = Math.floor(sz / 4) * 4;
				builtGreedy = greedy;
				meshJob = CompletableFuture.supplyAsync(() -> buildMesh(g, fg, ax, az, reachBlocks, radius, ux, uy, uz, v, fv, greedy));
			}
			if (mesh == null || quadCount == 0) {
				return;
			}
			Vector3f offset = new Vector3f((float) (builtAx + windX - cam.x), (float) (bottomY - 4.0F - cam.y), (float) (builtAz + windZ - cam.z));
			float reach = builtReach - stepBlocks;
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
		builtAx = Double.NaN;
	}
}
