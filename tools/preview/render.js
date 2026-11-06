// Offline preview of cirrus_raymarch.fsh: march into a 1/scale buffer, upscale nearest over a simple sky.
// usage: node render.js <fsh> <outPrefix> <json opts>
// opts: {w,h,scale,yaw,pitch,cam:[x,y,z],sun:[x,y,z],tod:'day'|'gold'|'night',frames,move:[dx,dy,dz],fov,reach,steps,amount,png:true}
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const [fshPath, outPrefix, optStr] = process.argv.slice(2);
const o = Object.assign({ w: 820, h: 340, scale: 4, yaw: 0, pitch: 20, cam: [0, -110, 0], sun: [0.4, 0.5, -0.75], tod: 'day',
  frames: 1, move: [0, 0, 0], fov: 80, reach: 1500, steps: 80, amount: 1.0, rain: 0, png: true }, JSON.parse(optStr || '{}'));
const fsh = fs.readFileSync(fshPath, 'utf8').replace('#version 330', '#version 300 es\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;');
const vsh = `#version 300 es
in vec2 P; out highp vec2 uv; void main(){ gl_Position=vec4(P,0.,1.); uv=P*0.5+0.5; }`;
const comp = `#version 300 es
precision highp float; uniform sampler2D C; uniform float S, fade, reach, P00, P11; uniform mat4 MV; uniform vec3 top, hor; in vec2 uv; out vec4 o;
uniform float SM;
void main(){ vec4 c=texelFetch(C, ivec2(gl_FragCoord.xy/S),0); vec3 sky=mix(hor, top, pow(uv.y,0.6));
 float cover = 1.0;
 if (SM > 0.5) {
   // smooth edges, same as cirrus_rmcomp.fsh
   ivec2 sz = textureSize(C, 0); vec2 p = gl_FragCoord.xy / S - 0.5; ivec2 i = ivec2(floor(p)); vec2 f = p - floor(p);
   vec4 c00 = texelFetch(C, clamp(i, ivec2(0), sz - 1), 0), c10 = texelFetch(C, clamp(i + ivec2(1, 0), ivec2(0), sz - 1), 0);
   vec4 c01 = texelFetch(C, clamp(i + ivec2(0, 1), ivec2(0), sz - 1), 0), c11 = texelFetch(C, clamp(i + ivec2(1, 1), ivec2(0), sz - 1), 0);
   float th = 1.0 / 255.0;
   float w00 = (1.0 - f.x) * (1.0 - f.y) * step(th, c00.a), w10 = f.x * (1.0 - f.y) * step(th, c10.a), w01 = (1.0 - f.x) * f.y * step(th, c01.a), w11 = f.x * f.y * step(th, c11.a);
   float m = w00 + w10 + w01 + w11;
   if (m < 0.35) { o = vec4(sky, 1.0); return; }
   cover = clamp((m - 0.5) / max(fwidth(m), 1e-3) + 0.5, 0.0, 1.0);
   c.rgb = (c00.rgb * w00 + c10.rgb * w10 + c01.rgb * w01 + c11.rgb * w11) / m;
   c.a = max(max(c00.a * step(th, c00.a), c10.a * step(th, c10.a)), max(c01.a * step(th, c01.a), c11.a * step(th, c11.a)));
 }
 if (c.a<=0.5/255.0) { o=vec4(sky,1.0); return; }
 float a = 1.0;
 if (fade > 0.0) {
   float t = exp2((1.0 - c.a) * log2(4097.0)) - 1.0;
   vec3 vd = normalize(vec3((uv*2.0-1.0)/vec2(P00,P11), -1.0));
   vec3 rd = transpose(mat3(MV)) * vd;
   float low = 1.0 - smoothstep(0.02, 0.30, rd.y);
   float aerA = min(0.85, ((t > 3500.0 ? 0.10 : smoothstep(0.30, 1.0, t / reach) * 0.55) * (0.5 + 0.5 * low)) * fade);
   a = 1.0 - aerA;
 }
 o=vec4(mix(sky, c.rgb, a * cover),1.0); }`;
const hmSrc = fs.readFileSync(path.join(path.join(__dirname, "..", "..", "src", "main", "resources", "assets", "svregsky", "shaders", "core"), 'cirrus_hillmask.fsh'), 'utf8').replace('#version 330', '#version 300 es\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;');
const noiseB64 = fs.readFileSync(path.join(__dirname, 'out', o.noise || 'crumble.png')).toString('base64');

(async () => {
  const b = await chromium.launch({ args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  const p = await b.newPage();
  p.on('console', m => console.log('page:', m.text()));
  const res = await p.evaluate(async ([fsh, vsh, comp, o, noiseB64, hmSrc]) => {
    const cv = document.createElement('canvas'); cv.width = o.w; cv.height = o.h;
    const gl = cv.getContext('webgl2', { preserveDrawingBuffer: true });
    const err = [];
    const mk = (t, s) => { const sh = gl.createShader(t); gl.shaderSource(sh, s); gl.compileShader(sh); if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) err.push(gl.getShaderInfoLog(sh)); return sh; };
    const prog = (a, c) => { const pr = gl.createProgram(); gl.attachShader(pr, mk(gl.VERTEX_SHADER, a)); gl.attachShader(pr, mk(gl.FRAGMENT_SHADER, c)); gl.bindAttribLocation(pr, 0, 'P'); gl.linkProgram(pr); if (!gl.getProgramParameter(pr, gl.LINK_STATUS)) err.push(gl.getProgramInfoLog(pr)); return pr; };
    const vshM = vsh.replace('in vec2 P', 'in vec3 Position').replace('vec4(P,0.,1.)', 'vec4(Position.xy,0.,1.)').replace('uv=P*', 'uv=Position.xy*');
    const pm = prog(vshM, fsh), pc = prog(vsh, comp);
    if (err.length) return { err: err.join('\n') };
    gl.bindAttribLocation(pm, 0, 'Position'); gl.linkProgram(pm);
    const vb = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, vb);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
    // noise texture
    const img = new Image(); img.src = 'data:image/png;base64,' + noiseB64; await img.decode();
    const nt = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, nt);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, img);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    // low-res target
    const bw = Math.ceil(o.w / o.scale), bh = Math.ceil(o.h / o.scale);
    const bt = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, bt);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, bw, bh, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
    const fb = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, fb); gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, bt, 0);
    // UBOs
    const ubo = gl.createBuffer(), pubo = gl.createBuffer();
    gl.uniformBlockBinding(pm, gl.getUniformBlockIndex(pm, 'DynamicTransforms'), 0);
    gl.uniformBlockBinding(pm, gl.getUniformBlockIndex(pm, 'Projection'), 1);
    const f = 1 / Math.tan(o.fov * Math.PI / 360), asp = o.w / o.h, n = 0.05, fa = 4096;
    const proj = new Float32Array([f / asp, 0, 0, 0, 0, f, 0, 0, 0, 0, -(fa + n) / (fa - n), -1, 0, 0, -2 * fa * n / (fa - n), 0]);
    gl.bindBuffer(gl.UNIFORM_BUFFER, pubo); gl.bufferData(gl.UNIFORM_BUFFER, proj, gl.DYNAMIC_DRAW);
    const tod = { day: [1, 0, 0], gold: [0.3, 1, 0], night: [0, 0, 1], blue: [0, 0.15, 0.7] }[o.tod];
    const sky = { day: [[0.16, 0.52, 0.98], [0.55, 0.82, 1.0]], gold: [[0.92, 0.45, 0.10], [1.0, 0.70, 0.25]], night: [[0.05, 0.07, 0.25], [0.12, 0.16, 0.42]], blue: [[0.12, 0.17, 0.43], [0.62, 0.48, 0.62]] }[o.tod];
    const sl = Math.hypot(...o.sun), sun = o.sun.map(v => v / sl);
    // hill silhouette strip (Sampler1), baked once like the mod does
    const hm = prog(vshM, hmSrc); gl.bindAttribLocation(hm, 0, 'Position'); gl.linkProgram(hm);
    const hmT = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, hmT);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, 1024, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.REPEAT); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    { const hf = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, hf); gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, hmT, 0);
      const hu = gl.createBuffer(); const hd = new Float32Array(40); hd.set([0, 1, 0, 0], 16);
      gl.bindBuffer(gl.UNIFORM_BUFFER, hu); gl.bufferData(gl.UNIFORM_BUFFER, hd, gl.DYNAMIC_DRAW);
      gl.uniformBlockBinding(hm, gl.getUniformBlockIndex(hm, 'DynamicTransforms'), 3); gl.bindBufferBase(gl.UNIFORM_BUFFER, 3, hu);
      gl.viewport(0, 0, 1024, 1); gl.useProgram(hm); gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4); }
    const frames = [];
    for (let fi = 0; fi < o.frames; fi++) {
      const yaw = (o.yaw + (o.yawStep || 0) * fi) * Math.PI / 180, pit = o.pitch * Math.PI / 180;
      // view = Rx(-pitch) * Ry(yaw): world -> view, camera looks along -z at yaw 0
      const cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pit), sp = Math.sin(pit);
      // rows of view rotation: right, up, back
      const right = [cy, 0, sy], fwd = [sy * cp, sp, -cy * cp];
      const up = [-sy * sp * -1 * -1, cp, 0]; // placeholder, recompute below
      const u = [right[1] * fwd[2] - right[2] * fwd[1], right[2] * fwd[0] - right[0] * fwd[2], right[0] * fwd[1] - right[1] * fwd[0]];
      const back = fwd.map(v => -v);
      // column-major mat4 with rows right,u,back
      const mv = [right[0], u[0], back[0], 0, right[1], u[1], back[1], 0, right[2], u[2], back[2], 0, 0, 0, 0, 1];
      const cam = o.cam.map((v, i) => v + o.move[i] * fi);
      const t = o.time0 !== undefined ? o.time0 + fi * 0.05 : fi * 0.05;
      const tm = [sun[0], sun[1], sun[2], o.layer || 125, tod[0], tod[1], tod[2], o.rain,
        sky[1][0], sky[1][1], sky[1][2], o.amount, sky[1][0], o.t3y || 0, sky[1][1], sky[1][2]];
      const data = new Float32Array(40);
      data.set(mv, 0); data.set([o.bufH ? bh : t, o.reach, o.steps, o.org || 0], 16); data.set(cam, 20); data.set(tm, 24);
      gl.bindBuffer(gl.UNIFORM_BUFFER, ubo); gl.bufferData(gl.UNIFORM_BUFFER, data, gl.DYNAMIC_DRAW);
      gl.bindBufferBase(gl.UNIFORM_BUFFER, 0, ubo); gl.bindBufferBase(gl.UNIFORM_BUFFER, 1, pubo);
      gl.bindFramebuffer(gl.FRAMEBUFFER, fb); gl.viewport(0, 0, bw, bh); gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT);
      gl.useProgram(pm); gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, nt);
      gl.uniform1i(gl.getUniformLocation(pm, 'Sampler0'), 0);
      gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D, hmT); gl.uniform1i(gl.getUniformLocation(pm, 'Sampler1'), 1); gl.activeTexture(gl.TEXTURE0);
      { const px1 = new Uint8Array(4); gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px1); }
      const t0 = performance.now();
      for (let r = 0; r < (o.reps || 1); r++) gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      { const px1 = new Uint8Array(4); gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px1); }
      console.log('marchMs ' + ((performance.now() - t0) / (o.reps || 1)).toFixed(1));
      gl.bindFramebuffer(gl.FRAMEBUFFER, null); gl.viewport(0, 0, o.w, o.h);
      gl.useProgram(pc); gl.bindTexture(gl.TEXTURE_2D, bt);
      gl.uniform1i(gl.getUniformLocation(pc, 'C'), 0); gl.uniform1f(gl.getUniformLocation(pc, 'S'), o.scale);
      gl.uniform1f(gl.getUniformLocation(pc, 'fade'), o.fade || 0); gl.uniform1f(gl.getUniformLocation(pc, 'SM'), o.smooth ? 1 : 0); gl.uniform1f(gl.getUniformLocation(pc, 'reach'), o.reach);
      gl.uniform1f(gl.getUniformLocation(pc, 'P00'), proj[0]); gl.uniform1f(gl.getUniformLocation(pc, 'P11'), proj[5]); gl.uniformMatrix4fv(gl.getUniformLocation(pc, 'MV'), false, new Float32Array(mv));
      gl.uniform3fv(gl.getUniformLocation(pc, 'top'), o.skyKey ? [1, 0, 1] : sky[0]); gl.uniform3fv(gl.getUniformLocation(pc, 'hor'), o.skyKey ? [1, 0, 1] : sky[1]);
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      const px = new Uint8Array(o.w * o.h * 4); gl.readPixels(0, 0, o.w, o.h, gl.RGBA, gl.UNSIGNED_BYTE, px);
      frames.push({ px: Array.from(px), url: (o.png && (fi === 0 || o.allPng)) ? cv.toDataURL('image/png') : null });
    }
    return { frames };
  }, [fsh, vsh, comp, o, noiseB64, hmSrc]);
  if (res.err) { console.log('ERR', res.err); process.exit(1); }
  // flicker metric: mean abs diff of consecutive frames
  let tot = 0, cnt = 0, big = 0;
  for (let i = 1; i < res.frames.length; i++) {
    const a = res.frames[i - 1].px, c = res.frames[i].px;
    for (let k = 0; k < a.length; k += 4) { const d = Math.abs(a[k] - c[k]) + Math.abs(a[k + 1] - c[k + 1]) + Math.abs(a[k + 2] - c[k + 2]); tot += d; cnt++; if (d > 40) big++; }
  }
  {
    // speckle: low-res cloud pixels that differ (by >30) from at least 3 of their 4 neighbours = isolated dots that shimmer
    const px = res.frames[0].px, S = o.scale, bw = Math.floor(o.w / S), bh = Math.floor(o.h / S);
    const at = (x, y) => { const k = ((y * S + (S >> 1)) * o.w + x * S + (S >> 1)) * 4; return [px[k], px[k + 1], px[k + 2]]; };
    let sp = 0, cl = 0;
    for (let y = 1; y < bh - 1; y++) for (let x = 1; x < bw - 1; x++) {
      const c = at(x, y); if (c[0] < 120) continue; cl++; // cloud-ish (sky red channel is low)
      let dn = 0; for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) { const q = at(x + dx, y + dy); if (Math.abs(q[0] - c[0]) + Math.abs(q[1] - c[1]) + Math.abs(q[2] - c[2]) > 30) dn++; }
      if (dn >= 3) sp++;
    }
    console.log(`speckle%=${(100 * sp / Math.max(1, cl)).toFixed(2)} (of ${cl} cloud px)`);
  }
  if (cnt) console.log(`flicker: meanAbsDiff=${(tot / cnt).toFixed(3)} changedPx%=${(100 * big / cnt).toFixed(2)}`);
  res.frames.forEach((fr, i) => { if (fr.url) fs.writeFileSync(`${outPrefix}_${i}.png`, Buffer.from(fr.url.split(',')[1], 'base64')); });
  await b.close();
})();
