package com.svreg.sky.wind;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.CompareOp;
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
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Random;

/**
 * Ghibli / Zelda-style wind lines: thin white ribbons that streak past the player along the wind,
 * sometimes curling into a little loop, then fade. Drawn in the clouds pass, depth-tested against the world.
 */
public final class WindLines {
	private static final Identifier SHADER = Identifier.fromNamespaceAndPath(SvregSky.MOD_ID, "core/cirrus_windline");
	private static final int MAX = 20;
	private static final int SAMPLES = 28;

	private static RenderPipeline pipeline;
	private static boolean broken;
	private static final Random RNG = new Random();
	private static final Line[] LINES = new Line[MAX];
	private static long lastNanos;

	private static final class Line {
		double x, y, z;     // start, world space
		float dx, dz;       // direction (unit, horizontal)
		float length, life, age, phase, curl, width;
		boolean alive;
	}

	private WindLines() {
	}

	private static RenderPipeline pipeline() {
		if (pipeline == null) {
			pipeline = RenderPipeline.builder(RenderPipeline.builder().withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION).buildSnippet())
					.withLocation(Identifier.fromNamespaceAndPath(SvregSky.MOD_ID, "pipeline/cirrus_windline"))
					.withVertexShader(SHADER)
					.withFragmentShader(SHADER)
					.withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
					.withPrimitiveTopology(PrimitiveTopology.QUADS)
					.withCull(false)
					.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
					.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
					.build();
		}
		return pipeline;
	}

	private static void spawn(Line l, Vec3 cam) {
		double ang = Math.atan2(0.333, 0.943) + (RNG.nextDouble() - 0.5) * 0.35;
		l.dx = (float) Math.cos(ang);
		l.dz = (float) Math.sin(ang);
		// start upwind of the player so the line flies past/through the view
		double r = 3 + RNG.nextDouble() * 15;
		double a = RNG.nextDouble() * Math.PI * 2;
		l.length = 5 + RNG.nextFloat() * 6;
		l.x = cam.x + Math.cos(a) * r - l.dx * l.length * 0.5;
		l.z = cam.z + Math.sin(a) * r - l.dz * l.length * 0.5;
		l.y = cam.y - 1.2 + RNG.nextDouble() * 3.5;
		l.life = 1.6f + RNG.nextFloat() * 1.2f;
		l.age = -RNG.nextFloat() * 1.5f; // staggered starts
		l.phase = RNG.nextFloat() * 6.28f;
		l.curl = RNG.nextFloat() < 0.45f ? 0.35f + RNG.nextFloat() * 0.3f : 0f;
		l.width = 0.035f + RNG.nextFloat() * 0.025f;
		l.alive = true;
	}

	/** point on a line's path at s (0..1), relative to the camera */
	private static void point(Line l, float s, Vec3 cam, float[] out) {
		float sx = -l.dz, sz = l.dx; // side
		float px = l.dx * s * l.length + sx * (float) Math.sin(s * 3.1 + l.phase) * 0.35f;
		float py = (float) Math.sin(s * 6.28 + l.phase) * 0.22f;
		float pz = l.dz * s * l.length + sz * (float) Math.sin(s * 3.1 + l.phase) * 0.35f;
		if (l.curl > 0 && s > 0.55f && s < 0.8f) {
			double th = (s - 0.55) / 0.25 * Math.PI * 2;
			float rr = l.curl;
			px += l.dx * (float) Math.sin(th) * rr;
			pz += l.dz * (float) Math.sin(th) * rr;
			py += (float) (1 - Math.cos(th)) * rr;
		}
		out[0] = (float) (l.x - cam.x) + px;
		out[1] = (float) (l.y - cam.y) + py;
		out[2] = (float) (l.z - cam.z) + pz;
	}

	public static void render(Vec3 cam) {
		if (broken || !SkySettings.windLines || SkySettings.windStrength <= 0) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		try {
			long now = System.nanoTime();
			float dt = lastNanos == 0 ? 0 : (float) Math.min(0.1, (now - lastNanos) / 1e9);
			lastNanos = now;
			int count = Math.min(MAX, Math.round(3 + 3.5F * SkySettings.windStrength * SkySettings.windLineAmount));
			float speed = 0.6f + 0.4f * SkySettings.windSpeed;
			float rain = CirrusSky.rainLevel();

			try (ByteBufferBuilder bytes = new ByteBufferBuilder(DefaultVertexFormat.POSITION_COLOR.getVertexSize() * 4 * SAMPLES * MAX)) {
				BufferBuilder b = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_COLOR);
				int quads = 0;
				float[] p0 = new float[3], p1 = new float[3];
				for (int i = 0; i < MAX; i++) {
					if (LINES[i] == null) {
						LINES[i] = new Line();
					}
					Line l = LINES[i];
					if (i >= count) {
						l.alive = false;
						continue;
					}
					if (!l.alive) {
						spawn(l, cam);
					}
					l.age += dt * speed;
					if (l.age > l.life) {
						spawn(l, cam);
						continue;
					}
					if (l.age < 0) {
						continue;
					}
					float prog = l.age / l.life;
					float head = prog * 1.35f;
					float tail = head - 0.45f;
					float env = smooth(0f, 0.15f, prog) * (1 - smooth(0.75f, 1f, prog));
					float s0 = Math.max(0, tail), s1 = Math.min(1, head);
					if (s1 <= s0 || env <= 0.01f) {
						continue;
					}
					for (int k = 0; k < SAMPLES - 1; k++) {
						float a = s0 + (s1 - s0) * k / (SAMPLES - 1f);
						float c = s0 + (s1 - s0) * (k + 1) / (SAMPLES - 1f);
						point(l, a, cam, p0);
						point(l, c, cam, p1);
						// ribbon facing the camera: side = tangent x view direction
						float tx = p1[0] - p0[0], ty = p1[1] - p0[1], tz = p1[2] - p0[2];
						float vx = p0[0], vy = p0[1], vz = p0[2];
						float nx = ty * vz - tz * vy, ny = tz * vx - tx * vz, nz = tx * vy - ty * vx;
						float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz) + 1e-6f;
						// thin at both ends of the visible part, thickest in the middle
						float u0 = (a - s0) / (s1 - s0), u1 = (c - s0) / (s1 - s0);
						float w0 = l.width * (float) Math.sin(Math.PI * u0), w1 = l.width * (float) Math.sin(Math.PI * u1);
						int a0 = Math.round(255 * env * 0.75f * (0.25f + 0.75f * (float) Math.sin(Math.PI * u0)) * (1 - rain * 0.7f));
						int a1 = Math.round(255 * env * 0.75f * (0.25f + 0.75f * (float) Math.sin(Math.PI * u1)) * (1 - rain * 0.7f));
						nx /= nl; ny /= nl; nz /= nl;
						b.addVertex(p0[0] - nx * w0, p0[1] - ny * w0, p0[2] - nz * w0).setColor(255, 255, 255, a0);
						b.addVertex(p0[0] + nx * w0, p0[1] + ny * w0, p0[2] + nz * w0).setColor(255, 255, 255, a0);
						b.addVertex(p1[0] + nx * w1, p1[1] + ny * w1, p1[2] + nz * w1).setColor(255, 255, 255, a1);
						b.addVertex(p1[0] - nx * w1, p1[1] - ny * w1, p1[2] - nz * w1).setColor(255, 255, 255, a1);
						quads++;
					}
				}
				if (quads == 0) {
					return;
				}
				try (MeshData data = b.build()) {
					if (data == null) {
						return;
					}
					GpuBuffer vb = RenderSystem.getDevice().createBuffer(() -> "SvregSky wind lines", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_VERTEX, data.vertexBuffer());
					try {
						float day = CirrusSky.dayLevel();
						Vector4f tint = new Vector4f(1.0F, 0.98F + 0.02F * day, 0.95F + 0.05F * day, 0.35F + 0.65F * day);
						var transforms = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(RenderSystem.getModelViewMatrixCopy()), tint, new Vector3f(), new Matrix4f());
						RenderTarget cloudTarget = mc.levelRenderer.cloudsTarget();
						RenderTarget target = cloudTarget != null ? cloudTarget : mc.gameRenderer.mainRenderTarget();
						RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
						GpuBuffer ib = indices.getBuffer(quads * 6);
						IndexType type = indices.type();
						try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "SvregSky wind lines", target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
							pass.setPipeline(pipeline());
							RenderSystem.bindDefaultUniforms(pass);
							pass.setUniform("DynamicTransforms", transforms);
							pass.setVertexBuffer(0, vb.slice());
							pass.setIndexBuffer(ib, type);
							pass.drawIndexed(quads * 6, 1, 0, 0, 0);
						}
					} finally {
						vb.close();
					}
				}
			}
		} catch (Throwable t) {
			broken = true;
			SvregSky.LOG.error("SvregSky: wind lines failed, turning them off", t);
		}
	}

	private static float smooth(float e0, float e1, float x) {
		float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
		return t * t * (3 - 2 * t);
	}
}
