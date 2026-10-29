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
	private static int lastProgram = -1, locWind = -1, locCam = -1, locSun = -1, locGrade = -1, locPlayer = -1, locLook = -1, locSeason = -1, locSkyA = -1, locSkyB = -1, locSkyC = -1, locLook2 = -1;
	private static long skyNanos;
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

	private static float smoothstep(float e0, float e1, float x) {
		float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
		return t * t * (3 - 2 * t);
	}

	public static boolean enabled() {
		return SkySettings.windStrength > 0 || SkySettings.farGrassTint > 0;
	}

	/** sets the wind uniforms on Sodium's current terrain program (called for every region draw) */
	public static void upload() {
		int prog = GL20C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
		if (prog == 0) {
			return;
		}
		if (prog != lastProgram) {
			lastProgram = prog;
			locWind = GL20C.glGetUniformLocation(prog, "svregsky_Wind");
			locCam = GL20C.glGetUniformLocation(prog, "svregsky_Cam");
			locSun = GL20C.glGetUniformLocation(prog, "svregsky_Sun");
			locGrade = GL20C.glGetUniformLocation(prog, "svregsky_Grade");
			locPlayer = GL20C.glGetUniformLocation(prog, "svregsky_Player");
			locLook = GL20C.glGetUniformLocation(prog, "svregsky_Look");
			locSeason = GL20C.glGetUniformLocation(prog, "svregsky_Season");
			locSkyA = GL20C.glGetUniformLocation(prog, "svregsky_SkyA");
			locSkyB = GL20C.glGetUniformLocation(prog, "svregsky_SkyB");
			locSkyC = GL20C.glGetUniformLocation(prog, "svregsky_SkyC");
			locLook2 = GL20C.glGetUniformLocation(prog, "svregsky_Look2");
		}
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
			GL20C.glUniform4f(locLook, SkySettings.on("warm_lamps") ? 1 : 0, SkySettings.on("dappled_light") ? 1 : 0, vf, 0);
		}
		if (locSkyA >= 0 || locSkyB >= 0 || locSkyC >= 0) {
			long now = System.nanoTime();
			if (now - skyNanos > 30_000_000L) { // sky colours for the water mirror, refreshed ~30 times a second
				skyNanos = now;
				float[] a = com.svreg.sky.sky.CirrusSky.horizonToward(true), b = com.svreg.sky.sky.CirrusSky.horizonToward(false);
				float day = com.svreg.sky.sky.CirrusSky.dayLevel(), gold = com.svreg.sky.sky.CirrusSky.goldLevel(), night = com.svreg.sky.sky.CirrusSky.nightLevel();
				skyA = new float[]{a[0], a[1], a[2], SkySettings.on("water_reflection") ? 1 : 0};
				skyB = new float[]{b[0], b[1], b[2], SkySettings.on("face_light") ? 1 : 0};
				float zr = 0.06F + (0.30F - 0.06F) * day, zg = 0.08F + (0.58F - 0.08F) * day, zb = 0.22F + (0.95F - 0.22F) * day;
				zr += 0.25F * gold; zg += 0.05F * gold;
				skyC = new float[]{zr, zg, zb, night > 0.5F ? 1 : 0};
			}
			if (locSkyA >= 0) GL20C.glUniform4f(locSkyA, skyA[0], skyA[1], skyA[2], skyA[3]);
			if (locSkyB >= 0) GL20C.glUniform4f(locSkyB, skyB[0], skyB[1], skyB[2], skyB[3]);
			if (locSkyC >= 0) GL20C.glUniform4f(locSkyC, skyC[0], skyC[1], skyC[2], skyC[3]);
			if (locLook2 >= 0) GL20C.glUniform4f(locLook2, SkySettings.on("water_sparkle_dots") ? 1 : 0, SkySettings.on("water_sunset") ? 1 : 0, 0, debugWater ? 1 : 0);
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
			out vec4 v_svWater;           // camera-relative position, water surface flag
			float svWaterMark;            // 1 for water vertices (marked by the fluid mesher)
			out float v_svDbgA;
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
			        v_svDbgA = _vert_color.a;
			        svWaterMark = svK == 28 ? 1.0 : 0.0;
			        if (svK == 28) {
			            _vert_color.a = 1.0;
			        }
			        vec3 svWp = position + svregsky_Cam.xyz;
			        float svStr = svregsky_Wind.y;
			        if (svK > 0 && (svK <= 24 || svK == 26)) {
			            _vert_color.a = 1.0;
			            bool svLeaf = svK == 26;
			            float svW = svLeaf ? 0.35 : float(svK) / 12.0;
			            float svT = svregsky_Wind.x * svregsky_Wind.z;
			            vec2 svDir = vec2(0.943, 0.333);
			            vec2 svSide = vec2(-svDir.y, svDir.x);
			            float svAlong = dot(svWp.xz, svDir);
			            float svPh = dot(svWp.xz, vec2(0.37, 0.21));
			            // big gust bands rolling across the meadow + smaller cross ripples
			            float svWave = 0.5 + 0.5 * sin(svAlong * 0.16 - svT * 2.1);
			            float svWave2 = 0.5 + 0.5 * sin(svAlong * 0.41 + dot(svWp.xz, svSide) * 0.23 - svT * 3.3);
			            float svGust = clamp(0.62 * svWave * svWave + 0.38 * svWave2 * svWave, 0.0, 1.0);
			            float svFlutter = 0.6 * sin(svT * 7.3 + svPh * 2.0) + 0.4 * sin(svT * 11.7 + svPh * 3.1);
			            float svLean = svLeaf
			                ? svStr * (0.10 + 0.20 * svGust + 0.08 * svFlutter)
			                : svStr * (0.20 + 0.62 * svGust + 0.07 * svFlutter);
			            float svAmt = svLean * svW * 0.62;
			            position.xz += svDir * svAmt + svSide * (svStr * svW * 0.05 * svFlutter);
			            position.y -= svAmt * svAmt * 0.42;
			            if (svregsky_Player.w > 0.5 && !svLeaf) {
			                // plants bend away from the player walking through them
			                vec2 svAway = position.xz - svregsky_Player.xz;
			                float svD = length(svAway);
			                float svPush = (1.0 - smoothstep(0.2, 1.3, svD)) * step(abs(position.y - svregsky_Player.y), 2.2) * clamp(svW, 0.0, 1.5);
			                position.xz += svAway / max(svD, 0.05) * svPush * 0.45;
			                position.y -= svPush * 0.25;
			            }
			            // light sheen on the bent grass, so the gusts show as bright waves
			            _vert_color.rgb *= 1.0 + min(svStr, 2.0) * (svLeaf ? 0.05 : 0.13) * svGust * clamp(svW, 0.0, 1.0);
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
			        vec3 svCol = v_Color.rgb * mix(vec3(1.0), svT, svG) * svExtra;
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
			        v_svInfo = vec4(svSkyL, svDay, svGold, 0.0);
			    }
			#endif
			""";


	private static final String FRAG_DECL = """
			#ifndef VULKAN
			in vec4 v_svMist;
			in vec4 v_svWater;
			in vec4 v_svInfo;
			in float v_svDbgA;
			uniform vec4 svregsky_Wind;
			uniform vec4 svregsky_Cam;
			uniform vec4 svregsky_Sun;
			uniform vec4 svregsky_SkyA; // horizon toward the sun, water mirror on/off
			uniform vec4 svregsky_SkyB; // horizon away from the sun, anime face light on/off
			uniform vec4 svregsky_SkyC; // zenith colour, night (moon instead of sun)
			uniform vec4 svregsky_Look2; // sparkle dots, unused...
			float svHash(vec2 p) {
			    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
			    p3 += dot(p3, p3.yzx + 33.33);
			    return fract((p3.x + p3.y) * p3.z);
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
			            color.rgb = vec3(v_svWater.w, fract(v_svDbgA * 255.0 / 32.0), 1.0 - v_svDbgA);
			            color.a = 1.0;
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
			            // by day lighten toward the sky (keeps the turquoise); at golden hour the water turns into the sky's colours
			            vec3 svDayMix = mix(svBase, max(svBase, svSky * 0.95) * vec3(0.96, 1.02, 1.02), svFres * 0.6);
			            vec3 svGoldMix = mix(svBase, svSky, clamp(0.55 + svFres * 0.45, 0.0, 0.92));
			            color.rgb = mix(svDayMix, svGoldMix, v_svInfo.z * svregsky_Look2.y);
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
			        } else if (svregsky_SkyB.w > 0.5 && v_svInfo.x > 0.6 && v_svInfo.y > 0.05) {
			            // anime light: faces toward the sun a touch warmer, faces away cool lavender-blue
			            float svD = dot(svN, normalize(vec3(svSunD.x, max(svSunD.y, 0.2), svSunD.z)));
			            float svK = smoothstep(0.6, 0.9, v_svInfo.x) * v_svInfo.y;
			            vec3 svTone = svD > 0.2 ? mix(vec3(1.06, 1.01, 0.90), vec3(1.10, 0.96, 0.84), v_svInfo.z)
			                        : (svD < -0.2 ? vec3(0.84, 0.88, 1.05) : vec3(0.96, 0.97, 1.0));
			            color.rgb *= mix(vec3(1.0), svTone, svK * 0.85);
			        }
			    }
			#endif
			""";

	/** adds the wind code to Sodium's terrain vertex shader source; returns it unchanged if the anchors are missing */
	public static String patchVertexShader(String src) {
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
				+ "\n#ifndef VULKAN\n    v_svMist = vec4(0.0);\n    v_svWater = vec4(0.0);\n    v_svInfo = vec4(0.0);\n    svWaterMark = 0.0;\n    v_svDbgA = 1.0;\n#endif\n"
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
