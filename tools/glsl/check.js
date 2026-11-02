const { chromium } = require('playwright');
const fs = require('fs');
const inc = {
  'minecraft:dynamictransforms.glsl': 'layout(std140) uniform DynamicTransforms { mat4 ModelViewMat; vec4 ColorModulator; vec3 ModelOffset; mat4 TextureMat; };',
  'minecraft:projection.glsl': 'layout(std140) uniform Projection { mat4 ProjMat; };',
};
function prep(src) {
  return src.replace('#version 330', '#version 300 es\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;')
    .replace(/#moj_import <([^>]+)>/g, (_, n) => inc[n] || ('#error missing ' + n));
}
(async () => {
  const [vs, fs_] = process.argv.slice(2).map(f => prep(fs.readFileSync(f, 'utf8')));
  const b = await chromium.launch({ args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader'] });
  const p = await b.newPage();
  const r = await p.evaluate(([vs, fs_]) => {
    const gl = document.createElement('canvas').getContext('webgl2');
    if (!gl) return 'no webgl2';
    const out = [];
    const mk = (t, s) => { const sh = gl.createShader(t); gl.shaderSource(sh, s); gl.compileShader(sh); if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) out.push(gl.getShaderInfoLog(sh)); return sh; };
    const prog = gl.createProgram();
    gl.attachShader(prog, mk(gl.VERTEX_SHADER, vs)); gl.attachShader(prog, mk(gl.FRAGMENT_SHADER, fs_));
    gl.linkProgram(prog);
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) out.push('link: ' + gl.getProgramInfoLog(prog));
    return out.join('\n') || 'OK';
  }, [vs, fs_]);
  console.log(r);
  await b.close();
})();
