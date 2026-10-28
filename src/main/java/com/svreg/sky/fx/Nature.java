package com.svreg.sky.fx;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.svreg.sky.sky.CirrusSky;
import com.svreg.sky.sky.SkySettings;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * More ambient life: butterflies over flowers, light motes under trees, rain ripples, shore foam, dawn steam on water,
 * smoke from campfires and furnaces, big soft snowflakes, and warm window glow at night.
 * A rolling scan of the blocks around the player (a slice per frame) finds where each effect can appear.
 */
public final class Nature {
	static final int MAX_QUADS = 520;
	private static final Random RNG = new Random();
	private static final int RX = 16, RY = 8;

	// rolling scan
	private static int scanIndex;
	private static BlockPos scanOrigin = BlockPos.ZERO;
	private static List<BlockPos> sFlowers = new ArrayList<>(), sLeafAir = new ArrayList<>(), sWater = new ArrayList<>(),
			sShore = new ArrayList<>(), sSmoke = new ArrayList<>(), sWindows = new ArrayList<>(), sGround = new ArrayList<>();
	private static List<BlockPos> flowers = List.of(), leafAir = List.of(), water = List.of(), shore = List.of(), smoke = List.of(), windows = List.of(),
			ground = List.of();
	private static int sLeaves, leaves;
	private static final List<Direction> windowSides = new ArrayList<>();
	private static List<Direction> sWindowSides = new ArrayList<>();

	private static final class P {
		double x, y, z;
		float vx, vy, vz, age, life, phase, size;
		int color;
		boolean alive;
	}

	private static final P[] butterflies = make(10), motes = make(30), ripples = make(30), foam = make(40), steam = make(20),
			smokeP = make(36), snow = make(90), dragonflies = make(5), seeds = make(28), fish = make(3), puddles = make(36), glints = make(40);
	private static final P[] flock = make(9);
	private static float flockT = -1, flockDirX, flockDirZ, flockAlt, flockSide;
	private static double flockCx, flockCz;
	private static float nextFlock = 20;

	private static P[] make(int n) {
		P[] a = new P[n];
		for (int i = 0; i < n; i++) {
			a[i] = new P();
		}
		return a;
	}

	private Nature() {
	}

	static boolean anyOn() {
		for (String k : new String[]{"butterflies", "light_motes", "rain_ripples", "shore_foam", "water_steam", "smoke_wisps", "ghibli_snow", "window_glow",
				"bird_flocks", "dragonflies", "dandelion_seeds", "fish_jumps", "puddles", "sky_reflection", "leaf_rustle"}) {
			if (SkySettings.on(k)) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ scan
	private static void scan(ClientLevel level, Vec3 cam) {
		int sx = RX * 2 + 1, sy = RY * 2 + 1, total = sx * sx * sy;
		int per = total / 40 + 1;
		if (scanIndex == 0) {
			scanOrigin = BlockPos.containing(cam);
			sFlowers = new ArrayList<>(); sLeafAir = new ArrayList<>(); sWater = new ArrayList<>(); sShore = new ArrayList<>();
			sSmoke = new ArrayList<>(); sWindows = new ArrayList<>(); sWindowSides = new ArrayList<>(); sGround = new ArrayList<>();
			sLeaves = 0;
		}
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int n = 0; n < per && scanIndex < total; n++, scanIndex++) {
			int i = scanIndex;
			int x = i % sx - RX, z = (i / sx) % sx - RX, y = i / (sx * sx) - RY;
			m.set(scanOrigin.getX() + x, scanOrigin.getY() + y, scanOrigin.getZ() + z);
			BlockState s = level.getBlockState(m);
			if (s.isAir()) {
				if (sLeafAir.size() < 60 && (x * 7 + z * 13) % 3 == 0) {
					for (int k = 2; k <= 6; k++) {
						if (level.getBlockState(m.above(k)).is(BlockTags.LEAVES)) {
							sLeafAir.add(m.immutable());
							break;
						}
					}
				}
				continue;
			}
			if (s.is(BlockTags.LEAVES)) {
				sLeaves++;
				continue;
			}
			if (sGround.size() < 90 && (x * 5 + z * 11) % 4 == 0 && s.isSolidRender() && level.getBlockState(m.above()).isAir()
					&& level.canSeeSky(m.above())) {
				sGround.add(m.immutable());
			}
			if (s.is(BlockTags.FLOWERS) || s.is(BlockTags.SMALL_FLOWERS)) {
				if (sFlowers.size() < 80) sFlowers.add(m.immutable());
			} else if ((s.is(Blocks.CAMPFIRE) || s.is(Blocks.SOUL_CAMPFIRE) || s.is(Blocks.FURNACE) || s.is(Blocks.SMOKER) || s.is(Blocks.BLAST_FURNACE))
					&& s.hasProperty(BlockStateProperties.LIT) && s.getValue(BlockStateProperties.LIT)) {
				if (sSmoke.size() < 16) sSmoke.add(m.immutable());
			} else if (s.is(BlockTags.IMPERMEABLE) || s.getBlock().getDescriptionId().contains("glass_pane")) {
				if (sWindows.size() < 40) {
					for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
						BlockPos inside = m.relative(d);
						BlockPos outside = m.relative(d.getOpposite());
						if (level.getBrightness(LightLayer.BLOCK, inside) >= 9 && level.getBrightness(LightLayer.SKY, outside) >= 10
								&& level.getBlockState(inside).isAir()) {
							sWindows.add(m.immutable());
							sWindowSides.add(d.getOpposite());
							break;
						}
					}
				}
			} else if (level.getFluidState(m).is(FluidTags.WATER) && level.getBlockState(m.above()).isAir()) {
				if (sWater.size() < 120) sWater.add(m.immutable());
				if (sShore.size() < 80) {
					for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
						BlockState nb = level.getBlockState(m.relative(d));
						if (!nb.isAir() && !level.getFluidState(m.relative(d)).is(FluidTags.WATER)) {
							sShore.add(m.immutable());
							break;
						}
					}
				}
			}
		}
		if (scanIndex >= total) {
			scanIndex = 0;
			flowers = sFlowers; leafAir = sLeafAir; water = sWater; shore = sShore; smoke = sSmoke; windows = sWindows;
			ground = sGround; leaves = sLeaves;
			windowSides.clear();
			windowSides.addAll(sWindowSides);
		}
	}

	/** how leafy it is around the player (0..1), for the leaf rustle sound */
	public static float leafiness() {
		return Math.min(1, leaves / 220F);
	}

	private static BlockPos pick(List<BlockPos> list) {
		return list.isEmpty() ? null : list.get(RNG.nextInt(list.size()));
	}

	private static void flat(BufferBuilder b, Vec3 cam, double x, double y, double z, float size, float u0, int argb) {
		Effects.billboard(b, (float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z), size, 1, 0, 0, 0, 0, 1, u0, argb);
	}

	private static void bill(BufferBuilder b, Vec3 cam, double x, double y, double z, float size, float rx, float ry, float rz, float ux, float uy, float uz, float u0, int argb) {
		Effects.billboard(b, (float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z), size, rx, ry, rz, ux, uy, uz, u0, argb);
	}

	private static int argb(float a, int rgb) {
		return Math.round(Math.max(0, Math.min(1, a)) * 255) << 24 | rgb;
	}

	// ------------------------------------------------------------------ emit
	static int emit(BufferBuilder b, ClientLevel level, Vec3 cam, float dt, float t, float rx, float ry, float rz, float ux, float uy, float uz) {
		if (!anyOn()) {
			return 0;
		}
		scan(level, cam);
		int q = 0;
		float day = CirrusSky.dayLevel(), night = CirrusSky.nightLevel(), gold = CirrusSky.goldLevel(), rain = CirrusSky.rainLevel();
		float wind = SkySettings.windStrength;

		// butterflies: daytime, over flowers
		boolean bf = SkySettings.on("butterflies") && day > 0.4F && rain < 0.2F;
		for (P p : butterflies) {
			if (!bf) { p.alive = false; continue; }
			if (!p.alive || p.age > p.life) {
				BlockPos f = pick(flowers);
				if (f == null || RNG.nextFloat() > 0.02F) { p.alive = false; continue; }
				p.x = f.getX() + 0.5; p.y = f.getY() + 0.6; p.z = f.getZ() + 0.5;
				p.life = 10 + RNG.nextFloat() * 10; p.age = 0; p.phase = RNG.nextFloat() * 6.28F;
				int[] cols = {0xFFFFFF, 0xFFE070, 0xFFA64A, 0x9CC8FF, 0xF6B8E0};
				p.color = cols[RNG.nextInt(cols.length)]; p.size = 0.13F; p.alive = true;
			}
			p.age += dt;
			float wander = t * 0.7F + p.phase;
			p.vx = (float) Math.cos(wander) * 0.8F + 0.3F * wind; p.vz = (float) Math.sin(wander * 1.3) * 0.8F;
			p.vy = (float) Math.sin(t * 2.1 + p.phase) * 0.6F;
			p.x += p.vx * dt; p.y += p.vy * dt; p.z += p.vz * dt;
			float flap = (float) Math.abs(Math.sin(t * 16 + p.phase));
			float env = Math.min(1, p.age) * Math.min(1, p.life - p.age);
			// wings open/close: shrink the quad's width; face the direction of travel
			float hx = (float) Math.cos(wander), hz = (float) Math.sin(wander * 1.3);
			float hl = (float) Math.sqrt(hx * hx + hz * hz) + 1e-4F;
			bill(b, cam, p.x, p.y, p.z, p.size, -hz / hl * (0.3F + 0.7F * flap), 0, hx / hl * (0.3F + 0.7F * flap), 0, 1, 0, 6, argb(env, p.color));
			q++;
		}

		// light motes: drifting golden dust in the shade of trees, by day
		boolean mo = SkySettings.on("light_motes") && day > 0.3F;
		for (P p : motes) {
			if (!mo) { p.alive = false; continue; }
			if (!p.alive || p.age > p.life) {
				BlockPos a = pick(leafAir);
				if (a == null || RNG.nextFloat() > 0.1F) { p.alive = false; continue; }
				p.x = a.getX() + RNG.nextDouble(); p.y = a.getY() + RNG.nextDouble() * 3; p.z = a.getZ() + RNG.nextDouble();
				p.life = 5 + RNG.nextFloat() * 6; p.age = 0; p.phase = RNG.nextFloat() * 6.28F; p.size = 0.035F + RNG.nextFloat() * 0.03F; p.alive = true;
			}
			p.age += dt;
			p.x += (Math.sin(t * 0.5 + p.phase) * 0.08 + 0.05 * wind) * dt; p.y += Math.sin(t * 0.7 + p.phase * 2) * 0.05 * dt; p.z += Math.cos(t * 0.4 + p.phase) * 0.08 * dt;
			float tw = 0.5F + 0.5F * (float) Math.sin(t * 3 + p.phase * 5);
			float env = Math.min(1, p.age / 1.5F) * Math.min(1, (p.life - p.age) / 1.5F);
			bill(b, cam, p.x, p.y, p.z, p.size, rx, ry, rz, ux, uy, uz, 0, argb(env * (0.35F + 0.5F * tw) * day, gold > 0.3F ? 0xFFD890 : 0xFFF4C8));
			q++;
		}

		// rain ripples on water
		boolean rp = SkySettings.on("rain_ripples") && rain > 0.2F;
		for (P p : ripples) {
			if (!rp) { p.alive = false; continue; }
			if (!p.alive || p.age > p.life) {
				BlockPos w = pick(water);
				if (w == null || RNG.nextFloat() > 0.5F * rain) { p.alive = false; continue; }
				p.x = w.getX() + RNG.nextDouble(); p.y = w.getY() + 0.9; p.z = w.getZ() + RNG.nextDouble();
				p.life = 0.5F + RNG.nextFloat() * 0.4F; p.age = 0; p.alive = true;
			}
			p.age += dt;
			float k = p.age / p.life;
			flat(b, cam, p.x, p.y, p.z, 0.08F + 0.35F * k, 8, argb((1 - k) * 0.55F, 0xE8F4FF));
			q++;
		}

		// shore foam: soft white lumps that breathe along the waterline
		boolean fo = SkySettings.on("shore_foam");
		for (P p : foam) {
			if (!fo) { p.alive = false; continue; }
			if (!p.alive || p.age > p.life) {
				BlockPos s = pick(shore);
				if (s == null || RNG.nextFloat() > 0.3F) { p.alive = false; continue; }
				p.x = s.getX() + 0.15 + RNG.nextDouble() * 0.7; p.y = s.getY() + 0.885; p.z = s.getZ() + 0.15 + RNG.nextDouble() * 0.7;
				p.life = 3 + RNG.nextFloat() * 4; p.age = 0; p.size = 0.25F + RNG.nextFloat() * 0.25F; p.phase = RNG.nextFloat(); p.alive = true;
			}
			p.age += dt;
			float k = p.age / p.life;
			float breathe = 0.85F + 0.15F * (float) Math.sin(t * 1.6 + p.phase * 10);
			int seedColor = Math.round(p.phase * 255) << 16 | 0xFFFF; // red channel seeds the blob shape
			flat(b, cam, p.x, p.y, p.z, p.size * breathe, 10, argb((float) Math.sin(Math.PI * k) * 0.7F * (0.6F + 0.4F * Math.max(day, 0.3F)), seedColor));
			q++;
		}

		// steam over water at dawn
		org.joml.Vector3f sun = CirrusSky.sunDirection();
		float dawn = sun.y > -0.2F && sun.y < 0.35F && sun.x > 0 ? 1 : 0;
		boolean st = SkySettings.on("water_steam") && dawn > 0 && SkySettings.morningMist > 0;
		for (P p : steam) {
			if (!st) { p.alive = false; continue; }
			if (!p.alive || p.age > p.life) {
				BlockPos w = pick(water);
				if (w == null || RNG.nextFloat() > 0.1F) { p.alive = false; continue; }
				p.x = w.getX() + RNG.nextDouble(); p.y = w.getY() + 1.0; p.z = w.getZ() + RNG.nextDouble();
				p.life = 4 + RNG.nextFloat() * 4; p.age = 0; p.size = 0.6F + RNG.nextFloat() * 0.6F; p.phase = RNG.nextFloat() * 6.28F; p.alive = true;
			}
			p.age += dt;
			p.y += 0.12 * dt; p.x += (0.1 * wind + Math.sin(t * 0.3 + p.phase) * 0.05) * dt;
			float k = p.age / p.life;
			bill(b, cam, p.x, p.y, p.z, p.size * (0.8F + 0.6F * k), rx, ry, rz, ux, uy, uz, 0, argb((float) Math.sin(Math.PI * k) * 0.22F * SkySettings.morningMist, 0xF2F4F8));
			q++;
		}

		// smoke from campfires and lit furnaces: soft puffs that rise and lean with the wind
		boolean sm = SkySettings.on("smoke_wisps");
		for (P p : smokeP) {
			if (!sm) { p.alive = false; continue; }
			if (!p.alive || p.age > p.life) {
				BlockPos s = pick(smoke);
				if (s == null || RNG.nextFloat() > 0.15F) { p.alive = false; continue; }
				p.x = s.getX() + 0.5; p.y = s.getY() + 1.1; p.z = s.getZ() + 0.5;
				p.life = 4 + RNG.nextFloat() * 3; p.age = 0; p.size = 0.25F; p.phase = RNG.nextFloat() * 6.28F; p.alive = true;
			}
			p.age += dt;
			float k = p.age / p.life;
			p.y += (0.9 - 0.4 * k) * dt;
			p.x += (0.943 * wind * 0.6 * k + Math.sin(t * 1.2 + p.phase) * 0.15) * dt; p.z += (0.333 * wind * 0.6 * k) * dt;
			bill(b, cam, p.x, p.y, p.z, p.size * (1 + 2.5F * k), rx, ry, rz, ux, uy, uz, 0, argb((1 - k) * 0.45F, night > 0.5F ? 0x9AA0B0 : 0xD8D8DC));
			q++;
		}

		// big soft snowflakes in snowy weather
		BlockPos at = BlockPos.containing(cam);
		boolean snowing = SkySettings.on("ghibli_snow") && rain > 0.2F && level.getBiome(at).value().coldEnoughToSnow(at, level.getSeaLevel());
		for (P p : snow) {
			if (!snowing) { p.alive = false; continue; }
			if (!p.alive || p.age > p.life) {
				p.x = cam.x + (RNG.nextDouble() - 0.5) * 24; p.y = cam.y + 2 + RNG.nextDouble() * 10; p.z = cam.z + (RNG.nextDouble() - 0.5) * 24;
				p.life = 6 + RNG.nextFloat() * 4; p.age = 0; p.size = 0.05F + RNG.nextFloat() * 0.05F; p.phase = RNG.nextFloat() * 6.28F; p.alive = true;
			}
			p.age += dt;
			p.y -= 0.9 * dt; p.x += (Math.sin(t * 1.1 + p.phase) * 0.3 + 0.943 * wind * 0.5) * dt; p.z += (Math.cos(t * 0.9 + p.phase) * 0.3 + 0.333 * wind * 0.5) * dt;
			float env = Math.min(1, p.age) * Math.min(1, (p.life - p.age));
			bill(b, cam, p.x, p.y, p.z, p.size, rx, ry, rz, ux, uy, uz, 12, argb(env * 0.9F * rain, 0xFFFFFF));
			q++;
		}

		// warm window glow at night: lit rooms shine out through glass
		if (SkySettings.on("window_glow") && night > 0.2F) {
			for (int i = 0; i < windows.size() && i < windowSides.size(); i++) {
				BlockPos w = windows.get(i);
				Direction out = windowSides.get(i);
				double x = w.getX() + 0.5 + out.getStepX() * 0.52, y = w.getY() + 0.5, z = w.getZ() + 0.5 + out.getStepZ() * 0.52;
				float flick = 0.9F + 0.1F * (float) Math.sin(t * 3 + i);
				// a soft glow facing out of the window
				float wx = out.getStepZ(), wz = -out.getStepX();
				bill(b, cam, x, y, z, 0.75F, wx, 0, wz, 0, 1, 0, 0, argb(night * 0.45F * flick, 0xFFB45A));
				q++;
			}
		}

		q += emitMore(b, level, cam, dt, t, rx, ry, rz, ux, uy, uz, day, night, gold, rain, wind);
		return q;
	}

	private static int emitMore(BufferBuilder b, ClientLevel level, Vec3 cam, float dt, float t, float rx, float ry, float rz, float ux, float uy, float uz,
								float day, float night, float gold, float rain, float wind) {
		int q = 0;
		org.joml.Vector3f sun = CirrusSky.sunDirection();

		// bird flocks: a loose V of birds crossing the sky now and then (by day and at dusk)
		boolean birds = SkySettings.on("bird_flocks") && (day > 0.3F || gold > 0.2F) && rain < 0.4F;
		if (flockT < 0) {
			nextFlock -= dt;
			if (birds && nextFlock <= 0) {
				nextFlock = 50 + RNG.nextFloat() * 90;
				double a = RNG.nextDouble() * Math.PI * 2;
				flockDirX = (float) Math.cos(a); flockDirZ = (float) Math.sin(a);
				flockSide = RNG.nextFloat() * 70 - 35;
				flockAlt = 30 + RNG.nextFloat() * 25;
				flockCx = cam.x - flockDirX * 160 - flockDirZ * flockSide; flockCz = cam.z - flockDirZ * 160 + flockDirX * flockSide;
				flockT = 0;
				int n = 5 + RNG.nextInt(5);
				for (int i = 0; i < flock.length; i++) {
					P p = flock[i];
					p.alive = i < n;
					int row = (i + 1) / 2, side = i % 2 == 0 ? 1 : -1;
					p.vx = i == 0 ? 0 : side * row * 2.2F; // sideways offset in the V
					p.vz = -row * 2.0F;                    // trailing behind the leader
					p.phase = RNG.nextFloat() * 6.28F;
					p.size = 0.55F + RNG.nextFloat() * 0.2F;
				}
			}
		} else {
			flockT += dt;
			float speed = 9F;
			double cx = flockCx + flockDirX * flockT * speed, cz = flockCz + flockDirZ * flockT * speed;
			if (flockT > 36 || !birds) {
				flockT = -1;
			}
			int col = gold > 0.3F ? 0x3A2E3C : 0x2E3448;
			for (P p : flock) {
				if (!p.alive || flockT < 0) continue;
				double x = cx + flockDirX * p.vz - flockDirZ * p.vx;
				double z = cz + flockDirZ * p.vz + flockDirX * p.vx;
				double y = cam.y + flockAlt + Math.sin(t * 0.6 + p.phase) * 0.6;
				float flap = (float) Math.sin(t * 9 + p.phase);
				int seed = Math.round((flap * 0.5F + 0.5F) * 255) << 16; // red carries the wing beat
				float fade = Math.min(1, flockT / 3) * Math.min(1, (36 - flockT) / 3);
				// horizontal wings across the flight direction, seen from below
				bill(b, cam, x, y, z, p.size, -flockDirZ, 0, flockDirX, flockDirX * 0.5F, 0.25F, flockDirZ * 0.5F, 14, argb(fade * 0.9F, (col & 0xFFFF) | seed));
				q++;
			}
		}

		// dragonflies: dart and hover over water by day
		boolean df = SkySettings.on("dragonflies") && day > 0.4F && rain < 0.2F;
		for (P p : dragonflies) {
			if (!df) { p.alive = false; continue; }
			if (!p.alive || p.age > p.life) {
				BlockPos w = pick(water);
				if (w == null || RNG.nextFloat() > 0.01F) { p.alive = false; continue; }
				p.x = w.getX() + 0.5; p.y = w.getY() + 1.4 + RNG.nextDouble() * 0.6; p.z = w.getZ() + 0.5;
				p.life = 14 + RNG.nextFloat() * 12; p.age = 0; p.phase = 0; p.vx = p.vz = 0; p.alive = true;
				int[] cols = {0x3FA7C9, 0x4FC38A, 0xD9573A};
				p.color = cols[RNG.nextInt(cols.length)];
			}
			p.age += dt;
			p.phase -= dt;
			if (p.phase <= 0) { // pick a new dart
				p.phase = 0.6F + RNG.nextFloat() * 1.6F;
				double a = RNG.nextDouble() * Math.PI * 2;
				float sp = RNG.nextFloat() < 0.5F ? 0 : 2.5F + RNG.nextFloat() * 2;
				p.vx = (float) Math.cos(a) * sp; p.vz = (float) Math.sin(a) * sp;
			}
			p.vx *= 0.92F; p.vz *= 0.92F;
			p.x += p.vx * dt; p.z += p.vz * dt; p.y += Math.sin(t * 3 + p.life) * 0.15 * dt;
			float hl = (float) Math.sqrt(p.vx * p.vx + p.vz * p.vz);
			float hx = hl > 0.05F ? p.vx / hl : 1, hz = hl > 0.05F ? p.vz / hl : 0;
			float env = Math.min(1, p.age) * Math.min(1, p.life - p.age);
			int flutter = Math.round((0.5F + 0.5F * (float) Math.sin(t * 40)) * 255) << 16;
			// lies flat: long axis along travel, wings across
			bill(b, cam, p.x, p.y, p.z, 0.16F, hx, 0, hz, -hz, 0, hx, 16, argb(env, (p.color & 0xFFFF) | flutter));
			q++;
		}

		// dandelion seeds: tiny fluffy parachutes drifting up and away with the wind
		boolean ds = SkySettings.on("dandelion_seeds") && day > 0.4F && rain < 0.2F;
		for (P p : seeds) {
			if (!ds) { p.alive = false; continue; }
			if (!p.alive || p.age > p.life) {
				if (RNG.nextFloat() > 0.02F + 0.03F * Math.min(wind, 2)) { p.alive = false; continue; }
				BlockPos g = pick(ground);
				if (g == null) { p.alive = false; continue; }
				p.x = g.getX() + RNG.nextDouble(); p.y = g.getY() + 1.2; p.z = g.getZ() + RNG.nextDouble();
				p.life = 8 + RNG.nextFloat() * 8; p.age = 0; p.phase = RNG.nextFloat() * 6.28F; p.size = 0.05F + RNG.nextFloat() * 0.03F; p.alive = true;
			}
			p.age += dt;
			p.x += (0.943 * (0.3 + wind * 0.9) + Math.sin(t * 0.8 + p.phase) * 0.25) * dt;
			p.z += (0.333 * (0.3 + wind * 0.9) + Math.cos(t * 0.7 + p.phase) * 0.25) * dt;
			p.y += (0.18 + Math.sin(t * 1.3 + p.phase) * 0.25) * dt;
			float env = Math.min(1, p.age) * Math.min(1, (p.life - p.age) / 2);
			bill(b, cam, p.x, p.y, p.z, p.size, rx, ry, rz, ux, uy, uz, 18, argb(env * 0.95F, 0xFFFFFF));
			q++;
		}

		// fish: now and then one leaps out of open water in a little arc
		boolean fj = SkySettings.on("fish_jumps") && night < 0.8F;
		for (P p : fish) {
			if (!fj) { p.alive = false; continue; }
			if (!p.alive || p.age > p.life) {
				BlockPos w = pick(water);
				if (w == null || water.size() < 12 || RNG.nextFloat() > 0.004F) { p.alive = false; continue; }
				double a = RNG.nextDouble() * Math.PI * 2;
				p.vx = (float) Math.cos(a) * 1.4F; p.vz = (float) Math.sin(a) * 1.4F;
				p.x = w.getX() + 0.5; p.y = w.getY() + 0.85; p.z = w.getZ() + 0.5;
				p.life = 0.9F; p.age = 0; p.alive = true; p.size = 0.14F + RNG.nextFloat() * 0.06F;
			}
			p.age += dt;
			float k = p.age / p.life;
			double fx = p.x + p.vx * p.age, fz = p.z + p.vz * p.age;
			double fy = p.y + Math.sin(Math.PI * k) * 0.9;
			float vy = (float) Math.cos(Math.PI * k) * 2.8F;
			float hl = (float) Math.sqrt(p.vx * p.vx + p.vz * p.vz + vy * vy);
			// body along the arc, upright (side view)
			bill(b, cam, fx, fy, fz, p.size, p.vx / hl, vy / hl, p.vz / hl, -vy / hl * p.vx / 1.4F, 1.4F / hl, -vy / hl * p.vz / 1.4F, 20, argb(1, 0x9FB6C2));
			q++;
			// splash rings where it leaves and where it lands
			float r1 = Math.min(1, p.age / 0.8F);
			flat(b, cam, p.x, p.y + 0.03, p.z, 0.1F + 0.5F * r1, 8, argb((1 - r1) * 0.6F, 0xE8F4FF));
			float r2 = Math.max(0, k - 0.95F) / 0.05F;
			flat(b, cam, p.x + p.vx * p.life, p.y + 0.03, p.z + p.vz * p.life, 0.12F + 0.2F * r2, 8, argb(k > 0.95F ? 0.6F : 0, 0xE8F4FF));
			q += 2;
		}

		// puddles: shiny patches on open ground while it rains and for a while after
		float wetK = CirrusSky.wetness();
		boolean pd = SkySettings.on("puddles") && wetK > 0.15F;
		for (int i = 0; i < puddles.length; i++) {
			P p = puddles[i];
			if (!pd) { p.alive = false; continue; }
			if (!p.alive) {
				BlockPos g = pick(ground);
				if (g == null || RNG.nextFloat() > 0.05F) continue;
				p.x = g.getX() + 0.2 + RNG.nextDouble() * 0.6; p.y = g.getY() + 1.012; p.z = g.getZ() + 0.2 + RNG.nextDouble() * 0.6;
				p.size = 0.35F + RNG.nextFloat() * 0.5F; p.phase = RNG.nextFloat(); p.alive = true;
				if (Math.abs(p.x - cam.x) > 20 || Math.abs(p.z - cam.z) > 20) { p.alive = false; continue; }
			}
			if (Math.abs(p.x - cam.x) > 24 || Math.abs(p.z - cam.z) > 24) { p.alive = false; continue; }
			int sky = night > 0.5F ? 0x2A3658 : (gold > 0.3F ? 0xE0A07A : 0x9CC4E8);
			int seed = Math.round(p.phase * 255) << 16;
			flat(b, cam, p.x, p.y, p.z, p.size * Math.min(1, wetK * 1.4F), 22, argb(Math.min(1, (wetK - 0.15F) * 3) * 0.55F, (sky & 0xFFFF) | seed));
			q++;
		}

		// the sun's (or moon's) glittering path on the water, toward the low sun or the moon
		boolean gl = SkySettings.on("sky_reflection") && !water.isEmpty() && rain < 0.5F;
		org.joml.Vector3f light = night > 0.5F ? new org.joml.Vector3f(-sun.x, -sun.y, -sun.z) : sun;
		float lowK = light.y > 0.02F && light.y < 0.55F ? 1 - smooth(0.25F, 0.55F, light.y) : 0;
		float gh = (float) Math.sqrt(light.x * light.x + light.z * light.z) + 1e-4F;
		float lx = light.x / gh, lz = light.z / gh;
		int pathCol = night > 0.5F ? 0xDCE8FF : (gold > 0.3F ? 0xFFC070 : 0xFFF2D0);
		for (P p : glints) {
			if (!gl || lowK <= 0) { p.alive = false; continue; }
			if (!p.alive || p.age > p.life) {
				BlockPos w = pick(water);
				if (w == null) { p.alive = false; continue; }
				double dx = w.getX() + 0.5 - cam.x, dz = w.getZ() + 0.5 - cam.z;
				double d = Math.sqrt(dx * dx + dz * dz) + 1e-4;
				// only on the line from the eye toward the light
				double along = (dx * lx + dz * lz) / d;
				if (along < 0.93 || d < 3) { p.alive = false; continue; }
				p.x = w.getX() + RNG.nextDouble(); p.y = w.getY() + 0.9; p.z = w.getZ() + RNG.nextDouble();
				p.life = 0.6F + RNG.nextFloat() * 1.2F; p.age = 0; p.size = 0.25F + (float) d * 0.02F; p.alive = true;
			}
			p.age += dt;
			float k = (float) Math.sin(Math.PI * p.age / p.life);
			float a = k * lowK * (night > 0.5F ? 0.55F * night : 0.8F) ;
			// stretched toward the viewer, like a streak of light on the ripples
			double vx = p.x - cam.x, vz = p.z - cam.z;
			float vl = (float) Math.sqrt(vx * vx + vz * vz) + 1e-4F;
			bill(b, cam, p.x, p.y, p.z, p.size, (float) (-vz / vl) * 0.35F, 0, (float) (vx / vl) * 0.35F, (float) (vx / vl), 0, (float) (vz / vl), 24, argb(a, pathCol));
			q++;
		}
		return q;
	}

	private static float smooth(float e0, float e1, float x) {
		float k = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
		return k * k * (3 - 2 * k);
	}
}
