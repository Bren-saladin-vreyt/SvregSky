// node hrender.js out.png '{"w","h","fov","yaw","pitch","show","tod","sun"}'
const { chromium } = require('playwright'); const fs = require('fs'); const path = require('path');
const [out, optStr] = process.argv.slice(2);
const o = Object.assign({ w: 1640, h: 680, fov: 80, yaw: 165, pitch: 15, show: 3, tod: 'day', sun: [0.4, 0.5, -0.75], tm: 3 }, JSON.parse(optStr || '{}'));
const fsh = fs.readFileSync(path.join(__dirname, o.src || "hillsbg.fsh"), "utf8");
(async () => {
  const b = await chromium.launch({ args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  const p = await b.newPage();
  const r = await p.evaluate(([fsh, o]) => {
    const cv = document.createElement('canvas'); cv.width = o.w; cv.height = o.h;
    const gl = cv.getContext('webgl2', { preserveDrawingBuffer: true });
    const mk = (t, s) => { const sh = gl.createShader(t); gl.shaderSource(sh, s); gl.compileShader(sh); if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) throw gl.getShaderInfoLog(sh); return sh; };
    const pr = gl.createProgram();
    gl.attachShader(pr, mk(gl.VERTEX_SHADER, '#version 300 es\nin vec2 P; void main(){ gl_Position=vec4(P,0.,1.); }'));
    gl.attachShader(pr, mk(gl.FRAGMENT_SHADER, fsh)); gl.bindAttribLocation(pr, 0, 'P'); gl.linkProgram(pr); gl.useProgram(pr);
    const vb = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, vb); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
    const sky = { day: [[0.16, 0.52, 0.98], [0.55, 0.82, 1.0]], gold: [[0.92, 0.45, 0.10], [1.0, 0.70, 0.25]], blue: [[0.12, 0.17, 0.43], [0.62, 0.48, 0.62]] }[o.tod];
    const U = n => gl.getUniformLocation(pr, n);
    gl.uniform2f(U('res'), o.w, o.h); gl.uniform1f(U('fov'), o.fov * Math.PI / 180); gl.uniform1f(U('yaw'), o.yaw * Math.PI / 180);
    gl.uniform1f(U('pitch'), o.pitch * Math.PI / 180); gl.uniform1f(U('tm'), o.tm); gl.uniform3fv(U('top'), sky[0]); gl.uniform3fv(U('hor'), sky[1]);
    const l = Math.hypot(...o.sun); gl.uniform3fv(U('sunDir'), o.sun.map(v => v / l)); gl.uniform1f(U('show'), o.show);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    return cv.toDataURL('image/png');
  }, [fsh, o]);
  fs.writeFileSync(out, Buffer.from(r.split(',')[1], 'base64')); await b.close();
})().catch(e => { console.log('ERR', e); process.exit(1); });
