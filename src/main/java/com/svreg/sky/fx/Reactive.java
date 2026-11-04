package com.svreg.sky.fx;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.svreg.sky.SvregSky;
import com.svreg.sky.sky.CirrusSky;
import com.svreg.sky.sky.SkySettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The world answers the player: rings spread on shallow water around whoever wades through it (the player and the
 * animals nearby), grass and flowers kick up bits of green and petals when run through (and startled fireflies at
 * night), and little sparrows pecking on the grass flutter up and away when someone comes too close.
 * Drawn in the same particle draw as {@link Effects}.
 */
final class Reactive {
	static final int MAX_QUADS = 48 + 24 + 40 + 6;
	private static final Random RNG = new Random();
	private static final SoundEvent FLUTTER = SoundEvent.createVariableRangeEvent(Identifier.withDefaultNamespace("entity.parrot.fly"));
	private static final SoundEvent CHIRP = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(SvregSky.MOD_ID, "ambient.bird"));

	private static final class P {
		double x, y, z;
		float vx, vy, vz, age, life, size, phase, delay;
		int color, state;
		boolean alive;
	}

	private static final P[] rings = make(48), drops = make(24), bits = make(40), birds = make(6);
	private static final Map<Integer, float[]> wade = new HashMap<>();
	private static List<Entity> waders = List.of();
	private static float scanT, birdWait = 8;
	private static double birdX, birdZ;
	private static boolean flockUp;

	private Reactive() {
	}

	private static P[] make(int n) {
		P[] a = new P[n];
		for (int i = 0; i < n; i++) {
			a[i] = new P();
		}
		return a;
	}

	static boolean anyOn() {
		return SkySettings.on("walk_ripples") || SkySettings.on("grass_kick") || SkySettings.on("startle_birds");
	}

	private static P free(P[] pool) {
		for (P p : pool) {
			if (!p.alive) {
				return p;
			}
		}
		return null;
	}

	private static int argb(float a, int rgb) {
		return Math.round(Math.max(0, Math.min(1, a)) * 255) << 24 | rgb;
	}

	/** the water surface height where an entity wades, or NaN when it is not standing in shallow water */
	private static double surface(ClientLevel level, Entity e) {
		if (!e.isInWater() || e.isUnderWater() || e.isPassenger()) {
			return Double.NaN;
		}
		BlockPos feet = BlockPos.containing(e.getX(), e.getY() + 0.1, e.getZ());
		for (int i = 0; i < 2; i++) {
			BlockPos at = feet.above(i);
			FluidState f = level.getFluidState(at);
			if (f.is(FluidTags.WATER) && !level.getFluidState(at.above()).is(FluidTags.WATER)) {
				return at.getY() + f.getHeight(level, at);
			}
		}
		return Double.NaN;
	}

	private static void ring(double x, double y, double z, float size, float delay) {
		P p = free(rings);
		if (p == null) {
			return;
		}
		p.x = x;
		p.y = y + 0.02;
		p.z = z;
		p.size = size;
		p.age = -delay;
		p.life = 1.3F + size * 0.5F;
		p.alive = true;
	}

	static int emit(BufferBuilder b, ClientLevel level, Vec3 cam, float dt, float t, float rx, float ry, float rz, float ux, float uy, float uz) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		int q = 0;
		float day = CirrusSky.dayLevel(), gold = CirrusSky.goldLevel(), night = CirrusSky.nightLevel(), rain = CirrusSky.rainLevel();
		float light = 0.35F + 0.65F * Math.max(day, gold);
		boolean paused = mc.isPaused();
		if (paused) {
			dt = 0;
		}

		// ---------------------------------------------------------------- rings on shallow water
		boolean rp = SkySettings.on("walk_ripples") && player != null;
		if (rp && dt > 0) {
			scanT -= dt;
			if (scanT <= 0) {
				scanT = 0.25F;
				List<Entity> list = new ArrayList<>();
				list.add(player);
				for (Entity e : level.getEntities(player, new AABB(player.position(), player.position()).inflate(20, 8, 20), e -> e instanceof LivingEntity && e.isInWater())) {
					if (list.size() >= 12) {
						break;
					}
					list.add(e);
				}
				waders = list;
				wade.keySet().removeIf(id -> waders.stream().noneMatch(e -> e.getId() == id));
			}
			for (Entity e : waders) {
				if (e.isRemoved()) {
					continue;
				}
				double sy = surface(level, e);
				float[] s = wade.computeIfAbsent(e.getId(), k -> new float[]{0, 0, 0});
				if (Double.isNaN(sy)) {
					s[2] = 0;
					continue;
				}
				double mx = e.getX() - e.xo, mz = e.getZ() - e.zo;
				float speed = (float) Math.sqrt(mx * mx + mz * mz) * 20; // blocks per second
				float w = Math.max(0.5F, Math.min(1.4F, e.getBbWidth() * 1.6F));
				if (s[2] == 0) {
					// just stepped in: a splash ring
					ring(e.getX(), sy, e.getZ(), 1.1F * w, 0);
					ring(e.getX(), sy, e.getZ(), 0.7F * w, 0.22F);
					s[2] = 1;
				}
				s[0] += speed * dt;
				s[1] += dt;
				float step = speed > 4.5F ? 0.9F : 0.6F;
				if (s[0] > step) {
					s[0] = 0;
					s[1] = 0;
					double ox = speed > 0.1F ? mx / (speed / 20) * 0.25 : 0, oz = speed > 0.1F ? mz / (speed / 20) * 0.25 : 0;
					float sz = (speed > 4.5F ? 1.25F : 0.9F) * w;
					ring(e.getX() + ox, sy, e.getZ() + oz, sz, 0);
					ring(e.getX() + ox, sy, e.getZ() + oz, sz * 0.55F, 0.18F);
					if (speed > 4.5F && e == player) {
						// running: little droplets kicked up
						for (int i = 0; i < 3; i++) {
							P d = free(drops);
							if (d == null) {
								break;
							}
							double a = RNG.nextDouble() * Math.PI * 2;
							d.x = e.getX() + Math.cos(a) * 0.3;
							d.z = e.getZ() + Math.sin(a) * 0.3;
							d.y = sy + 0.05;
							d.vx = (float) Math.cos(a) * 0.8F + (float) mx * 8;
							d.vz = (float) Math.sin(a) * 0.8F + (float) mz * 8;
							d.vy = 1.6F + RNG.nextFloat() * 1.2F;
							d.age = 0;
							d.life = 0.7F;
							d.size = 0.035F + RNG.nextFloat() * 0.03F;
							d.alive = true;
						}
					}
				} else if (s[1] > 1.6F) {
					// standing still in the water: a slow, gentle ring now and then
					s[1] = RNG.nextFloat() * 0.6F;
					ring(e.getX(), sy, e.getZ(), 0.6F * w, 0);
				}
			}
		}
		int ringCol = Math.round(232 * light) << 16 | Math.round(244 * light) << 8 | Math.min(255, Math.round(255 * light + 20));
		for (P p : rings) {
			if (!p.alive) {
				continue;
			}
			if (!rp) {
				p.alive = false;
				continue;
			}
			p.age += dt;
			if (p.age > p.life) {
				p.alive = false;
				continue;
			}
			if (p.age < 0) {
				continue;
			}
			float k = p.age / p.life;
			float grow = 1 - (1 - k) * (1 - k); // fast at first, then easing out
			Effects.billboard(b, (float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z), 0.12F + p.size * grow, 1, 0, 0, 0, 0, 1, 8,
					argb((1 - k) * (1 - k) * 0.65F, ringCol));
			q++;
		}
		for (P p : drops) {
			if (!p.alive) {
				continue;
			}
			p.age += dt;
			if (p.age > p.life || !rp) {
				p.alive = false;
				continue;
			}
			p.vy -= 9.0F * dt;
			p.x += p.vx * dt;
			p.y += p.vy * dt;
			p.z += p.vz * dt;
			Effects.billboard(b, (float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z), p.size, rx, ry, rz, ux, uy, uz, 12,
					argb(0.8F * (1 - p.age / p.life), ringCol));
			q++;
		}

		// ---------------------------------------------------------------- grass and flowers kicked up
		boolean gk = SkySettings.on("grass_kick") && player != null;
		if (gk && dt > 0 && player.onGround()) {
			double mx = player.getX() - player.xo, mz = player.getZ() - player.zo;
			float speed = (float) Math.sqrt(mx * mx + mz * mz) * 20;
			if (speed > 2.0F) {
				BlockPos feet = BlockPos.containing(player.getX(), player.getY() + 0.1, player.getZ());
				BlockState s = level.getBlockState(feet);
				boolean flower = s.is(BlockTags.FLOWERS);
				boolean grass = flower || s.is(BlockTags.REPLACEABLE_BY_TREES) || s.is(Blocks.SHORT_GRASS) || s.is(Blocks.TALL_GRASS) || s.is(Blocks.FERN);
				float chance = (speed - 2.0F) * 0.5F * dt * (speed > 4.5F ? 2.2F : 1);
				if (grass && RNG.nextFloat() < chance * 6) {
					int n = 1 + (speed > 4.5F ? RNG.nextInt(2) : 0);
					for (int i = 0; i < n; i++) {
						P p = free(bits);
						if (p == null) {
							break;
						}
						boolean fly = night > 0.5F && SkySettings.fireflies && rain < 0.3F && RNG.nextFloat() < 0.45F;
						p.x = player.getX() + (RNG.nextDouble() - 0.5) * 0.8;
						p.z = player.getZ() + (RNG.nextDouble() - 0.5) * 0.8;
						p.y = player.getY() + 0.2 + RNG.nextDouble() * 0.4;
						p.age = 0;
						p.phase = RNG.nextFloat() * 6.28F;
						if (fly) {
							// a startled firefly lifting out of the grass
							p.state = 1;
							p.vx = (RNG.nextFloat() - 0.5F) * 0.8F;
							p.vz = (RNG.nextFloat() - 0.5F) * 0.8F;
							p.vy = 0.5F + RNG.nextFloat() * 0.5F;
							p.life = 4 + RNG.nextFloat() * 3;
							p.size = 0.12F + RNG.nextFloat() * 0.06F;
							p.color = RNG.nextFloat() < 0.7F ? 0xD8FF6A : 0xFFE27A;
						} else {
							p.state = 0;
							p.vx = (float) mx * 6 + (RNG.nextFloat() - 0.5F) * 1.2F;
							p.vz = (float) mz * 6 + (RNG.nextFloat() - 0.5F) * 1.2F;
							p.vy = 1.4F + RNG.nextFloat() * 1.3F;
							p.life = 1.2F + RNG.nextFloat() * 0.6F;
							p.size = 0.045F + RNG.nextFloat() * 0.03F;
							if (flower && RNG.nextFloat() < 0.7F) {
								int[] petals = {0xF7B8CC, 0xFFF1A8, 0xFFFFFF, 0xC9B8F2, 0xF2A65A};
								p.color = petals[RNG.nextInt(petals.length)];
							} else {
								int[] greens = {0x7DBE4C, 0x9CCB52, 0x6FAE48, 0xA8D25E};
								p.color = greens[RNG.nextInt(greens.length)];
							}
						}
						p.alive = true;
					}
				}
			}
		}
		for (P p : bits) {
			if (!p.alive) {
				continue;
			}
			p.age += dt;
			if (p.age > p.life || !gk) {
				p.alive = false;
				continue;
			}
			float env = Math.min(1, p.age / 0.15F) * Math.min(1, (p.life - p.age) / 0.5F);
			if (p.state == 1) {
				p.vx += (RNG.nextFloat() - 0.5F) * dt * 0.8F;
				p.vz += (RNG.nextFloat() - 0.5F) * dt * 0.8F;
				p.vy *= 1 - dt * 0.4F;
				p.x += p.vx * dt;
				p.y += p.vy * dt;
				p.z += p.vz * dt;
				float blink = 0.5F + 0.5F * (float) Math.sin(t * 2.6 + p.phase * 3);
				Effects.billboard(b, (float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z), p.size * (0.8F + 0.4F * blink), rx, ry, rz, ux, uy, uz, 0,
						argb(env * night * (0.3F + 0.7F * blink * blink), p.color));
			} else {
				p.vy -= 6.0F * dt;
				p.vx *= 1 - dt * 1.5F;
				p.vz *= 1 - dt * 1.5F;
				p.vy = Math.max(p.vy, -1.2F); // flutter down slowly
				p.x += p.vx * dt;
				p.y += p.vy * dt;
				p.z += p.vz * dt;
				float ang = t * 7 + p.phase;
				float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
				int c = shade(p.color, light);
				Effects.billboard(b, (float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z), p.size, ca, 0.4F * sa, sa, -sa * 0.2F, 0.5F, ca * 0.2F, 2, argb(env, c));
			}
			q++;
		}

		// ---------------------------------------------------------------- sparrows that fly off when approached
		boolean sb = SkySettings.on("startle_birds") && player != null;
		boolean daylight = (day > 0.3F || gold > 0.3F) && rain < 0.3F;
		boolean any = false;
		for (P p : birds) {
			any |= p.alive;
		}
		if (sb && dt > 0 && !any && daylight) {
			birdWait -= dt;
			if (birdWait <= 0) {
				birdWait = 4 + RNG.nextFloat() * 6; // try again soon if no spot was found
				if (spawnGroup(level, player)) {
					birdWait = 25 + RNG.nextFloat() * 35;
				}
			}
		}
		if (sb && any && dt > 0) {
			double dx = birdX - player.getX(), dz = birdZ - player.getZ();
			double dist = Math.sqrt(dx * dx + dz * dz);
			double mx = player.getX() - player.xo, mz = player.getZ() - player.zo;
			float speed = (float) Math.sqrt(mx * mx + mz * mz) * 20;
			float scare = speed > 4.5F ? 8.5F : speed > 0.5F ? 5.0F : 3.0F;
			if (!flockUp && (dist < scare || !daylight)) {
				flockUp = true;
				for (P p : birds) {
					if (!p.alive) {
						continue;
					}
					p.state = 1;
					p.delay = RNG.nextFloat() * 0.35F;
					double ax = p.x - player.getX(), az = p.z - player.getZ();
					double al = Math.sqrt(ax * ax + az * az) + 1e-3;
					float spread = (RNG.nextFloat() - 0.5F) * 1.1F;
					double cx = ax / al, cz = az / al;
					double fx = cx * Math.cos(spread) - cz * Math.sin(spread), fz = cx * Math.sin(spread) + cz * Math.cos(spread);
					float v = 5.5F + RNG.nextFloat() * 2.5F;
					p.vx = (float) fx * v;
					p.vz = (float) fz * v;
					p.vy = 2.6F + RNG.nextFloat() * 1.4F;
					p.age = 0;
					p.life = 6;
				}
				float vol = SkySettings.ambientSounds ? Math.max(0.15F, SkySettings.ambientVolume) : 0;
				if (vol > 0) {
					mc.getSoundManager().play(new SimpleSoundInstance(FLUTTER, SoundSource.AMBIENT, 0.55F * vol, 1.35F + RNG.nextFloat() * 0.2F,
							SoundInstance.createUnseededRandom(), birdX, player.getY() + 0.5, birdZ));
					mc.getSoundManager().play(new SimpleSoundInstance(CHIRP, SoundSource.AMBIENT, 0.35F * vol, 1.15F + RNG.nextFloat() * 0.2F,
							SoundInstance.createUnseededRandom(), birdX, player.getY() + 1.5, birdZ));
				}
			}
			if (dist > 48) {
				for (P p : birds) {
					p.alive = false;
				}
			}
		}
		for (P p : birds) {
			if (!p.alive) {
				continue;
			}
			if (!sb) {
				p.alive = false;
				continue;
			}
			float bx = (float) (p.x - cam.x), by = (float) (p.y - cam.y), bz = (float) (p.z - cam.z);
			int lc = Math.round(255 * light);
			if (p.state == 0) {
				// on the ground: peck, look around, hop now and then
				p.age += dt;
				p.delay -= dt;
				if (p.delay <= 0) {
					p.delay = 0.6F + RNG.nextFloat() * 1.8F;
					if (RNG.nextFloat() < 0.35F) {
						p.vy = 2.2F; // a little hop
						p.vx = (RNG.nextFloat() - 0.5F) * 1.2F;
						p.vz = (RNG.nextFloat() - 0.5F) * 1.2F;
						if (RNG.nextFloat() < 0.5F) {
							p.color ^= 1; // turn around
						}
					}
				}
				if (p.vy != 0 || p.y > p.phase) {
					p.vy -= 12 * dt;
					p.x += p.vx * dt;
					p.z += p.vz * dt;
					p.y += p.vy * dt;
					if (p.vy < 0 && p.y <= p.phase) {
						p.y = p.phase;
						p.vy = 0;
						p.vx = p.vz = 0;
					}
				}
				float peck = Math.max(0, (float) Math.sin(p.age * 5 + p.size * 7));
				peck = peck > 0.75F ? (peck - 0.75F) * 4 : 0;
				float fade = Math.min(1, p.age / 0.8F);
				Effects.billboard(b, bx, by + 0.13F, bz, 0.13F, rx, ry, rz, ux, uy, uz, 30,
						argb(fade, Math.round(peck * 255) << 16 | ((p.color & 1) * 255) << 8 | lc));
			} else {
				p.delay -= dt;
				if (p.delay > 0) {
					Effects.billboard(b, bx, by + 0.13F, bz, 0.13F, rx, ry, rz, ux, uy, uz, 30, argb(1, ((p.color & 1) * 255) << 8 | lc));
				} else {
					p.age += dt;
					if (p.age > p.life) {
						p.alive = false;
						continue;
					}
					p.vy *= 1 - dt * 0.35F;
					p.x += p.vx * dt;
					p.y += p.vy * dt;
					p.z += p.vz * dt;
					float hl = (float) Math.sqrt(p.vx * p.vx + p.vz * p.vz) + 1e-3F;
					float fx = p.vx / hl, fz = p.vz / hl;
					float flap = (float) Math.sin(t * 24 + p.size * 40);
					int seed = Math.round((flap * 0.5F + 0.5F) * 255) << 16;
					float fade = Math.min(1, (p.life - p.age) / 1.5F);
					// wings spread across the flight direction, tilted a little toward the climb
					Effects.billboard(b, bx, by + 0.15F, bz, 0.22F, -fz, 0, fx, fx * 0.45F, 0.3F, fz * 0.45F, 14, argb(fade, seed | 0x3A40));
				}
			}
			q++;
		}
		return q;
	}

	private static int shade(int rgb, float k) {
		int r = Math.round((rgb >> 16 & 0xFF) * k), g = Math.round((rgb >> 8 & 0xFF) * k), bl = Math.round((rgb & 0xFF) * k);
		return r << 16 | g << 8 | bl;
	}

	/** a few sparrows on open grass (or a tree top) somewhere ahead-ish, 10 to 24 blocks away */
	private static boolean spawnGroup(ClientLevel level, LocalPlayer player) {
		for (int tries = 0; tries < 6; tries++) {
			double a = RNG.nextDouble() * Math.PI * 2, r = 10 + RNG.nextDouble() * 14;
			int x = (int) Math.floor(player.getX() + Math.cos(a) * r), z = (int) Math.floor(player.getZ() + Math.sin(a) * r);
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
			if (Math.abs(y - player.getY()) > 10) {
				continue;
			}
			BlockState ground = level.getBlockState(new BlockPos(x, y - 1, z));
			if (!ground.is(Blocks.GRASS_BLOCK) && !ground.is(BlockTags.LEAVES) && !ground.is(Blocks.DIRT_PATH) && !ground.is(Blocks.FARMLAND)) {
				continue;
			}
			if (!level.getFluidState(new BlockPos(x, y, z)).isEmpty()) {
				continue;
			}
			birdX = x + 0.5;
			birdZ = z + 0.5;
			flockUp = false;
			int n = 3 + RNG.nextInt(4);
			for (int i = 0; i < birds.length; i++) {
				P p = birds[i];
				p.alive = i < n;
				if (!p.alive) {
					continue;
				}
				double bx = birdX + (RNG.nextDouble() - 0.5) * 3, bz = birdZ + (RNG.nextDouble() - 0.5) * 3;
				int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(bx), (int) Math.floor(bz));
				if (Math.abs(gy - y) > 1) {
					bx = birdX;
					bz = birdZ;
					gy = y;
				}
				p.x = bx;
				p.z = bz;
				p.y = gy;
				p.phase = gy; // ground height to land on after a hop
				p.vx = p.vy = p.vz = 0;
				p.state = 0;
				p.age = 0;
				p.delay = RNG.nextFloat() * 2;
				p.size = RNG.nextFloat();
				p.color = RNG.nextInt(2);
			}
			return true;
		}
		return false;
	}
}
