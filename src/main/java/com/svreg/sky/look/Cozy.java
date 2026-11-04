package com.svreg.sky.look;

import com.svreg.sky.sky.CirrusSky;
import com.svreg.sky.sky.SkySettings;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.Random;

/**
 * Cozy home: notices when the player is inside a house (a roof above and walls around -- a home they built or a
 * village house alike) and near a warm fire, so the rain can drum on the roof, the edges of the view mist up like
 * a window on a rainy day, and firelight glows and flickers warm.
 */
public final class Cozy {
	private static final Random RNG = new Random();
	private static float indoor, fire, flicker = 1, flickerTarget = 1;
	private static int tick;
	private static boolean roofNow, inNow;
	private static float fireNow;

	private Cozy() {
	}

	public static boolean on() {
		return SkySettings.on("cozy_home");
	}

	/** 0 outside .. 1 inside a house (smoothed) */
	public static float indoor() {
		return on() ? indoor : 0;
	}

	/** 0 .. 1: how close a warm fire is (campfire, fire, lantern, lit candles, lit furnace), smoothed */
	public static float fire() {
		return on() ? fire : 0;
	}

	/** the firelight's flicker, around 1 */
	public static float flicker() {
		return flicker;
	}

	/** inside during rain: how misted the window-like edges of the view are */
	public static float mist() {
		return indoor() * CirrusSky.rainLevel();
	}

	/** called every client tick */
	public static void tick(Minecraft mc) {
		if (!on() || mc.player == null || mc.level == null || mc.isPaused()) {
			return;
		}
		Level level = mc.level;
		BlockPos eye = BlockPos.containing(mc.player.getEyePosition());
		if (++tick % 10 == 0) {
			// a roof: something solid straight above within 24 blocks
			roofNow = false;
			for (int y = 1; y <= 24; y++) {
				BlockState s = level.getBlockState(eye.above(y));
				if (!s.isAir() && s.blocksMotion()) {
					roofNow = true;
					break;
				}
			}
			// walls: of 8 directions, most meet a solid block within 7 blocks (doors and windows can stay open)
			int walls = 0;
			int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
			for (int[] d : dirs) {
				for (int r = 1; r <= 7; r++) {
					BlockState s = level.getBlockState(eye.offset(d[0] * r, 0, d[1] * r));
					BlockState s2 = level.getBlockState(eye.offset(d[0] * r, -1, d[1] * r));
					if ((!s.isAir() && s.blocksMotion()) || (!s2.isAir() && s2.blocksMotion() && !level.getBlockState(eye.offset(d[0] * r, -2, d[1] * r)).isAir())) {
						walls++;
						break;
					}
				}
			}
			inNow = roofNow && walls >= 5 && !level.canSeeSky(eye);
		}
		if (tick % 20 == 5) {
			// warm fire nearby: the closest one in a 15 x 7 x 15 box
			float best = 0;
			BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
			for (int dx = -7; dx <= 7; dx++) {
				for (int dy = -3; dy <= 3; dy++) {
					for (int dz = -7; dz <= 7; dz++) {
						p.set(eye.getX() + dx, eye.getY() + dy, eye.getZ() + dz);
						float w = warmth(level.getBlockState(p));
						if (w > 0) {
							float d = (float) Math.sqrt(dx * dx + dy * dy * 2 + dz * dz);
							best = Math.max(best, w * (1 - Math.min(1, d / 8F)));
						}
					}
				}
			}
			fireNow = best;
		}
		indoor += ((inNow ? 1 : 0) - indoor) * 0.08F;
		fire += (fireNow - fire) * 0.1F;
		// firelight flicker: drifts toward a new random target every few ticks
		if (tick % 3 == 0) {
			flickerTarget = 0.82F + RNG.nextFloat() * 0.3F;
		}
		flicker += (flickerTarget - flicker) * 0.35F;
	}

	private static float warmth(BlockState s) {
		if (s.isAir()) {
			return 0;
		}
		if (s.getBlock() instanceof CampfireBlock) {
			return s.getValue(CampfireBlock.LIT) ? 1.0F : 0;
		}
		if (s.is(Blocks.FIRE)) {
			return 1.0F;
		}
		if (s.is(Blocks.LANTERN)) {
			return 0.55F;
		}
		if (s.hasProperty(BlockStateProperties.LIT) && s.getValue(BlockStateProperties.LIT)) {
			// lit candles, furnaces, smokers, lamps
			return 0.5F;
		}
		return 0;
	}
}
