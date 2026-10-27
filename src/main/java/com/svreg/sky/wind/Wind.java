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
	private static int lastProgram = -1, locWind = -1, locCam = -1;

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
		float w;
		if (kind == 2) {
			w = 0.35F;
		} else {
			w = Math.max(0, Math.min(2, localY + (upper ? 1 : 0)));
		}
		int k = Math.round(w * 12);
		if (kind == 2) {
			k = Math.max(k, 1);
		}
		return 255 - Math.min(24, k);
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
		}
		if (locWind >= 0) {
			float t = (float) (((System.nanoTime() - START) / 1e9) % 3600.0);
			GL20C.glUniform4f(locWind, t, SkySettings.windStrength, SkySettings.windSpeed, SkySettings.farGrassTint);
		}
		if (locCam >= 0) {
			GL20C.glUniform4f(locCam, (float) (camX % 4096.0), (float) camY, (float) (camZ % 4096.0), 0);
		}
	}

	private static final String DECL = """

			// ---- SvregSky wind + far grass ----
			#ifndef VULKAN
			uniform vec4 svregsky_Wind; // time, strength, speed, far grass tint
			uniform vec4 svregsky_Cam;  // camera position (x, z wrapped)
			#endif
			""";

	private static final String CODE = """
			#ifndef VULKAN
			    {
			        int svK = 255 - int(_vert_color.a * 255.0 + 0.5);
			        vec3 svWp = position + svregsky_Cam.xyz;
			        if (svK > 0 && svK <= 24) {
			            _vert_color.a = 1.0;
			            float svW = float(svK) / 12.0;
			            float svT = svregsky_Wind.x * svregsky_Wind.z;
			            vec2 svDir = vec2(0.94, 0.33);
			            float svPh = dot(svWp.xz, vec2(0.37, 0.21));
			            float svGust = 0.5 + 0.5 * sin(svT * 0.7 - dot(svWp.xz, svDir) * 0.09);
			            float svSway = 0.08 + 0.30 * svGust * svGust + 0.07 * sin(svT * 2.6 + svPh) + 0.03 * sin(svT * 5.1 + svPh * 1.7);
			            float svAmt = svregsky_Wind.y * svSway * svW * 0.55;
			            position.xz += svDir * svAmt;
			            position.y -= svAmt * svAmt * 0.30;
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

	/** adds the wind code to Sodium's terrain vertex shader source; returns it unchanged if the anchors are missing */
	public static String patchVertexShader(String src) {
		if (src.contains("svregsky_Wind")) {
			return src;
		}
		int main = src.indexOf("void main()");
		int pos = src.indexOf("gl_Position = u_ProjectionMatrix");
		if (main < 0 || pos < 0 || pos < main || !src.contains("vec3 position")) {
			return src;
		}
		String out = src.substring(0, main) + DECL + "\n" + src.substring(main, pos) + CODE + "\n    " + src.substring(pos);
		return out;
	}
}
