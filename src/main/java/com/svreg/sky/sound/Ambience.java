package com.svreg.sky.sound;

import com.svreg.sky.SvregSky;
import com.svreg.sky.sky.CirrusSky;
import com.svreg.sky.sky.SkySettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.Random;

/** Ghibli ambience: wind over open land, crickets at night, birdsong by day, a babbling stream near water. */
public final class Ambience {
	private static final SoundEvent WIND = event("ambient.wind_meadow");
	private static final SoundEvent CRICKETS = event("ambient.crickets");
	private static final SoundEvent STREAM = event("ambient.stream");
	private static final SoundEvent BIRD = event("ambient.bird");
	private static final SoundEvent OCEAN = event("ambient.ocean");
	private static final SoundEvent FROGS = event("ambient.frogs");
	private static final SoundEvent OWL = event("ambient.owl");
	private static final SoundEvent MOUNTAIN = event("ambient.mountain_wind");
	private static final SoundEvent RAIN_SOFT = event("ambient.rain_soft");
	private static final SoundEvent RAIN_ROOF = event("ambient.rain_roof");
	private static final SoundEvent THUNDER = event("ambient.thunder");
	private static final SoundEvent DRIP = event("ambient.drip");
	private static final SoundEvent ROOSTER = event("ambient.rooster");
	private static final SoundEvent BELL = event("ambient.bell");
	private static final SoundEvent MUSIC_RISE = event("music.sunrise");
	private static final SoundEvent MUSIC_SET = event("music.sunset");
	private static final SoundEvent RUSTLE = event("ambient.leaves_rustle");
	private static final Random RNG = new Random();

	private static Loop wind, crickets, stream, ocean, frogs, mountain, rainSoft, rainRoof, rustle;
	private static float waterNear, oceanNear, forestNear, roofed;
	private static int tick, nextBird = 100, nextOwl = 400, nextDrip = 60, nextBell = 1200, villagers, thunderIn = -1;
	private static boolean crowed;
	private static long lastMusicDay = -1;
	private static String lastMusic = "";
	private static float lastFlash;
	private static boolean broken;

	private Ambience() {
	}

	private static SoundEvent event(String path) {
		return SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(SvregSky.MOD_ID, path));
	}

	/** a looping, non-positional sound whose volume glides toward a target */
	private static final class Loop extends AbstractTickableSoundInstance {
		float target;

		Loop(SoundEvent e) {
			super(e, SoundSource.AMBIENT, SoundInstance.createUnseededRandom());
			this.looping = true;
			this.delay = 0;
			this.volume = 0.001F;
			this.relative = true;
			this.attenuation = SoundInstance.Attenuation.NONE;
		}

		@Override
		public void tick() {
			this.volume += (target - this.volume) * 0.03F;
			if (target <= 0.001F && this.volume < 0.005F) {
				this.stop();
			}
		}

		boolean done() {
			return this.isStopped();
		}
	}

	/** a one-time, non-positional music piece */
	private static final class Tune extends AbstractTickableSoundInstance {
		Tune(SoundEvent e) {
			super(e, SoundSource.MUSIC, SoundInstance.createUnseededRandom());
			this.looping = false;
			this.volume = 0.7F;
			this.relative = true;
			this.attenuation = SoundInstance.Attenuation.NONE;
		}

		@Override
		public void tick() {
		}
	}

	private static Loop drive(Loop loop, SoundEvent e, float target) {
		Minecraft mc = Minecraft.getInstance();
		if (target > 0.002F && (loop == null || loop.done())) {
			loop = new Loop(e);
			loop.target = target;
			mc.getSoundManager().play(loop);
		} else if (loop != null) {
			loop.target = target;
		}
		return loop;
	}

	public static void tick(Minecraft mc) {
		if (broken) {
			return;
		}
		try {
			Player player = mc.player;
			if (player == null || mc.level == null || mc.isPaused()) {
				return;
			}
			float vol = SkySettings.ambientSounds ? SkySettings.ambientVolume : 0;
			BlockPos eye = BlockPos.containing(player.getEyePosition());
			boolean outdoors = mc.level.canSeeSky(eye);
			boolean underwater = mc.level.getFluidState(eye).is(FluidTags.WATER);
			float day = CirrusSky.dayLevel(), night = CirrusSky.nightLevel(), rain = CirrusSky.rainLevel();
			float open = outdoors && !underwater ? 1 : 0;

			// water nearby: sample around the player twice a second
			if (++tick % 10 == 0) {
				int found = 0;
				for (int i = 0; i < 24; i++) {
					BlockPos p = eye.offset(RNG.nextInt(17) - 8, RNG.nextInt(7) - 4, RNG.nextInt(17) - 8);
					if (mc.level.getFluidState(p).is(FluidTags.WATER)) {
						found++;
					}
				}
				waterNear += (Math.min(1, found / 6F) - waterNear) * 0.5F;
			}

			// biome feel around the player, once a second
			if (tick % 20 == 0) {
				var biome = mc.level.getBiome(eye);
				float oc = biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_BEACH) ? 1 : 0;
				oceanNear += (Math.max(oc, waterNear > 0.8F ? 0.5F : 0) - oceanNear) * 0.5F;
				float fo = biome.is(BiomeTags.IS_FOREST) || biome.is(BiomeTags.IS_RIVER) ? 1 : 0;
				forestNear += (Math.max(fo, waterNear * 0.6F) - forestNear) * 0.5F;
				// under a roof: no sky straight above, but sky light close by
				roofed = !outdoors && mc.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, eye) >= 8 ? 1 : 0;
			}
			if (tick % 100 == 0) {
				villagers = mc.level.getEntities((net.minecraft.world.entity.Entity) null, new AABB(eye).inflate(40), e -> e.getType().getDescriptionId().endsWith(".villager")).size();
			}
			boolean places = SkySettings.on("place_sounds"), weather = SkySettings.on("weather_sounds");
			float pv = places ? vol : 0, wv = weather ? vol : 0;
			ocean = drive(ocean, OCEAN, pv * open * oceanNear * 0.55F);
			frogs = drive(frogs, FROGS, pv * smooth(0.4F, 0.9F, night) * forestNear * (1 - rain) * 0.4F);
			float high = smooth(110, 150, (float) player.getY());
			mountain = drive(mountain, MOUNTAIN, pv * open * high * 0.5F);
			rainSoft = drive(rainSoft, RAIN_SOFT, wv * rain * open * 0.5F);
			rainRoof = drive(rainRoof, RAIN_ROOF, wv * rain * roofed * 0.55F);
			float rv = SkySettings.on("leaf_rustle") ? vol : 0;
			rustle = drive(rustle, RUSTLE, rv * com.svreg.sky.fx.Nature.leafiness() * Math.min(SkySettings.windStrength, 2) * 0.3F * (outdoors ? 1 : 0.35F));

			// gentle thunder a moment after the lightning glow in the clouds
			float fl = CirrusSky.flashLevel();
			if (fl > 0.5F && lastFlash < 0.3F && thunderIn < 0) {
				thunderIn = 20 + RNG.nextInt(60);
			}
			lastFlash = fl;
			if (thunderIn >= 0 && --thunderIn < 0 && wv > 0) {
				oneShot(mc, player, THUNDER, wv * 0.6F, 0.85F + RNG.nextFloat() * 0.2F, 60);
			}

			// owls at night in the woods
			if (--nextOwl <= 0) {
				nextOwl = 500 + RNG.nextInt(900);
				if (pv > 0 && night > 0.6F && forestNear > 0.3F && rain < 0.3F) {
					oneShot(mc, player, OWL, pv * 0.5F, 0.95F + RNG.nextFloat() * 0.1F, 20);
				}
			}
			// caves: drips with echo
			boolean cave = !outdoors && mc.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, eye) == 0 && player.getY() < 60;
			if (--nextDrip <= 0) {
				nextDrip = 40 + RNG.nextInt(160);
				if (pv > 0 && cave) {
					oneShot(mc, player, DRIP, pv * 0.55F, 0.8F + RNG.nextFloat() * 0.4F, 8);
				}
			}
			// villages: a rooster at dawn, a distant bell now and then by day
			float tod = CirrusSky.dayLevel();
			boolean dawn = CirrusSky.sunDirection().x > 0 && CirrusSky.goldLevel() > 0.4F;
			if (!dawn) {
				crowed = false;
			} else if (!crowed && villagers > 0 && pv > 0) {
				crowed = true;
				oneShot(mc, player, ROOSTER, pv * 0.55F, 0.95F + RNG.nextFloat() * 0.1F, 25);
			}
			if (--nextBell <= 0) {
				nextBell = 2400 + RNG.nextInt(3600);
				if (pv > 0 && villagers > 2 && tod > 0.6F && rain < 0.5F) {
					oneShot(mc, player, BELL, pv * 0.4F, 1F, 45);
				}
			}
			// a short original piece once each sunrise and sunset
			if (SkySettings.on("ghibli_music") && open > 0 && CirrusSky.goldLevel() > 0.55F) {
				long dayNo = mc.level.getOverworldClockTime() / 24000L;
				String which = CirrusSky.sunDirection().x > 0 ? "rise" : "set";
				if (dayNo != lastMusicDay || !which.equals(lastMusic)) {
					lastMusicDay = dayNo;
					lastMusic = which;
					mc.getSoundManager().play(new Tune(which.equals("rise") ? MUSIC_RISE : MUSIC_SET));
				}
			}

			wind = drive(wind, WIND, vol * open * (0.12F + 0.22F * Math.min(SkySettings.windStrength, 2)) * (1 + rain * 0.5F));
			crickets = drive(crickets, CRICKETS, (SkySettings.on("crickets") ? vol : 0) * open * smooth(0.4F, 0.9F, night) * (1 - rain) * 0.22F);
			stream = drive(stream, STREAM, vol * waterNear * (underwater ? 0 : 0.5F));

			// birdsong: random songs around the player by day, more in the morning
			if (--nextBird <= 0) {
				float morning = CirrusSky.sunDirection().x > 0 ? 1.5F : 1F;
				nextBird = (int) ((120 + RNG.nextInt(260)) / morning);
				if (vol > 0 && open > 0 && day > 0.5F && rain < 0.2F) {
					double a = RNG.nextDouble() * Math.PI * 2, r = 6 + RNG.nextDouble() * 12;
					mc.getSoundManager().play(new SimpleSoundInstance(BIRD, SoundSource.AMBIENT, vol * 0.45F, 0.9F + RNG.nextFloat() * 0.25F,
							SoundInstance.createUnseededRandom(), player.getX() + Math.cos(a) * r, player.getEyeY() + 3 + RNG.nextDouble() * 5, player.getZ() + Math.sin(a) * r));
				}
			}
		} catch (Throwable t) {
			broken = true;
			SvregSky.LOG.error("SvregSky: ambient sounds failed, turning them off", t);
		}
	}

	private static void oneShot(Minecraft mc, Player player, SoundEvent e, float volume, float pitch, double dist) {
		double a = RNG.nextDouble() * Math.PI * 2, r = dist * (0.6 + RNG.nextDouble() * 0.4);
		mc.getSoundManager().play(new SimpleSoundInstance(e, SoundSource.AMBIENT, volume, pitch, SoundInstance.createUnseededRandom(),
				player.getX() + Math.cos(a) * r, player.getEyeY() + 2, player.getZ() + Math.sin(a) * r));
	}

	private static float smooth(float e0, float e1, float x) {
		float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
		return t * t * (3 - 2 * t);
	}
}
