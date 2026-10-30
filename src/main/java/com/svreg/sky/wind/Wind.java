package com.svreg.sky.wind;

import com.svreg.sky.sky.SkySettings;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL20C;

/**
 * Grass / leaves wind for Sodium terrain.
 * Mesh side: each foliage vertex carries a sway weight hidden in the vertex colour's alpha (255 - weight*12),
 * 0 at a plant's roots, 1 at the top of a one-block plant, 2 at the top of a tall plant, a small constant for leaves.
 * Shader side: Sodium's terrain vertex shader gets a few lines patched in (see {@link #patchVertexShader}) that
 * read the weight, restore the alpha, push the vertex along the wind and tint far grass toward a soft sage green.
 */
public final class Wind {
	private static final long START = System.nanoTime();
	private static volatile double camX, camY, camZ;
	private static int lastProgram = -1, locWind = -1, locCam = -1, locSun = -1, locGrade = -1, locPlayer = -1, locLook = -1, locSeason = -1, locSkyA = -1, locSkyB = -1, locSkyC = -1, locLook2 = -1, locShadow = -1, locShadow2 = -1, locHeightTex = -1, locWater = -1, locExtra = -1;
	private static long skyNanos;
	private static final String[] NAMES = {"Wind", "Cam", "Sun", "Grade", "Player", "Look", "Season", "SkyA", "SkyB", "SkyC", "Look2", "Shadow", "Shadow2", "HeightTex", "Water", "Extra"};
	private static final java.util.Map<Integer, int[]> LOCS = new java.util.HashMap<>();
	private static final java.util.Map<Integer, Long> LAST_UPLOAD = new java.util.HashMap<>();
	/** test hook: paint the water-detection debug view */
	public static boolean debugWater;
	private static float[] skyA = new float[4], skyB = new float[4], skyC = new float[4];
	private static volatile double pX, pY, pZ;

	private Wind() {
	}

	public static void setPlayer(Vec3 feet) {
		pX = feet.x;
		pY = feet.y;
		pZ = feet.z;
	}

	public static double cameraY() {
		return camY;
	}

	public static void setCamera(Vec3 cam) {
		camX = cam.x;
		camY = cam.y;
		camZ = cam.z;
	}

	/** 0 = no sway; otherwise the plant kind (1 plant, 2 leaves) */
	public static int kind(BlockState state) {
		if (state == null) {
			return 0;
		}
		if (!state.getFluidState().isEmpty()) {
			return 0; // seagrass, kelp and other underwater / waterlogged plants stay out of the wind
		}
		if (state.is(BlockTags.LEAVES)) {
			return 2;
		}
		if (state.is(BlockTags.REPLACEABLE_BY_TREES) || state.is(BlockTags.SMALL_FLOWERS) || state.is(BlockTags.FLOWERS)
				|| state.is(BlockTags.CROPS)) {
			return 1;
		}
		return 0;
	}

	public static boolean upperHalf(BlockState state) {
		return state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER;
	}

	/** alpha byte for a vertex at local height y (0..1 inside its block) */
	public static int alpha(int kind, boolean upper, float localY) {
		if (kind == 2) {
			return 255 - 26; // leaves: their own marker, the shader gives them a gentle sway
		}
		float w = Math.max(0, Math.min(2, localY + (upper ? 1 : 0)));
		return 255 - Math.min(24, Math.round(w * 12));
	}

	private static float hash(float x, float y) {
		float px = frac(x * 0.1031F), py = frac(y * 0.1031F), pz = frac(x * 0.1031F);
		float d = px * (py + 33.33F) + py * (pz + 33.33F) + pz * (px + 33.33F);
		px += d; py += d; pz += d;
		return frac((px + py) * pz);
	}

	private static float frac(float v) {
		return v - (float) Math.floor(v);
	}

	private static float noise(float x, float y) {
		float ix = (float) Math.floor(x), iy = (float) Math.floor(y), fx = x - ix, fy = y - iy;
		fx = fx * fx * (3 - 2 * fx);
		fy = fy * fy * (3 - 2 * fy);
		float a = hash(ix, iy), b = hash(ix + 1, iy), c = hash(ix, iy + 1), d = hash(ix + 1, iy + 1);
		return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fy;
	}

	/** the same gust field the grass shader uses (0 calm .. 1 gust) at a world position, for the wind sound */
	public static float gustAt(double x, double z) {
		float t = (float) (((System.nanoTime() - START) / 1e9) % 3600.0);
		float st = t * SkySettings.windSpeed;
		float ang = 0.34F + 0.30F * (float) Math.sin(t * 0.011F) + 0.12F * (float) Math.sin(t * 0.037F + 1.3F);
		float dx = (float) Math.cos(ang), dz = (float) Math.sin(ang);
		float wx = (float) ((x + 0) % 4096.0), wz = (float) ((z + 0) % 4096.0);
		float qx = wx * 0.032F - dx * st * 0.42F, qz = wz * 0.032F - dz * st * 0.42F;
		float rx = (float) Math.sin(qz * 1.9F + 1.3F + Math.sin(qx * 0.7F)) * 0.3F, rz = (float) Math.sin(qx * 1.7F + 4.1F + Math.sin(qz * 0.9F)) * 0.3F;
		float g1 = noise(qx + rx * 1.8F, qz + rz * 1.8F);
		float g2 = noise(qx * 2.6F + rx - dx * st * 0.35F + 11, qz * 2.6F + rz - dz * st * 0.35F + 11);
		return smoothstep(0.32F, 0.82F, g1 * 0.72F + g2 * 0.28F);
	}

	private static float smoothstep(float e0, float e1, float x) {
		float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
		return t * t * (3 - 2 * t);
	}

	public static boolean enabled() {
		return SkySettings.windStrength > 0 || SkySettings.farGrassTint > 0;
	}

	/** sets the wind uniforms on Sodium's current terrain program (called for every region draw) */
	public static void upload() {
		// called for every terrain region draw: keep it cheap. Uniform values stay in a program once set,
		// so each program (opaque, cutout, translucent...) gets them at most once every few milliseconds.
		long now = System.nanoTime();
		int prog = GL20C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
		if (prog == 0) {
			return;
		}
		int[] loc = LOCS.get(prog);
		if (loc == null) {
			loc = new int[NAMES.length + 1];
			for (int i = 0; i < NAMES.length; i++) {
				loc[i] = GL20C.glGetUniformLocation(prog, "svregsky_" + NAMES[i]);
			}
			LOCS.put(prog, loc);
		}
		Long last = LAST_UPLOAD.get(prog);
		if (last != null && now - last < 4_000_000L) {
			return;
		}
		LAST_UPLOAD.put(prog, now);
		lastProgram = prog;
		locWind = loc[0];
		locCam = loc[1];
		locSun = loc[2];
		locGrade = loc[3];
		locPlayer = loc[4];
		locLook = loc[5];
		locSeason = loc[6];
		locSkyA = loc[7];
		locSkyB = loc[8];
		locSkyC = loc[9];
		locLook2 = loc[10];
		locShadow = loc[11];
		locShadow2 = loc[12];
		locHeightTex = loc[13];
		locWater = loc[14];
		locExtra = loc[15];
		if (locWind >= 0) {
			float t = (float) (((System.nanoTime() - START) / 1e9) % 3600.0);
			GL20C.glUniform4f(locWind, t, SkySettings.windStrength, SkySettings.windSpeed, SkySettings.farGrassTint);
		}
		float drift = (float) (((System.nanoTime() - START) / 1e9 * 1.6 * SkySettings.cloudSpeed) % 4096.0);
		if (locCam >= 0) {
			GL20C.glUniform4f(locCam, (float) (camX % 4096.0), (float) camY, (float) (camZ % 4096.0), drift);
		}
		if (locSun >= 0) {
			org.joml.Vector3f sun = com.svreg.sky.sky.CirrusSky.sunDirection();
			float k = SkySettings.cloudShadows * Math.min(1, com.svreg.sky.sky.CirrusSky.dayLevel() * 1.5F) * (1 - com.svreg.sky.sky.CirrusSky.rainLevel())
					* (SkySettings.paintedClouds || SkySettings.cloud3d() ? 1 : 0) * (sun.y > 0.05F ? 1 : 0);
			GL20C.glUniform4f(locSun, sun.x, sun.y, sun.z, k);
		}
		if (locPlayer >= 0) {
			GL20C.glUniform4f(locPlayer, (float) (pX - camX), (float) (pY - camY), (float) (pZ - camZ), SkySettings.on("grass_bend") ? 1 : 0);
		}
		if (locLook >= 0) {
			org.joml.Vector3f sunV = com.svreg.sky.sky.CirrusSky.sunDirection();
			// valley fog: thickest in the cool morning and evening, light at midday
			float vf = SkySettings.on("valley_fog") ? 0.45F + 0.55F * (1 - smoothstep(0.15F, 0.6F, Math.abs(sunV.y))) : 0;
			GL20C.glUniform4f(locLook, SkySettings.on("warm_lamps") ? 1 : 0, SkySettings.on("dappled_light") ? 1 : 0, vf, com.svreg.sky.sky.DistantStorm.amount());
		}
		if (locSkyA >= 0 || locSkyB >= 0 || locSkyC >= 0) {
			if (now - skyNanos > 30_000_000L) { // sky colours for the water mirror, refreshed ~30 times a second
				skyNanos = now;
				float[] a = com.svreg.sky.sky.CirrusSky.horizonToward(true), b = com.svreg.sky.sky.CirrusSky.horizonToward(false);
				float day = com.svreg.sky.sky.CirrusSky.dayLevel(), gold = com.svreg.sky.sky.CirrusSky.goldLevel(), night = com.svreg.sky.sky.CirrusSky.nightLevel();
				skyA = new float[]{a[0], a[1], a[2], SkySettings.on("water_reflection") ? 1 : 0};
				skyB = new float[]{b[0], b[1], b[2], SkySettings.on("face_light") ? 1 : 0};
				float zr = 0.06F + (0.30F - 0.06F) * day, zg = 0.08F + (0.58F - 0.08F) * day, zb = 0.22F + (0.95F - 0.22F) * day;
				// golden hour: the sky's own saturated sunset colours (the horizon colour itself is washed out by the sun glow)
				float o = com.svreg.sky.sky.CirrusSky.skyExtra().w; // 1 = orange sunset, 0 = magenta
				float[] tA = {1.00F, 0.62F - 0.06F * o, 0.22F + 0.20F * (1 - o)};
				float[] tB = {0.96F - 0.12F * (1 - o), 0.52F - 0.10F * (1 - o), 0.30F + 0.30F * (1 - o)};
				float[] tC = {0.93F - 0.30F * (1 - o), 0.44F - 0.14F * (1 - o), 0.10F + 0.52F * (1 - o)};
				float k = Math.min(1, gold * 1.3F);
				for (int i = 0; i < 3; i++) {
					skyA[i] += (tA[i] - skyA[i]) * k;
					skyB[i] += (tB[i] - skyB[i]) * k;
				}
				zr += (tC[0] - zr) * k; zg += (tC[1] - zg) * k; zb += (tC[2] - zb) * k;
				skyC = new float[]{zr, zg, zb, night > 0.5F ? 1 : 0};
			}
			if (locSkyA >= 0) GL20C.glUniform4f(locSkyA, skyA[0], skyA[1], skyA[2], skyA[3]);
			if (locSkyB >= 0) GL20C.glUniform4f(locSkyB, skyB[0], skyB[1], skyB[2], skyB[3]);
			if (locSkyC >= 0) GL20C.glUniform4f(locSkyC, skyC[0], skyC[1], skyC[2], skyC[3]);
			if (locLook2 >= 0) GL20C.glUniform4f(locLook2, SkySettings.on("water_sparkle_dots") ? 1 : 0, SkySettings.on("water_sunset") ? 1 : 0, com.svreg.sky.sky.Twilight.dew(), debugWater ? 1 : 0);
		}
		if (locShadow >= 0) {
			// world sun shadows from the column height map (see HeightShadows)
			float k = 0;
			boolean map = HeightShadows.active();
			if (map) {
				HeightShadows.update(camX, camZ);
				k = HeightShadows.strength();
				map = HeightShadows.ready();
			}
			if (locWater >= 0) {
				GL20C.glUniform4f(locWater, map && SkySettings.on("anime_water") ? 1 : 0, map && SkySettings.on("shore_waves") ? 1 : 0, map ? 1 : 0,
						map && SkySettings.on("underwater_light") ? 1 : 0);
			}
			if (map) {
				GL20C.glUniform4f(locShadow, (float) (camX - HeightShadows.originX()), (float) (camZ - HeightShadows.originZ()), (float) (camY - HeightShadows.minY()), k);
				float[] q = HeightShadows.quality();
				float ci = "neutral".equals(SkySettings.shadowColor) ? 1 : "warm".equals(SkySettings.shadowColor) ? 2 : 0;
				if (locShadow2 >= 0) GL20C.glUniform4f(locShadow2, q[0], q[1], ci, SkySettings.on("shadow_debug") ? 1 : 0);
				if (locHeightTex >= 0) GL20C.glUniform1i(locHeightTex, HeightShadows.UNIT);
			} else {
				GL20C.glUniform4f(locShadow, 0, 0, 0, 0);
			}
		}
		if (locExtra >= 0) {
			float snow = SkySettings.on("snow_cover") && SkySettings.on("seasons") ? com.svreg.sky.sky.Seasons.winter() : 0;
			GL20C.glUniform4f(locExtra, snow, SkySettings.on("waterfall_foam") ? 1 : 0, com.svreg.sky.sky.CloudSea.packed(), com.svreg.sky.sky.Twilight.blueHour());
		}
		if (locSeason >= 0) {
			GL20C.glUniform4f(locSeason, com.svreg.sky.sky.Seasons.spring(), com.svreg.sky.sky.Seasons.autumn(), com.svreg.sky.sky.Seasons.winter(),
					SkySettings.on("wet_ground") ? com.svreg.sky.sky.CirrusSky.wetness() : 0);
		}
		if (locGrade >= 0) {
			org.joml.Vector3f sun = com.svreg.sky.sky.CirrusSky.sunDirection();
			float sh = sun.y;
			// mist: dawn (sun rising in the east, just below or above the horizon), a little in rain
			float dawn = smoothstep(-0.20F, 0.02F, sh) * (1 - smoothstep(0.18F, 0.45F, sh)) * (sun.x > 0 ? 1 : 0);
			float mist = SkySettings.morningMist * Math.min(1, dawn + 0.35F * com.svreg.sky.sky.CirrusSky.rainLevel());
			GL20C.glUniform4f(locGrade, SkySettings.colorGrading, mist, com.svreg.sky.sky.CirrusSky.dayLevel(), com.svreg.sky.sky.CirrusSky.goldLevel());
		}
	}

	private static final String DECL = """

			// ---- SvregSky wind + far grass ----
			#ifndef VULKAN
			uniform vec4 svregsky_Wind; // time, strength, speed, far grass tint
			uniform vec4 svregsky_Cam;  // camera position (x, z wrapped), w = cloud drift time
			uniform vec4 svregsky_Sun;  // sun direction, cloud shadow strength
			uniform vec4 svregsky_Grade; // colour grading strength, morning mist strength, day, golden hour
			out vec4 v_svMist;          // mist colour + amount, for the fragment shader
			uniform vec4 svregsky_Player; // player feet relative to the camera, grass bend on/off
			uniform vec4 svregsky_Look;   // warm lamps, dappled light, valley fog, unused
			uniform vec4 svregsky_Season; // spring, autumn, winter weights, ground wetness
			uniform vec4 svregsky_Extra;  // (fragment side) ..., w = blue hour
			out vec4 v_svWater;           // camera-relative position, water surface flag
			float svWaterMark;            // 1 for still water, 2 for flowing / falling water (marked by the fluid mesher)
			float svKind;                 // 1 leaves, 2 small plants
			out vec4 v_svInfo;            // sky light, day, golden hour, unused
			float svHash(vec2 p) {
			    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
			    p3 += dot(p3, p3.yzx + 33.33);
			    return fract((p3.x + p3.y) * p3.z);
			}
			float svNoise(vec2 p) {
			    vec2 i = floor(p);
			    vec2 f = fract(p);
			    f = f * f * (3.0 - 2.0 * f);
			    return mix(mix(svHash(i), svHash(i + vec2(1.0, 0.0)), f.x), mix(svHash(i + vec2(0.0, 1.0)), svHash(i + vec2(1.0, 1.0)), f.x), f.y);
			}
			#endif
			""";

	private static final String CODE = """
			#ifndef VULKAN
			    {
			        int svK = 255 - int(_vert_color.a * 255.0 + 0.5);
			        svWaterMark = svK == 28 ? 1.0 : (svK == 29 ? 2.0 : 0.0);
			        svKind = svK == 26 ? 1.0 : (svK > 0 && svK <= 24 ? 2.0 : 0.0);
			        if (svK == 28 || svK == 29) {
			            _vert_color.a = 1.0;
			        }
			        vec3 svWp = position + svregsky_Cam.xyz;
			        float svStr = svregsky_Wind.y;
			        if (svK > 0 && (svK <= 24 || svK == 26)) {
			            _vert_color.a = 1.0;
			            bool svLeaf = svK == 26;
			            float svW = svLeaf ? 1.0 : float(svK) / 12.0;
			            float svT = svregsky_Wind.x * svregsky_Wind.z;
			            // the wind slowly changes direction over minutes
			            float svAng = 0.34 + 0.30 * sin(svregsky_Wind.x * 0.011) + 0.12 * sin(svregsky_Wind.x * 0.037 + 1.3);
			            vec2 svDir = vec2(cos(svAng), sin(svAng));
			            vec2 svSide = vec2(-svDir.y, svDir.x);
			            // gusts: soft, irregular patches (warped noise) drifting downwind, so the meadow ripples in waves
			            vec2 svQ = svWp.xz * 0.032 - svDir * svT * 0.42;
			            // (kept cheap for phones: two noise lookups per vertex, the warp and the per-plant rhythm are sines)
			            vec2 svWarp = vec2(sin(svQ.y * 1.9 + 1.3 + sin(svQ.x * 0.7)), sin(svQ.x * 1.7 + 4.1 + sin(svQ.y * 0.9))) * 0.3;
			            float svG1 = svNoise(svQ + svWarp * 1.8);
			            float svG2 = svNoise(svQ * 2.6 + svWarp - svDir * svT * 0.35 + 11.0);
			            float svGust = smoothstep(0.32, 0.82, svG1 * 0.72 + svG2 * 0.28);
			            // every plant has its own rhythm (a continuous field: no tearing at block edges)
			            float svPh = sin(dot(svWp.xz, vec2(1.37, 0.91)) + 2.0 * sin(dot(svWp.xz, vec2(-0.71, 1.13)) + svWp.y * 0.31)) * 3.1416;
			            float svPh2 = sin(dot(svWp.xz, vec2(0.53, -1.21)) + 2.0 * sin(dot(svWp.xz, vec2(1.07, 0.37)) - svWp.y * 0.47)) * 3.1416;
			            if (svLeaf) {
			                // leaves: the whole crown leans with the gust, branches sway on slow loops, leaves rustle on top
			                float svLean = svStr * (0.035 + 0.11 * svGust);
			                vec3 svSway = vec3(sin(svT * 1.7 + svPh) + 0.5 * sin(svT * 0.9 + svPh2),
			                                   0.5 * sin(svT * 2.1 + svPh2),
			                                   cos(svT * 1.4 + svPh) + 0.5 * cos(svT * 1.1 + svPh2 * 1.3)) * svStr * (0.022 + 0.035 * svGust);
			                float svRustle = sin(svT * 9.0 + svPh * 3.0) * svStr * (0.006 + 0.016 * svGust);
			                position.xz += svDir * svLean + svSway.xz + svSide * svRustle;
			                position.y += svSway.y + svRustle * 0.6;
			                _vert_color.rgb *= 1.0 + min(svStr, 2.0) * 0.05 * svGust;
			            } else {
			                // grass: bends with the gusts and springs back, tips flutter, a little side-to-side wobble
			                float svFlutter = 0.6 * sin(svT * 6.1 + svPh) + 0.4 * sin(svT * 9.7 + svPh2);
			                float svLean = svStr * (0.16 + 0.80 * svGust + 0.06 * svFlutter);
			                float svAmt = svLean * svW * 0.60;
			                float svWob = svStr * svW * (0.05 + 0.05 * svGust) * sin(svT * 2.3 + svPh2);
			                position.xz += svDir * svAmt + svSide * svWob;
			                position.y -= svAmt * svAmt * 0.40;
			                // the bent blades catch the light: the gusts show as silvery waves rolling over the field
			                float svSheen = min(svStr, 2.0) * svGust * clamp(svW, 0.0, 1.0);
			                _vert_color.rgb = mix(_vert_color.rgb, _vert_color.rgb * vec3(1.12, 1.16, 1.02) + vec3(0.03, 0.03, 0.0), clamp(svSheen * 0.85, 0.0, 1.0));
			            }
            if (svregsky_Player.w > 0.5 && !svLeaf) {
			                // plants bend away from the player walking through them
			                vec2 svAway = position.xz - svregsky_Player.xz;
			                float svD = length(svAway);
			                float svPush = (1.0 - smoothstep(0.2, 1.3, svD)) * step(abs(position.y - svregsky_Player.y), 2.2) * clamp(svW, 0.0, 1.5);
			                position.xz += svAway / max(svD, 0.05) * svPush * 0.45;
			                position.y -= svPush * 0.25;
			            }
			        }
			        if (svregsky_Sun.w > 0.0) {
			            // soft cloud shadows drifting over the land (projected along the sun, moving with the wind)
			            vec2 svSp = svWp.xz + svregsky_Sun.xz / max(svregsky_Sun.y, 0.25) * (192.0 - svWp.y)
			                      - vec2(0.943, 0.333) * svregsky_Cam.w;
			            float svN = 0.65 * svNoise(svSp / 96.0) + 0.35 * svNoise(svSp / 41.0 + 7.3);
			            float svShadow = smoothstep(0.56, 0.68, svN) * svregsky_Sun.w;
			            _vert_color.rgb *= mix(vec3(1.0), vec3(0.74, 0.80, 0.94), svShadow);
			        }
			        if (svregsky_Wind.w > 0.0) {
			            vec3 svC = _vert_color.rgb;
			            if (svC.g - max(svC.r, svC.b) > 0.06) {
			                float svL = dot(svC, vec3(0.30, 0.59, 0.11));
			                vec3 svNear = clamp(svC * vec3(0.96, 1.07, 0.88) + vec3(0.0, 0.015, 0.0), 0.0, 1.0);
			                vec3 svSage = vec3(0.52, 0.76, 0.64) * (svL / 0.6745);
			                float svF = smoothstep(18.0, 120.0, length(position)) * svregsky_Wind.w;
			                _vert_color.rgb = mix(svNear, svSage, svF);
			            }
			        }
			        if (svregsky_Season.x + svregsky_Season.y + svregsky_Season.z > 0.001) {
			            // seasons: blossoms and fresh greens, autumn reds and golds, frosty winter
			            vec3 svC = _vert_color.rgb;
			            bool svIsLeaf = svK == 26;
			            // leaves always take the season colour; other blocks only where they are green (grass, plants)
			            float svGreen = svIsLeaf ? 1.0 : smoothstep(0.02, 0.10, svC.g - max(svC.r, svC.b));
			            if (svGreen > 0.0) {
			                float svL = dot(svC, vec3(0.30, 0.59, 0.11));
			                // a per-block random number from region-local coordinates: stable, so colours never jump around as you move
			                vec3 svLoc = floor(_vert_position + _get_draw_translation(_draw_id) + 0.001);
			                float svH = svHash(svLoc.xz + vec2(svLoc.y * 7.13, 3.7));
			                vec3 svOut = svC;
			                // spring
			                vec3 svSpr = svIsLeaf && svH < 0.22 ? vec3(1.0, 0.84, 0.91) : svC * vec3(0.96, 1.08, 0.90);
			                svOut = mix(svOut, svSpr, svregsky_Season.x);
			                // autumn
			                vec3 svAut = svIsLeaf
			                    ? (svH < 0.4 ? vec3(0.93, 0.52, 0.18) : (svH < 0.7 ? vec3(0.80, 0.30, 0.18) : vec3(0.95, 0.75, 0.25))) * clamp(svL / 0.45, 0.75, 1.15)
			                    : mix(svC, vec3(0.74, 0.68, 0.32) * (svL / 0.6), 0.55);
			                svOut = mix(svOut, svAut, svregsky_Season.y);
			                // winter
			                vec3 svWin = mix(svC, vec3(svL * 1.1 + 0.18) * vec3(0.92, 0.97, 1.06), 0.62);
			                svOut = mix(svOut, svWin, svregsky_Season.z);
			                _vert_color.rgb = mix(svC, clamp(svOut, 0.0, 1.0), svGreen);
			            }
			        }
			    }
			#endif
			""";

	private static final String GRADE = """
			#ifndef VULKAN
			    {
			        // Ghibli colour grading: warm sunlit surfaces, cool blue shade, warm lamplight, soft blue nights
			        float svG = svregsky_Grade.x;
			        float svDay = svregsky_Grade.z, svGold = svregsky_Grade.w;
			        float svSkyL = _vert_tex_light_coord.y, svBlockL = _vert_tex_light_coord.x;
			        vec3 svSunT = mix(vec3(1.06, 1.01, 0.90), vec3(1.12, 0.96, 0.82), svGold);
			        vec3 svT = mix(vec3(0.86, 0.93, 1.08), svSunT, smoothstep(0.55, 0.90, svSkyL));
			        svT = mix(vec3(0.84, 0.92, 1.14), svT, clamp(svDay + svGold * 0.5, 0.0, 1.0));
			        svT = mix(svT, vec3(1.14, 0.98, 0.80), smoothstep(0.30, 0.85, svBlockL) * (1.0 - svSkyL * svDay * 0.7));
			        // warmer lamplight at night
			        vec3 svExtra = mix(vec3(1.0), vec3(1.22, 1.04, 0.78), svregsky_Look.x * smoothstep(0.35, 0.9, svBlockL) * (1.0 - svDay * 0.8));
			        // dappled sunlight under trees: soft moving light spots where leaves shade the ground
			        float svUnder = (1.0 - smoothstep(0.89, 0.93, svSkyL)) * step(0.6, svSkyL) * svDay;
			        if (svregsky_Look.y > 0.0 && svUnder > 0.0) {
			            vec3 svWp2 = position + svregsky_Cam.xyz;
			            float svDap = svNoise(svWp2.xz * 0.9 + vec2(sin(svregsky_Wind.x * 0.8), cos(svregsky_Wind.x * 0.6)) * 0.6);
			            svExtra *= mix(1.0, mix(0.86, 1.12, smoothstep(0.45, 0.65, svDap)), svUnder * svregsky_Look.y);
			        }
			        // a storm coming: the daylight dims and cools a little
			        svExtra *= mix(vec3(1.0), vec3(0.80, 0.84, 0.92), svregsky_Look.w * smoothstep(0.6, 0.9, svSkyL) * 0.8);
			        // blue hour: the land turns a soft blue, lamplight glows its warmest
			        float svBh = svregsky_Extra.w;
			        svT = mix(svT, vec3(0.80, 0.86, 1.12), svBh * smoothstep(0.5, 0.9, svSkyL));
			        svExtra *= mix(vec3(1.0), vec3(1.18, 1.0, 0.82), svBh * smoothstep(0.3, 0.85, svBlockL));
			        vec3 svCol = v_Color.rgb * mix(vec3(1.0), svT, max(svG, svBh)) * svExtra;
			        // wet ground after rain: darker, a little cooler, outdoors only
			        float svWet = svregsky_Season.w * smoothstep(0.80, 0.92, svSkyL);
			        svCol *= mix(vec3(1.0), vec3(0.68, 0.71, 0.79), svWet);
			        float svLum = dot(svCol, vec3(0.30, 0.59, 0.11));
			        v_Color.rgb = mix(vec3(svLum), svCol, 1.0 + 0.10 * svG);
			        // low morning mist in valleys and over water
			        float svWy = position.y + svregsky_Cam.y;
			        float svM = svregsky_Grade.y * (1.0 - smoothstep(62.0, 78.0, svWy)) * smoothstep(3.0, 48.0, length(position));
			        // valley fog: low land far below the camera sits in a soft haze
			        svM = max(svM, svregsky_Look.z * smoothstep(6.0, 30.0, svregsky_Cam.y - svWy) * smoothstep(20.0, 96.0, length(position)) * 0.7);
			        v_svMist = vec4(mix(vec3(0.92, 0.88, 0.86), vec3(0.88, 0.93, 0.98), svDay), clamp(svM * 0.95, 0.0, 0.85));
			        // water surface: the top of a still water block sits at 8/9 of the block, and water is tinted blue
			        float svFy = fract(_vert_position.y + 0.0001); // chunk-local, so it can't wobble with the camera
			        float svIsWater = svWaterMark;
			        v_svWater = vec4(position, svIsWater);
			        v_svInfo = vec4(svSkyL, svDay, svGold, svKind);
			    }
			#endif
			""";


	private static final String FRAG_DECL = """
			#ifndef VULKAN
			in vec4 v_svMist;
			in vec4 v_svWater;
			in vec4 v_svInfo;
			uniform vec4 svregsky_Wind;
			uniform vec4 svregsky_Cam;
			uniform vec4 svregsky_Sun;
			uniform vec4 svregsky_SkyA; // horizon toward the sun, water mirror on/off
			uniform vec4 svregsky_SkyB; // horizon away from the sun, anime face light on/off
			uniform vec4 svregsky_SkyC; // zenith colour, night (moon instead of sun)
			uniform vec4 svregsky_Look2; // sparkle dots, unused...
			uniform vec4 svregsky_Shadow;  // height map offset x, z, camera height above the map floor, shadow strength
			uniform vec4 svregsky_Shadow2; // march steps, reach, colour (0 anime, 1 neutral, 2 warm), debug view
			uniform sampler2D svregsky_HeightTex; // tallest solid block per column, 16 bits in r + g; b plants; a = water depth << 4 | shore distance
			uniform vec4 svregsky_Water;   // anime water, shore waves, column map ready, underwater light patterns
			uniform vec4 svregsky_Extra;   // winter snow amount, flowing-water foam on/off, cloud sea level (+ amount as fraction), time
			// signed distance to the shoreline (blocks, + in the water) and water depth at a grid position, smoothly interpolated
			vec2 svShore(vec2 g) {
			    vec2 gp = g - 0.5;
			    vec2 f = fract(gp);
			    ivec2 i0 = ivec2(floor(gp));
			    vec2 r[4];
			    for (int k = 0; k < 4; k++) {
			        ivec2 c = clamp(i0 + ivec2(k & 1, k >> 1), ivec2(0), ivec2(255));
			        float a = floor(texelFetch(svregsky_HeightTex, c, 0).a * 255.0 + 0.5);
			        float dep = floor(a / 16.0);
			        float dis = a - dep * 16.0;
			        r[k] = vec2(dep > 0.0 ? dis - 0.5 : 0.5 - dis, dep);
			    }
			    return mix(mix(r[0], r[1], f.x), mix(r[2], r[3], f.x), f.y);
			}
			float svHash(vec2 p) {
			    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
			    p3 += dot(p3, p3.yzx + 33.33);
			    return fract((p3.x + p3.y) * p3.z);
			}
			float svNoise2(vec2 p) {
			    vec2 i = floor(p);
			    vec2 f = fract(p);
			    f = f * f * (3.0 - 2.0 * f);
			    return mix(mix(svHash(i), svHash(i + vec2(1.0, 0.0)), f.x), mix(svHash(i + vec2(0.0, 1.0)), svHash(i + vec2(1.0, 1.0)), f.x), f.y);
			}
			#endif
			""";

	private static final String FRAG_CODE = """
			#ifndef VULKAN
			    {
			        vec3 svN = normalize(cross(dFdx(v_svWater.xyz), dFdy(v_svWater.xyz)));
			        vec3 svV = normalize(v_svWater.xyz);
			        if (dot(svN, svV) > 0.0) {
			            svN = -svN;
			        }
			        vec3 svSunD = svregsky_Sun.xyz;
			        if (svregsky_Look2.w > 0.5) {
			            // debug view: red = water detected, green = flat top faces, blue = mirror on
			            color.rgb = vec3(v_svWater.w, svN.y > 0.7 ? 1.0 : 0.0, svregsky_SkyA.w);
			            color.a = 1.0;
			        } else if (v_svWater.w > 1.5 && svN.y < 0.7 && svregsky_Extra.y > 0.5) {
			            // a waterfall: pale turquoise with white streaks pouring down, foamier toward the bottom of each block
			            vec3 svWf = v_svWater.xyz + svregsky_Cam.xyz;
			            float svU = dot(svWf.xz, normalize(vec2(-svN.z, svN.x) + vec2(1e-4)));
			            float svTf = svregsky_Wind.x;
			            float svS1 = svNoise2(vec2(svU * 3.5, svWf.y * 0.45 + svTf * 2.4));
			            float svS2 = svNoise2(vec2(svU * 7.0 + 3.0, svWf.y * 0.9 + svTf * 3.6));
			            float svStreak = smoothstep(0.48, 0.68, svS1 * 0.65 + svS2 * 0.35);
			            float svLitW = (0.22 + 0.78 * max(v_svInfo.y, v_svInfo.z)) * mix(0.5, 1.0, v_svInfo.x);
			            vec3 svWc = mix(vec3(0.46, 0.84, 0.90), vec3(0.97, 1.0, 1.0), svStreak * 0.9);
			            color.rgb = mix(color.rgb, svWc * svLitW, 0.85);
			            color.a = max(color.a, 0.78 + 0.2 * svStreak);
			        } else if (v_svWater.w > 0.5 && svN.y > 0.7 && svregsky_SkyA.w > 0.5) {
			            // water as a mirror of the sky, with little moving waves and a sun / moon glitter path
			            vec3 svWp = v_svWater.xyz + svregsky_Cam.xyz;
			            float svT = svregsky_Wind.x;
			            vec2 svWv = vec2(sin(svWp.x * 1.7 + svT * 1.3 + sin(svWp.z * 0.9 + svT) * 1.2),
			                             sin(svWp.z * 1.9 - svT * 1.1 + sin(svWp.x * 0.7 - svT * 0.8) * 1.1));
			            vec3 svWn = normalize(vec3(svWv.x * 0.07, 1.0, svWv.y * 0.07));
			            vec3 svR = reflect(svV, svWn);
			            float svUp = clamp(svR.y, 0.0, 1.0);
			            float svToward = dot(normalize(svR.xz + vec2(1e-4)), normalize(svSunD.xz + vec2(1e-4))) * 0.5 + 0.5;
			            vec3 svHz = mix(svregsky_SkyB.rgb, svregsky_SkyA.rgb, svToward * svToward);
			            vec3 svSky = mix(svHz, svregsky_SkyC.rgb, smoothstep(0.0, 0.55, svUp));
			            float svFres = 0.22 + 0.6 * pow(1.0 - clamp(-svV.y, 0.0, 1.0), 3.0);
			            // lighten toward the sky (keeps the water's own turquoise where the sky is darker)
			            vec3 svBase = color.rgb;
			            vec2 svSh = vec2(3.0, 4.0);
			            if (svregsky_Water.z > 0.5) {
			                svSh = svShore(svWp.xz - svregsky_Cam.xz + svregsky_Shadow.xy + vec2(0.0));
			            }
			            if (svregsky_Water.x > 0.5) {
			                // anime water: bright turquoise in the shallows, deep blue further out, the texture's tiling hushed
			                float svDeep = smoothstep(0.5, 6.0, svSh.y) * smoothstep(0.0, 3.0, svSh.x);
			                vec3 svShallow = vec3(0.38, 0.86, 0.86), svDeepC = vec3(0.10, 0.40, 0.78);
			                float svBright = (0.16 + 0.84 * max(v_svInfo.y, v_svInfo.z * 0.9)) * mix(0.45, 1.0, v_svInfo.x);
			                vec3 svAn = mix(svShallow, svDeepC, svDeep) * svBright;
			                float svTexL = dot(color.rgb, vec3(0.30, 0.59, 0.11));
			                svBase = mix(svAn, svAn * clamp(svTexL / 0.45, 0.7, 1.3), 0.15);
			                color.rgb = svBase;
			                color.a = max(color.a, mix(0.62, 0.93, svDeep));
			            }
			            if (v_svWater.w > 1.5 && svregsky_Extra.y > 0.5) {
			                // flowing water: foam streaks drawn along the current (the flow texture runs along v)
			                vec2 svA = dFdx(svWp.xz), svB = dFdy(svWp.xz);
			                vec2 svDv = vec2(dFdx(v_TexCoord.y), dFdy(v_TexCoord.y));
			                float svDet = svA.x * svB.y - svA.y * svB.x;
			                vec2 svGv = abs(svDet) > 1e-8 ? vec2(svB.y * svDv.x - svA.y * svDv.y, -svB.x * svDv.x + svA.x * svDv.y) / svDet : vec2(0.0, 1.0);
			                vec2 svFl = normalize(svGv + vec2(1e-6));
			                float svAlong = dot(svWp.xz, svFl), svAcross = dot(svWp.xz, vec2(-svFl.y, svFl.x));
			                float svFs = svNoise2(vec2(svAcross * 2.6, svAlong * 0.55 - svregsky_Wind.x * 1.6)) * 0.7 + svNoise2(vec2(svAcross * 6.0 + 2.0, svAlong * 1.2 - svregsky_Wind.x * 2.4)) * 0.3;
			                float svFoamF = smoothstep(0.58, 0.80, svFs);
			                float svLitF = (0.22 + 0.78 * max(v_svInfo.y, v_svInfo.z)) * mix(0.5, 1.0, v_svInfo.x);
			                color.rgb = mix(color.rgb, vec3(0.95, 1.0, 1.0) * svLitF, svFoamF * 0.75);
			                color.a = max(color.a, svFoamF * 0.9);
			            }
			            // by day lighten toward the sky (keeps the turquoise); at golden hour the water turns into the sky's colours
			            vec3 svDayMix = mix(svBase, max(svBase, svSky * 0.95) * vec3(0.96, 1.02, 1.02), svFres * 0.6);
			            vec3 svGoldMix = mix(svBase, svSky, clamp(0.55 + svFres * 0.45, 0.0, 0.92));
			            color.rgb = mix(svDayMix, svGoldMix, v_svInfo.z * svregsky_Look2.y);
			            // a mirror is barely see-through: less of the dark bottom shows, so the sky colours stay clean
			            color.a = max(color.a, mix(0.72, 0.92, v_svInfo.z * svregsky_Look2.y));
			            vec3 svL = svregsky_SkyC.w > 0.5 ? -svSunD : svSunD;
			            float svSp = dot(svR, normalize(svL));
			            float svLineK = step(0.90, fract(dot(svWp.xz, vec2(0.6, 0.8)) * 1.4 + svWv.x * 0.35 + svT * 0.35));
			            float svGlint = (step(0.9965, svSp) + svLineK * smoothstep(0.955, 0.992, svSp) * 0.7) * step(-0.05, svL.y);
			            vec3 svLc = svregsky_SkyC.w > 0.5 ? vec3(0.80, 0.86, 1.0) * 0.8 : mix(vec3(1.0, 0.97, 0.88), vec3(1.0, 0.72, 0.42), v_svInfo.z);
			            color.rgb += svLc * svGlint;
			            if (svregsky_SkyB.w > -0.5 && svregsky_Look2.x > 0.5) {
			                // little toon sparkle dashes on the waves, densest toward the sun
			                vec2 svSg = floor(svWp.xz * vec2(1.2, 4.0));
			                float svSpk = step(0.993 - 0.03 * pow(max(dot(svR, normalize(svL)), 0.0), 6.0), svHash(svSg + floor(svT * 2.0) * 0.37));
			                color.rgb = mix(color.rgb, vec3(1.0), svSpk * 0.7 * (1.0 - smoothstep(20.0, 90.0, length(v_svWater.xyz))) * max(v_svInfo.y, v_svInfo.z));
			            }
			            // painted wave lines drifting across the surface
			            float svLines = step(0.93, fract(dot(svWp.xz, vec2(0.31, 0.95)) * 0.55 + svWv.y * 0.12 + svT * 0.12))
			                          * step(0.5, fract(dot(svWp.xz, vec2(0.95, -0.31)) * 0.2 + svT * 0.05));
			            color.rgb = mix(color.rgb, vec3(0.92, 0.98, 1.0), svLines * 0.18 * v_svInfo.y);
			            if (svregsky_Water.y > 0.5 && svSh.x < 2.4) {
			                // anime surf: foam lines rolling in to the shore, lacy where they break, and a pale shallow band
			                float svLace = svNoise2(svWp.xz * 0.9) * 0.6 + svNoise2(svWp.xz * 2.7) * 0.4;
			                float svFoam = 0.0;
			                for (int svF = 0; svF < 3; svF++) {
			                    float svPh = fract(svT * 0.12 + float(svF) / 3.0);
			                    float svPos = (1.0 - svPh) * 1.9 + 0.1;
			                    float svWd = 0.08 + 0.18 * svPh;
			                    float svLn = smoothstep(svWd, 0.0, abs(svSh.x - svPos - (svLace - 0.5) * 0.5));
			                    svFoam = max(svFoam, svLn * smoothstep(0.0, 0.25, svPh) * smoothstep(1.0, 0.8, svPh) * (0.55 + 0.45 * svPh) * step(0.35 - 0.25 * svPh, svLace));
			                }
			                float svLit = (0.25 + 0.75 * max(v_svInfo.y, v_svInfo.z)) * mix(0.5, 1.0, v_svInfo.x);
			                vec3 svFc = mix(vec3(1.0), vec3(1.0, 0.86, 0.72), v_svInfo.z * 0.6) * svLit;
			                color.rgb = mix(color.rgb, vec3(0.72, 0.96, 0.96) * svLit, (1.0 - smoothstep(0.0, 0.8, svSh.x)) * 0.35);
			                color.rgb = mix(color.rgb, svFc, svFoam * 0.92);
			                color.a = max(color.a, svFoam * 0.95);
			            }
			        } else if (svregsky_Water.y > 0.5 && svN.y > 0.7 && v_svWater.w < 0.5 && v_svInfo.x > 0.5
			                   && color.r > color.b + 0.08 && color.g > color.b + 0.04 && color.r > color.g * 0.85) {
			            // the wash on the beach: a thin sheet of water and foam slides up the sand and back, leaving it wet
			            vec3 svWp3 = v_svWater.xyz + svregsky_Cam.xyz;
			            vec2 svG0 = v_svWater.xz + svregsky_Shadow.xy;
			            float svA0 = floor(texelFetch(svregsky_HeightTex, clamp(ivec2(svG0), ivec2(0), ivec2(255)), 0).a * 255.0 + 0.5);
			            vec2 svSh2 = (svA0 < 16.0 && svA0 - floor(svA0 / 16.0) * 16.0 <= 2.0) ? svShore(svG0) : vec2(-9.0, 0.0);
			            float svUp = -svSh2.x;
			            if (svUp < 1.6) {
			                float svT3 = svregsky_Wind.x;
			                float svReach = 0.9 * (0.5 + 0.5 * sin(svT3 * 0.75 + svNoise2(svWp3.xz * 0.05) * 3.0));
			                float svLace2 = svNoise2(svWp3.xz * 0.9) * 0.6 + svNoise2(svWp3.xz * 2.7) * 0.4;
			                float svEdge = svReach + (svLace2 - 0.5) * 0.3;
			                float svLit2 = (0.25 + 0.75 * max(v_svInfo.y, v_svInfo.z)) * mix(0.5, 1.0, v_svInfo.x);
			                if (svUp < svEdge) {
			                    vec3 svWash = mix(vec3(0.55, 0.88, 0.90), vec3(0.82, 0.95, 0.96), svUp / max(svEdge, 0.01)) * svLit2;
			                    color.rgb = mix(color.rgb, svWash, 0.75);
			                    color.rgb = mix(color.rgb, vec3(1.0) * svLit2, smoothstep(svEdge - 0.12, svEdge, svUp) * 0.85);
			                } else {
			                    color.rgb *= mix(vec3(0.80, 0.78, 0.84), vec3(1.0), smoothstep(svEdge, 1.6, svUp));
			                }
			            }
			        } else if (svregsky_SkyB.w > 0.5 && v_svInfo.x > 0.6 && v_svInfo.y > 0.05) {
			            // anime light: faces toward the sun a touch warmer, faces away cool lavender-blue
			            float svD = dot(svN, normalize(vec3(svSunD.x, max(svSunD.y, 0.2), svSunD.z)));
			            float svK = smoothstep(0.6, 0.9, v_svInfo.x) * v_svInfo.y;
			            vec3 svTone = svD > 0.2 ? mix(vec3(1.06, 1.01, 0.90), vec3(1.10, 0.96, 0.84), v_svInfo.z)
			                        : (svD < -0.2 ? vec3(0.84, 0.88, 1.05) : vec3(0.96, 0.97, 1.0));
			            color.rgb *= mix(vec3(1.0), svTone, svK * 0.85);
			        }
			        if (svregsky_Shadow.w > 0.0) {
			            // world sun shadows: walk toward the sun over the column height map
			            vec3 svL2 = normalize(svregsky_Sun.xyz);
			            float svDist = length(v_svWater.xyz);
			            float svRange = svregsky_Shadow2.y;
			            float svOcc = 0.0;
			            bool svIn = svDist < svRange && v_svInfo.x > 0.2;
			            if (svIn) {
			                vec3 svQ0 = v_svWater.xyz + svN * 0.06;
			                vec2 svCell0 = floor(svQ0.xz + svregsky_Shadow.xy);
			                float svT2 = 0.35;
			                int svSteps = int(svregsky_Shadow2.x + 0.5);
			                for (int svI = 0; svI < 48; svI++) {
			                    if (svI >= svSteps || svOcc > 0.99) {
			                        break;
			                    }
			                    vec3 svQ = svQ0 + svL2 * svT2;
			                    vec2 svG = svQ.xz + svregsky_Shadow.xy;
			                    if (svG.x < 0.0 || svG.y < 0.0 || svG.x >= 256.0 || svG.y >= 256.0) {
			                        break;
			                    }
			                    vec4 svH = texelFetch(svregsky_HeightTex, ivec2(svG), 0);
			                    float svTop = floor(svH.r * 255.0 + 0.5) + floor(svH.g * 255.0 + 0.5) * 256.0;
			                    float svY = svQ.y + svregsky_Shadow.z;
			                    if (svY < svTop - 0.02) {
			                        svOcc = 1.0;
			                    } else if (svH.b > 0.0 && svT2 < 4.0 && floor(svG) != svCell0) {
			                        // grass / flowers in a neighbouring column: a soft, blade-patterned little shadow
			                        float svPh = svH.b * 255.0 / 128.0;
			                        vec2 svBl = floor(svG * 4.0);
			                        if (svY < svTop + svPh * (0.55 + 0.45 * svHash(svBl)) && svHash(svBl + 17.0) > 0.35) {
			                            svOcc = max(svOcc, 0.6);
			                        }
			                    }
			                    svT2 += 0.30 + svT2 * 0.10;
			                }
			            }
			            if (svregsky_Shadow2.w > 0.5) {
			                // shadow check: blue = shadow, light grey = sunlit, dark grey = out of reach / no sky
			                color.rgb = !svIn ? vec3(0.35) : mix(vec3(0.85), vec3(0.15, 0.25, 1.0), svOcc);
			            } else {
			                float svK2 = svOcc * svregsky_Shadow.w * (1.0 - smoothstep(svRange * 0.6, svRange, svDist)) * smoothstep(0.2, 0.6, v_svInfo.x);
			                vec3 svSc = svregsky_Shadow2.z < 0.5 ? vec3(0.32, 0.36, 0.62) : (svregsky_Shadow2.z < 1.5 ? vec3(0.36) : vec3(0.52, 0.37, 0.28));
			                color.rgb *= mix(vec3(1.0), svSc, svK2);
			            }
			        }
			        vec3 svWg = v_svWater.xyz + svregsky_Cam.xyz;
			        if (svregsky_Water.w > 0.5 && svregsky_Water.z > 0.5 && v_svWater.w < 0.5 && v_svInfo.x < 0.97) {
			            // under water: dancing light patterns on the bottom, a clean turquoise tint
			            vec2 svGc = v_svWater.xz + svregsky_Shadow.xy - svN.xz * 0.05;
			            if (svGc.x >= 0.0 && svGc.y >= 0.0 && svGc.x < 256.0 && svGc.y < 256.0) {
			                vec4 svHc = texelFetch(svregsky_HeightTex, ivec2(svGc), 0);
			                float svAc = floor(svHc.a * 255.0 + 0.5);
			                float svTopC = floor(svHc.r * 255.0 + 0.5) + floor(svHc.g * 255.0 + 0.5) * 256.0;
			                float svUnder = svTopC - (v_svWater.y + svregsky_Shadow.z);
			                if (svAc >= 16.0 && svUnder > 0.15) {
			                    float svTc = svregsky_Wind.x;
			                    float svC1 = svNoise2(svWg.xz * 1.7 + vec2(svTc * 0.45, svTc * 0.28));
			                    float svC2 = svNoise2(svWg.xz * 2.3 + vec2(-svTc * 0.36, svTc * 0.40) + 5.2);
			                    float svCa = smoothstep(0.80, 1.0, 1.0 - abs(svC1 - svC2) * 4.0);
			                    float svFade = 1.0 - smoothstep(2.0, 12.0, svUnder);
			                    float svDayC = max(v_svInfo.y, v_svInfo.z * 0.8);
			                    color.rgb *= mix(vec3(1.0), vec3(0.74, 0.93, 1.0), 0.45);
			                    color.rgb += vec3(0.80, 1.0, 1.0) * svCa * 0.30 * svFade * svDayC;
			                }
			            }
			        }
			        if (svregsky_Look2.z > 0.01 && v_svWater.w < 0.5 && v_svInfo.x > 0.8 && (v_svInfo.w > 0.5 || (svN.y > 0.5
			                && color.g > color.r * 1.05 && color.g > color.b * 1.1))) {
			            // morning dew: twinkling drops on grass tops, grass blades and leaves, brighter toward the sun
			            // (tops use the ground plane, blades and leaves their own face plane)
			            vec2 svDu = svN.y > 0.5 ? svWg.xz : vec2(dot(svWg.xz, normalize(vec2(-svN.z, svN.x) + vec2(1e-4))), svWg.y);
			            vec2 svDc = floor(svDu * 6.0);
			            float svDh = svHash(svDc + floor(svWg.y) * 7.1);
			            vec2 svDp = (svDc + 0.2 + 0.6 * vec2(svHash(svDc + 3.1), svHash(svDc + 5.7))) / 6.0;
			            float svDd = length(svDu - svDp) * 6.0;
			            vec3 svRv = reflect(svV, svN.y > 0.5 ? vec3(0.0, 1.0, 0.0) : svN);
			            float svToSun = pow(max(dot(svRv, normalize(svSunD)), 0.0), 2.0);
			            float svTw = 0.55 + 0.45 * sin(svregsky_Wind.x * (2.0 + svDh * 5.0) + svDh * 40.0);
			            float svOn = step(0.80 - 0.25 * svToSun, svDh) * svTw;
			            float svSpark = svOn * (exp(-svDd * svDd * 14.0) + 0.5 * exp(-svDd * svDd * 3.0)) * (1.0 - smoothstep(5.0, 22.0, length(v_svWater.xyz)));
			            color.rgb += mix(vec3(1.0, 0.95, 0.82), vec3(1.0), svToSun) * svSpark * svregsky_Look2.z * (0.9 + svToSun);
			        }
			        if (svregsky_Extra.x > 0.01 && v_svWater.w < 0.5 && v_svInfo.x > 0.75) {
			            // winter: snow settles on the tops of blocks, leaves and roofs (a thin lip on the sides), in patches that grow
			            float svNz = svNoise2(svWg.xz * 0.45) * 0.65 + svNoise2(svWg.xz * 1.7) * 0.35;
			            float svCover = smoothstep(svNz - 0.08, svNz + 0.08, svregsky_Extra.x * 1.25 - 0.15);
			            float svLitS = (0.22 + 0.78 * max(v_svInfo.y, v_svInfo.z)) * mix(0.55, 1.0, smoothstep(0.75, 1.0, v_svInfo.x));
			            float svSl = dot(svN, normalize(svSunD + vec3(0.0, 0.4, 0.0)));
			            vec3 svSnow = (svSl > 0.35 ? vec3(1.0) : (svSl > -0.1 ? vec3(0.90, 0.94, 1.0) : vec3(0.78, 0.85, 1.0))) * svLitS;
			            svSnow = mix(svSnow, svSnow * vec3(1.05, 0.95, 0.88), v_svInfo.z * 0.6);
			            if (svN.y > 0.55) {
			                color.rgb = mix(color.rgb, svSnow, svCover * (v_svInfo.w > 1.5 ? 0.5 : 0.95));
			            } else if (svN.y > -0.3 && v_svInfo.w < 1.5) {
			                float svLip = step(0.86 - 0.06 * svNz, fract(svWg.y - 0.0001));
			                color.rgb = mix(color.rgb, svSnow * 0.96, svLip * svCover * 0.9);
			            }
			        }
			        float svCsA = fract(svregsky_Extra.z), svCsY = floor(svregsky_Extra.z);
			        if (svCsA > 0.01 && v_svWater.y < -0.5) {
			            // the cloud sea: seen from up high, the valleys below are filled with a soft sea of cloud
			            float svCamY = svregsky_Cam.y;
			            float svFy = v_svWater.y + svCamY;
			            if (svCamY > svCsY + 2.0 && svFy < svCsY + 6.0) {
			                vec3 svHp = v_svWater.xyz * clamp((svCsY - svCamY) / v_svWater.y, 0.0, 1.0);
			                vec2 svQ = (svHp.xz + svregsky_Cam.xz) * 0.018 + vec2(svregsky_Cam.w * 0.012, svregsky_Cam.w * 0.004);
			                float svH0 = svNoise2(svQ) * 0.6 + svNoise2(svQ * 2.3 + 3.1) * 0.3 + svNoise2(svQ * 5.1 + 7.7) * 0.1;
			                float svHx = svNoise2(svQ + vec2(0.12, 0.0)) * 0.6 + svNoise2((svQ + vec2(0.12, 0.0)) * 2.3 + 3.1) * 0.3 + svNoise2((svQ + vec2(0.12, 0.0)) * 5.1 + 7.7) * 0.1;
			                float svHz = svNoise2(svQ + vec2(0.0, 0.12)) * 0.6 + svNoise2((svQ + vec2(0.0, 0.12)) * 2.3 + 3.1) * 0.3 + svNoise2((svQ + vec2(0.0, 0.12)) * 5.1 + 7.7) * 0.1;
			                float svBelow = svCsY + (svH0 - 0.35) * 14.0 - svFy;
			                float svCa2 = smoothstep(-1.0, 4.0, svBelow) * svCsA;
			                vec3 svCn = normalize(vec3(-(svHx - svH0) * 7.0, 1.0, -(svHz - svH0) * 7.0));
			                float svL3 = dot(svCn, normalize(svSunD + vec3(0.0, 0.15, 0.0))) * 0.35 + 0.45 + (svH0 - 0.45) * 1.1;
			                float svGd = v_svInfo.z, svDd = v_svInfo.y;
			                vec3 svC0 = mix(vec3(0.58, 0.72, 0.96), vec3(0.42, 0.30, 0.52), svGd), svC1b = mix(vec3(0.74, 0.85, 1.0), vec3(0.58, 0.42, 0.62), svGd);
			                vec3 svC2b = mix(vec3(0.89, 0.95, 1.0), vec3(0.90, 0.66, 0.60), svGd), svC3 = mix(vec3(1.0), vec3(1.0, 0.86, 0.64), svGd);
			                vec3 svCs = svL3 > 0.78 ? svC3 : (svL3 > 0.62 ? svC2b : (svL3 > 0.45 ? svC1b : svC0));
			                svCs *= 0.25 + 0.75 * max(svDd, svGd);
			                // far away it melts into the horizon colour
			                vec3 svHzC = mix(svregsky_SkyB.rgb, svregsky_SkyA.rgb, 0.5);
			                svCs = mix(svCs, svHzC, smoothstep(60.0, 260.0, length(svHp.xz)) * 0.6);
			                color.rgb = mix(color.rgb, svCs, svCa2);
			                color.a = max(color.a, svCa2);
			            }
			        }
			    }
			#endif
			""";

	/** adds the wind code to Sodium's terrain vertex shader source; returns it unchanged if the anchors are missing */
	public static String patchVertexShader(String src) {
		LOCS.clear(); // shaders are being (re)built: program ids and uniform locations change
		LAST_UPLOAD.clear();
		if (src.contains("svregsky_Wind")) {
			return src;
		}
		int main = src.indexOf("void main()");
		int pos = src.indexOf("gl_Position = u_ProjectionMatrix");
		String colorLine = "v_Color = _vert_color * texture(u_LightTex, _vert_tex_light_coord);";
		int col = src.indexOf(colorLine);
		if (main < 0 || pos < 0 || pos < main || col < pos || !src.contains("vec3 position")) {
			return src;
		}
		int afterCol = col + colorLine.length();
		return src.substring(0, main) + DECL + "\n" + src.substring(main, main + src.substring(main).indexOf('{') + 1)
				+ "\n#ifndef VULKAN\n    v_svMist = vec4(0.0);\n    v_svWater = vec4(0.0);\n    v_svInfo = vec4(0.0);\n    svWaterMark = 0.0;\n    svKind = 0.0;\n#endif\n"
				+ src.substring(main + src.substring(main).indexOf('{') + 1, pos) + CODE + "\n    "
				+ src.substring(pos, afterCol) + "\n" + GRADE + src.substring(afterCol);
	}

	/** the fragment side of the morning mist (only when the vertex shader was patched too) */
	public static String patchFragmentShader(String src) {
		if (src.contains("v_svMist")) {
			return src;
		}
		int main = src.indexOf("void main()");
		String anchor = "color *= v_Color;";
		int at = src.indexOf(anchor);
		if (main < 0 || at < main) {
			return src;
		}
		int end = at + anchor.length();
		return src.substring(0, main) + FRAG_DECL + src.substring(main, end) + "\n" + FRAG_CODE
				+ "\n#ifndef VULKAN\n    color.rgb = mix(color.rgb, v_svMist.rgb, v_svMist.a);\n#endif" + src.substring(end);
	}
}
