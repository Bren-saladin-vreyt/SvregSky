package com.svreg.sky.fx;

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
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Random;

/**
 * Ambient Ghibli particles, all in one cheap draw: fireflies over grass at night, leaves and cherry petals
 * drifting off trees with the wind, and sun glints sparkling on water.
 */
public final class Effects {
	private static final Identifier SHADER = Identifier.fromNamespaceAndPath(SvregSky.MOD_ID, "core/cirrus_fx");
	private static final int FIREFLIES = 56, LEAVES = 36, SPARKLES = 90;
	private static final Random RNG = new Random();

	private static RenderPipeline pipeline;
	private static boolean broken;
	private static long lastNanos;
	private static final float WIND_X = 0.943F, WIND_Z = 0.333F;

	private static final class P {
		double x, y, z;
		float vx, vy, vz, age, life, phase, size, spin;
		int color;
		boolean alive;
	}

	private static final P[] fireflies = make(FIREFLIES), leaves = make(LEAVES), sparkles = make(SPARKLES);

	private static P[] make(int n) {
		P[] a = new P[n];
		for (int i = 0; i < n; i++) {
			a[i] = new P();
		}
		return a;
	}

	private Effects() {
	}

	private static RenderPipeline pipeline() {
		if (pipeline == null) {
			pipeline = RenderPipeline.builder(RenderPipeline.builder().withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION).buildSnippet())
					.withLocation(Identifier.fromNamespaceAndPath(SvregSky.MOD_ID, "pipeline/cirrus_fx"))
					.withVertexShader(SHADER)
					.withFragmentShader(SHADER)
					.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
					.withPrimitiveTopology(PrimitiveTopology.QUADS)
					.withCull(false)
					.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
					.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
					.build();
		}
		return pipeline;
	}

	private static int top(ClientLevel level, int x, int z) {
		return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
	}

	// ------------------------------------------------------------------ spawning
	private static void spawnFirefly(P p, ClientLevel level, Vec3 cam) {
		p.alive = false;
		for (int tries = 0; tries < 4; tries++) {
			int x = (int) Math.floor(cam.x + (RNG.nextDouble() - 0.5) * 32);
			int z = (int) Math.floor(cam.z + (RNG.nextDouble() - 0.5) * 32);
			int y = top(level, x, z);
			if (Math.abs(y - cam.y) > 12) {
				continue;
			}
			BlockState ground = level.getBlockState(new BlockPos(x, y - 1, z));
			BlockState above = level.getBlockState(new BlockPos(x, y, z));
			if (!ground.is(Blocks.GRASS_BLOCK) && !above.is(BlockTags.REPLACEABLE_BY_TREES) && !above.is(BlockTags.FLOWERS)) {
				continue;
			}
			p.x = x + RNG.nextDouble();
			p.z = z + RNG.nextDouble();
			p.y = y + 0.3 + RNG.nextDouble() * 1.6;
			p.vx = (RNG.nextFloat() - 0.5F) * 0.4F;
			p.vz = (RNG.nextFloat() - 0.5F) * 0.4F;
			p.vy = (RNG.nextFloat() - 0.5F) * 0.15F;
			p.life = 6 + RNG.nextFloat() * 8;
			p.age = 0;
			p.phase = RNG.nextFloat() * 6.28F;
			p.size = 0.15F + RNG.nextFloat() * 0.09F;
			p.color = RNG.nextFloat() < 0.7F ? 0xD8FF6A : 0xFFE27A;
			p.alive = true;
			return;
		}
	}

	private static void spawnLeaf(P p, ClientLevel level, Vec3 cam) {
		p.alive = false;
		for (int tries = 0; tries < 6; tries++) {
			int x = (int) Math.floor(cam.x + (RNG.nextDouble() - 0.5) * 28);
			int z = (int) Math.floor(cam.z + (RNG.nextDouble() - 0.5) * 28);
			int y = (int) Math.floor(cam.y + RNG.nextDouble() * 14 - 2);
			BlockPos pos = new BlockPos(x, y, z);
			BlockState s = level.getBlockState(pos);
			if (!s.is(BlockTags.LEAVES) || !level.getBlockState(pos.below()).isAir()) {
				continue;
			}
			boolean cherry = s.is(Blocks.CHERRY_LEAVES);
			p.x = x + RNG.nextDouble();
			p.y = y - 0.05;
			p.z = z + RNG.nextDouble();
			p.vx = p.vz = 0;
			p.vy = -0.5F - RNG.nextFloat() * 0.4F;
			p.life = 9 + RNG.nextFloat() * 5;
			p.age = 0;
			p.phase = RNG.nextFloat() * 6.28F;
			p.spin = 1.5F + RNG.nextFloat() * 2.5F;
			p.size = cherry ? 0.07F + RNG.nextFloat() * 0.03F : 0.09F + RNG.nextFloat() * 0.05F;
			if (cherry) {
				p.color = RNG.nextBoolean() ? 0xF7B8CC : 0xFFD6E2;
			} else {
				int[] greens = {0x7DBE4C, 0x9CCB52, 0xB8C94A, 0x6FAE48};
				p.color = greens[RNG.nextInt(greens.length)];
			}
			p.alive = true;
			return;
		}
	}

	private static void spawnSparkle(P p, ClientLevel level, Vec3 cam, Vector3f sun) {
		p.alive = false;
		for (int tries = 0; tries < 5; tries++) {
			double a = RNG.nextDouble() * Math.PI * 2, r = 3 + RNG.nextDouble() * 34;
			int x = (int) Math.floor(cam.x + Math.cos(a) * r);
			int z = (int) Math.floor(cam.z + Math.sin(a) * r);
			int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
			BlockPos below = new BlockPos(x, y - 1, z);
			if (!level.getFluidState(below).is(FluidTags.WATER)) {
				continue;
			}
			// glints mostly where the sun reflects toward the camera
			double px = x + RNG.nextDouble() - cam.x, py = y - 0.1 - cam.y, pz = z + RNG.nextDouble() - cam.z;
			double len = Math.sqrt(px * px + py * py + pz * pz) + 1e-6;
			double rx = px / len, ry = -py / len, rz = pz / len; // view ray reflected on the flat water
			double align = rx * sun.x + ry * sun.y + rz * sun.z;
			if (align < 0.55 + RNG.nextDouble() * 0.35) {
				continue;
			}
			p.x = cam.x + px;
			p.y = y - 0.1;
			p.z = cam.z + pz;
			p.life = 0.25F + RNG.nextFloat() * 0.5F;
			p.age = 0;
			p.size = 0.10F + RNG.nextFloat() * 0.12F + (float) (r * 0.004);
			p.alive = true;
			return;
		}
	}

	// ------------------------------------------------------------------ drawing
	private static void billboard(BufferBuilder b, float x, float y, float z, float size, float rx, float ry, float rz, float ux, float uy, float uz,
								  float u0, int argb) {
		int r = argb >> 16 & 0xFF, g = argb >> 8 & 0xFF, bl = argb & 0xFF, a = argb >>> 24;
		b.addVertex(x - (rx + ux) * size, y - (ry + uy) * size, z - (rz + uz) * size).setUv(u0, 0).setColor(r, g, bl, a);
		b.addVertex(x + (rx - ux) * size, y + (ry - uy) * size, z + (rz - uz) * size).setUv(u0 + 1, 0).setColor(r, g, bl, a);
		b.addVertex(x + (rx + ux) * size, y + (ry + uy) * size, z + (rz + uz) * size).setUv(u0 + 1, 1).setColor(r, g, bl, a);
		b.addVertex(x - (rx - ux) * size, y - (ry - uy) * size, z - (rz - uz) * size).setUv(u0, 1).setColor(r, g, bl, a);
	}

	public static void render(Vec3 cam) {
		boolean ff = SkySettings.fireflies, lv = SkySettings.fallingLeaves, sp = SkySettings.waterSparkles;
		Minecraft mc = Minecraft.getInstance();
		if (broken || (!ff && !lv && !sp) || mc.level == null) {
			return;
		}
		try {
			ClientLevel level = mc.level;
			long now = System.nanoTime();
			float dt = lastNanos == 0 ? 0 : (float) Math.min(0.1, (now - lastNanos) / 1e9);
			lastNanos = now;
			float t = (float) ((now / 1e9) % 3600.0);
			float night = CirrusSky.nightLevel(), day = CirrusSky.dayLevel(), gold = CirrusSky.goldLevel(), rain = CirrusSky.rainLevel();
			float wind = SkySettings.windStrength;
			Vector3f sun = CirrusSky.sunDirection();

			Matrix4f mv = RenderSystem.getModelViewMatrixCopy();
			float rx = mv.m00(), ry = mv.m10(), rz = mv.m20(), ux = mv.m01(), uy = mv.m11(), uz = mv.m21();

			try (ByteBufferBuilder bytes = new ByteBufferBuilder(DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize() * 4 * (FIREFLIES + LEAVES + SPARKLES))) {
				BufferBuilder b = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
				int quads = 0;

				// fireflies: night, clear weather, over grass and flowers
				float ffVis = night * (1 - rain);
				for (P p : fireflies) {
					if (!ff || ffVis < 0.05F) {
						p.alive = false;
						continue;
					}
					if (!p.alive || p.age > p.life || p.x - cam.x > 20 || cam.x - p.x > 20 || p.z - cam.z > 20 || cam.z - p.z > 20) {
						if (RNG.nextFloat() < 0.25F) {
							spawnFirefly(p, level, cam);
						}
						if (!p.alive) {
							continue;
						}
					}
					p.age += dt;
					p.vx += (RNG.nextFloat() - 0.5F) * dt * 0.8F;
					p.vz += (RNG.nextFloat() - 0.5F) * dt * 0.8F;
					p.vy += (RNG.nextFloat() - 0.5F) * dt * 0.4F;
					p.vx *= 0.98F; p.vy *= 0.97F; p.vz *= 0.98F;
					p.x += p.vx * dt; p.y += p.vy * dt + Math.sin(t * 1.3 + p.phase) * 0.004; p.z += p.vz * dt;
					float blink = 0.5F + 0.5F * (float) Math.sin(t * 2.2 + p.phase * 3);
					float env = Math.min(1, p.age / 1.2F) * Math.min(1, (p.life - p.age) / 1.2F);
					int a = Math.round(255 * ffVis * env * (0.25F + 0.75F * blink * blink));
					billboard(b, (float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z), p.size * (0.8F + 0.4F * blink), rx, ry, rz, ux, uy, uz, 0, a << 24 | p.color);
					quads++;
				}

				// leaves & petals: fall, drift with the wind, flutter and spin
				for (P p : leaves) {
					if (!lv) {
						p.alive = false;
						continue;
					}
					if (!p.alive || p.age > p.life) {
						if (RNG.nextFloat() < 0.04F + 0.06F * Math.min(wind, 2)) {
							spawnLeaf(p, level, cam);
						}
						if (!p.alive) {
							continue;
						}
					}
					p.age += dt;
					float sway = (float) Math.sin(t * 2.4 + p.phase);
					p.vx += (WIND_X * wind * 1.6F - p.vx) * dt * 1.2F;
					p.vz += (WIND_Z * wind * 1.6F - p.vz) * dt * 1.2F;
					p.x += (p.vx + sway * 0.6F * -WIND_Z) * dt;
					p.z += (p.vz + sway * 0.6F * WIND_X) * dt;
					p.y += (p.vy + Math.abs(sway) * 0.25F) * dt;
					BlockPos at = BlockPos.containing(p.x, p.y, p.z);
					if (!level.getBlockState(at).isAir() && !level.getBlockState(at).is(BlockTags.LEAVES)) {
						p.age = Math.max(p.age, p.life - 0.8F); // landed: fade out
						p.vx = p.vy = p.vz = 0;
					}
					float env = Math.min(1, p.age / 0.6F) * Math.min(1, (p.life - p.age) / 0.8F);
					// spinning quad: tumble around a tilted axis
					float ang = t * p.spin + p.phase;
					float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
					float ax = ca, ay = 0.35F * sa, az = sa;               // long axis
					float bx = -sa * 0.4F, by = (float) Math.cos(ang * 0.7), bz = ca * 0.4F; // short axis
					float bl = (float) Math.sqrt(bx * bx + by * by + bz * bz);
					bx /= bl; by /= bl; bz /= bl;
					int a = Math.round(255 * env);
					billboard(b, (float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z), p.size, ax, ay, az, bx * 0.5F, by * 0.5F, bz * 0.5F, 2, a << 24 | p.color);
					quads++;
				}

				// water sparkles: sun glints, strongest at golden hour
				float spVis = Math.max(day, gold) * (1 - rain) * (sun.y > -0.02F ? 1 : 0);
				int spColor = gold > 0.3F ? 0xFFE7A8 : 0xFFFFFF;
				for (P p : sparkles) {
					if (!sp || spVis < 0.05F) {
						p.alive = false;
						continue;
					}
					if (!p.alive || p.age > p.life) {
						spawnSparkle(p, level, cam, sun);
						if (!p.alive) {
							continue;
						}
					}
					p.age += dt;
					float k = (float) Math.sin(Math.PI * Math.min(1, p.age / p.life));
					int a = Math.round(255 * spVis * k);
					billboard(b, (float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z), p.size * (0.6F + 0.4F * k), rx, ry, rz, ux, uy, uz, 4, a << 24 | spColor);
					quads++;
				}

				if (quads == 0) {
					return;
				}
				try (MeshData data = b.build()) {
					if (data == null) {
						return;
					}
					GpuBuffer vb = RenderSystem.getDevice().createBuffer(() -> "SvregSky fx", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_VERTEX, data.vertexBuffer());
					try {
						var transforms = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(mv), new Vector4f(1, 1, 1, 1), new Vector3f(), new Matrix4f());
						RenderTarget cloudTarget = mc.levelRenderer.cloudsTarget();
						RenderTarget target = cloudTarget != null ? cloudTarget : mc.gameRenderer.mainRenderTarget();
						RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
						GpuBuffer ib = indices.getBuffer(quads * 6);
						IndexType type = indices.type();
						try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "SvregSky fx", target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
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
		} catch (Throwable e) {
			broken = true;
			SvregSky.LOG.error("SvregSky: ambient effects failed, turning them off", e);
		}
	}
}
