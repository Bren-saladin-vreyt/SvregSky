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
	private static int lastProgram = -1, locWind = -1, locCam = -1, locSun = -1, locGrade = -1;

	private Wind() {
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
			        vec3 svT = mix(vec3(0.86, 0.93, 1.08), svSunT, smoothstep(0.55, 0.92, svSkyL));
			        svT = mix(vec3(0.84, 0.92, 1.14), svT, clamp(svDay + svGold * 0.5, 0.0, 1.0));
			        svT = mix(svT, vec3(1.14, 0.98, 0.80), smoothstep(0.30, 0.85, svBlockL) * (1.0 - svSkyL * svDay * 0.7));
			        vec3 svCol = v_Color.rgb * mix(vec3(1.0), svT, svG);
			        float svLum = dot(svCol, vec3(0.30, 0.59, 0.11));
			        v_Color.rgb = mix(vec3(svLum), svCol, 1.0 + 0.10 * svG);
			        // low morning mist in valleys and over water
			        float svWy = position.y + svregsky_Cam.y;
			        float svM = svregsky_Grade.y * (1.0 - smoothstep(62.0, 78.0, svWy)) * smoothstep(6.0, 70.0, length(position));
			        v_svMist = vec4(mix(vec3(0.92, 0.88, 0.86), vec3(0.88, 0.93, 0.98), svDay), clamp(svM * 0.75, 0.0, 0.8));
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
				+ "\n#ifndef VULKAN\n    v_svMist = vec4(0.0);\n#endif\n"
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
		return src.substring(0, main) + "#ifndef VULKAN\nin vec4 v_svMist;\n#endif\n" + src.substring(main, end)
				+ "\n#ifndef VULKAN\n    color.rgb = mix(color.rgb, v_svMist.rgb, v_svMist.a);\n#endif" + src.substring(end);
	}
}
