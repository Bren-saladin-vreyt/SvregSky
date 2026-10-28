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
	static final int MAX_QUADS = 340;
	private static final Random RNG = new Random();
	private static final int RX = 16, RY = 8;

	// rolling scan
	private static int scanIndex;
	private static BlockPos scanOrigin = BlockPos.ZERO;
	private static List<BlockPos> sFlowers = new ArrayList<>(), sLeafAir = new ArrayList<>(), sWater = new ArrayList<>(),
			sShore = new ArrayList<>(), sSmoke = new ArrayList<>(), sWindows = new ArrayList<>();
	private static List<BlockPos> flowers = List.of(), leafAir = List.of(), water = List.of(), shore = List.of(), smoke = List.of(), windows = List.of();
	private static final List<Direction> windowSides = new ArrayList<>();
	private static List<Direction> sWindowSides = new ArrayList<>();

	private static final class P {
		double x, y, z;
		float vx, vy, vz, age, life, phase, size;
		int color;
		boolean alive;
	}

	private static final P[] butterflies = make(10), motes = make(30), ripples = make(30), foam = make(40), steam = make(20),
			smokeP = make(36), snow = make(90);

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
		for (String k : new String[]{"butterflies", "light_motes", "rain_ripples", "shore_foam", "water_steam", "smoke_wisps", "ghibli_snow", "window_glow"}) {
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
			sSmoke = new ArrayList<>(); sWindows = new ArrayList<>(); sWindowSides = new ArrayList<>();
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
			windowSides.clear();
			windowSides.addAll(sWindowSides);
		}
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
		return q;
	}
}
