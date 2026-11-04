package com.svreg.sky.fx;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.svreg.sky.look.Cozy;
import com.svreg.sky.sky.CirrusSky;
import com.svreg.sky.sky.SkySettings;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Sunlight through the windows: inside a house by day, every glass block (or pane, or bars) the sun shines through
 * throws a soft shaft of light into the room, tinted by stained glass, with dust motes drifting slowly inside it.
 * Drawn in the same particle draw as {@link Effects}.
 */
final class WindowLight {
	static final int MAX_BEAMS = 14, MOTES = 24;
	static final int MAX_QUADS = MAX_BEAMS * 2 + MOTES;
	private static final Random RNG = new Random();

	private record Beam(double x, double y, double z, float len, int color) {
	}

	private static final class Mote {
		double x, y, z;
		float age, life, phase;
		int beam = -1;
	}

	private static List<Beam> beams = List.of();
	private static final Mote[] motes = new Mote[MOTES];
	private static float scanT, vis;

	static {
		for (int i = 0; i < MOTES; i++) {
			motes[i] = new Mote();
		}
	}

	private WindowLight() {
	}

	static boolean anyOn() {
		return SkySettings.on("window_light");
	}

	private static boolean window(BlockState s) {
		return (s.is(BlockTags.IMPERMEABLE) || s.getBlock() instanceof IronBarsBlock) && !s.is(Blocks.TINTED_GLASS);
	}

	/** light passes through air, glass and other blocks that don't fill their space */
	private static boolean clear(ClientLevel level, BlockPos p) {
		BlockState s = level.getBlockState(p);
		return s.isAir() || window(s) || !s.canOcclude();
	}

	private static void scan(ClientLevel level, Vec3 cam, Vector3f sun) {
		List<Beam> found = new ArrayList<>();
		BlockPos eye = BlockPos.containing(cam);
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int dy = -2; dy <= 4 && found.size() < 64; dy++) {
			for (int dx = -8; dx <= 8; dx++) {
				for (int dz = -8; dz <= 8; dz++) {
					p.set(eye.getX() + dx, eye.getY() + dy, eye.getZ() + dz);
					BlockState s = level.getBlockState(p);
					if (!window(s)) {
						continue;
					}
					double cx = p.getX() + 0.5, cy = p.getY() + 0.5, cz = p.getZ() + 0.5;
					// the room side: the block the sun shines into must be open
					BlockPos in = BlockPos.containing(cx - sun.x * 0.9, cy - sun.y * 0.9, cz - sun.z * 0.9);
					if (in.equals(p) || !level.getBlockState(in).isAir()) {
						continue;
					}
					// the outside: nothing solid between the window and the sun for 12 blocks
					boolean lit = true;
					for (int k = 1; k <= 12 && lit; k++) {
						BlockPos o = BlockPos.containing(cx + sun.x * k, cy + sun.y * k, cz + sun.z * k);
						if (!o.equals(p.immutable()) && !clear(level, o)) {
							lit = false;
						}
					}
					if (!lit) {
						continue;
					}
					// how far the light reaches into the room
					float len = 0.5F;
					while (len < 12) {
						BlockPos q = BlockPos.containing(cx - sun.x * (len + 0.5), cy - sun.y * (len + 0.5), cz - sun.z * (len + 0.5));
						if (!q.equals(p.immutable()) && !level.getBlockState(q).isAir() && level.getBlockState(q).canOcclude()) {
							break;
						}
						len += 0.5F;
					}
					int col = 0xFFFFFF;
					if (!s.is(Blocks.GLASS) && !s.is(Blocks.GLASS_PANE) && !s.is(Blocks.IRON_BARS)) {
						// stained glass tints its light (lightened, so it stays a soft wash)
						int m = s.getMapColor(level, p).col;
						int r = (m >> 16 & 0xFF) / 2 + 128, g = (m >> 8 & 0xFF) / 2 + 128, b = (m & 0xFF) / 2 + 128;
						col = r << 16 | g << 8 | b;
					}
					found.add(new Beam(cx - sun.x * 0.5, cy - sun.y * 0.5, cz - sun.z * 0.5, len, col));
				}
			}
		}
		found.sort((a, b) -> Double.compare(dist2(a, cam), dist2(b, cam)));
		beams = found.size() > MAX_BEAMS ? new ArrayList<>(found.subList(0, MAX_BEAMS)) : found;
	}

	private static double dist2(Beam b, Vec3 c) {
		double x = b.x - c.x, y = b.y - c.y, z = b.z - c.z;
		return x * x + y * y + z * z;
	}

	static int emit(BufferBuilder b, ClientLevel level, Vec3 cam, float dt, float t, float rx, float ry, float rz, float ux, float uy, float uz) {
		Vector3f sun = CirrusSky.sunDirection();
		float day = Math.max(CirrusSky.dayLevel(), CirrusSky.goldLevel()), gold = CirrusSky.goldLevel(), rain = CirrusSky.rainLevel();
		boolean want = anyOn() && Cozy.on() && sun.y > 0.08F && day > 0.2F && rain < 0.6F;
		float target = want ? Math.min(1, Cozy.indoor() * 1.5F) * day * (1 - rain) : 0;
		vis += (target - vis) * Math.min(1, dt * 2);
		if (!want && vis < 0.01F) {
			beams = List.of();
			return 0;
		}
		scanT -= dt;
		if (scanT <= 0 && want) {
			scanT = 0.75F;
			scan(level, cam, sun);
		}
		if (beams.isEmpty()) {
			return 0;
		}
		int q = 0;
		// the shaft runs away from the sun; two crossed quads give it some body from every side
		float ax = -sun.x, ay = -sun.y, az = -sun.z;
		float p1x = -az, p1y = 0, p1z = ax;
		float pl = (float) Math.sqrt(p1x * p1x + p1z * p1z);
		if (pl < 1e-3F) {
			p1x = 1;
			p1z = 0;
			pl = 1;
		}
		p1x /= pl;
		p1z /= pl;
		float p2x = ay * p1z - az * p1y, p2y = az * p1x - ax * p1z, p2z = ax * p1y - ay * p1x;
		float sr = 1.0F, sg = 0.94F - 0.16F * gold, sb = 0.80F - 0.30F * gold;
		for (Beam bm : beams) {
			float half = bm.len / 2;
			float cx = (float) (bm.x - cam.x) + ax * half, cy = (float) (bm.y - cam.y) + ay * half, cz = (float) (bm.z - cam.z) + az * half;
			int r = Math.round((bm.color >> 16 & 0xFF) * sr), g = Math.round((bm.color >> 8 & 0xFF) * sg), bl = Math.round((bm.color & 0xFF) * sb);
			int argb = Math.round(255 * 0.20F * vis) << 24 | r << 16 | g << 8 | bl;
			Effects.billboard(b, cx, cy, cz, 1, p1x * 0.5F, p1y * 0.5F, p1z * 0.5F, ax * half, ay * half, az * half, 32, argb);
			Effects.billboard(b, cx, cy, cz, 1, p2x * 0.5F, p2y * 0.5F, p2z * 0.5F, ax * half, ay * half, az * half, 32, argb);
			q += 2;
		}
		// dust motes floating in the light
		for (Mote m : motes) {
			if (m.beam < 0 || m.beam >= beams.size() || m.age > m.life) {
				if (RNG.nextFloat() < 0.2F) {
					m.beam = RNG.nextInt(beams.size());
					Beam bm = beams.get(m.beam);
					float along = RNG.nextFloat() * bm.len;
					m.x = bm.x + ax * along + p1x * (RNG.nextFloat() - 0.5F) * 0.8 + p2x * (RNG.nextFloat() - 0.5F) * 0.8;
					m.y = bm.y + ay * along + p2y * (RNG.nextFloat() - 0.5F) * 0.8;
					m.z = bm.z + az * along + p1z * (RNG.nextFloat() - 0.5F) * 0.8 + p2z * (RNG.nextFloat() - 0.5F) * 0.8;
					m.age = 0;
					m.life = 3 + RNG.nextFloat() * 4;
					m.phase = RNG.nextFloat() * 6.28F;
				} else {
					m.beam = -1;
					continue;
				}
			}
			m.age += dt;
			m.x += Math.sin(t * 0.4 + m.phase) * 0.05 * dt;
			m.y += (Math.sin(t * 0.3 + m.phase * 2) * 0.04 - 0.01) * dt;
			m.z += Math.cos(t * 0.35 + m.phase) * 0.05 * dt;
			float env = Math.min(1, m.age / 1.0F) * Math.min(1, (m.life - m.age) / 1.0F);
			float tw = 0.6F + 0.4F * (float) Math.sin(t * 1.5 + m.phase * 3);
			Effects.billboard(b, (float) (m.x - cam.x), (float) (m.y - cam.y), (float) (m.z - cam.z), 0.022F, rx, ry, rz, ux, uy, uz, 12,
					Math.round(255 * 0.75F * env * tw * vis) << 24 | 0xFFF4DC);
			q++;
		}
		return q;
	}
}
