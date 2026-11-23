// A minimal WebGL2 sprite batcher — the world's draw path on GPU browsers.
//
// Canvas2D pays CPU per API call: at herd scale the viewer was bound by the
// calls themselves, not the pixels (the classic 2D-canvas wall; see PixiJS's
// batching model, which this borrows in miniature). Here every textured quad
// is appended to one vertex buffer and flushed in a single drawElements per
// RUN OF QUADS SHARING A TEXTURE — so a sorted entity list of a thousand
// creatures costs a handful of draw calls, and the static world layers cost
// one each. No dependency: the whole engine is one shader pair and one
// interleaved buffer.
//
// Textures are canvases the 2D code authored (atlas bakes, layer composites,
// fixture stamps), uploaded once and re-uploaded only when their revision
// bumps — the catalog-of-record doctrine holds, because everything on screen
// is still painted by the same authoring code; the GPU only composites it.

const MAX_QUADS = 4096;
const FLOATS_PER_VERT = 9; // x, y, u, v, r, g, b, a, mode
/** Textures one sprite batch may sample. A batch used to break at every
 *  texture change, and the bodies are drawn in painter's order, so a herd of
 *  mixed shapes ("A B A B ...") was a draw call per body. With several
 *  textures bound at once (multi-texture batching, as PixiJS and Phaser do)
 *  the batch breaks only when a ninth distinct texture arrives. */
const SLOTS = 8;
/** The unit uploads bind on: outside the batch's slots and the full-screen
 *  passes' units (1-4), so an upload never disturbs a batch being built. */
const UPLOAD_UNIT = SLOTS + 4;

const VS = `#version 300 es
in vec2 aPos; in vec2 aUV; in vec4 aCol; in float aMode;
uniform vec2 uRes;
out vec2 vUV; out vec4 vCol; flat out int vMode;
void main() {
  gl_Position = vec4(aPos.x * 2.0 / uRes.x - 1.0, 1.0 - aPos.y * 2.0 / uRes.y, 0.0, 1.0);
  vUV = aUV;
  vCol = aCol;
  vMode = int(aMode + 0.5);
}`;

// A vertex's mode is its texture slot times two plus its shading: 0 the
// plain premultiplied multiply every layer, stamp and dot uses, 1 the tint.
// The slot picks one of SLOTS samplers by a branch: GLSL ES 3.0 indexes a
// sampler array only by a constant.
//
// Shading 0 is the plain premultiplied multiply every layer, stamp and dot uses.
// Shading 1 is the creature tint: the texel is a COLOUR-NEUTRAL bake (the body's
// palette encoded as greys around a mid-grey pivot — see ProcCreature.neutral
// server-side), and the quad's colour is the creature's rgb. Greys at or below
// the pivot invert the bake's shade() (tint scaled toward black); greys above
// invert mixWhite() (tint mixed toward white) — so one atlas per SHAPE serves
// every colour a lineage drifts through. Saturated texels pass through
// untinted, with a soft gate so downscaled edge pixels blend instead of
// fringing.
const FS = `#version 300 es
precision mediump float;
in vec2 vUV; in vec4 vCol; flat in int vMode;
uniform sampler2D uTex[${SLOTS}];
out vec4 outColor;
vec4 slot(int i, vec2 uv) {
${Array.from({ length: SLOTS }, (_, i) => `  if (i == ${i}) return texture(uTex[${i}], uv);`).join('\n')}
  return vec4(0.0);
}
void main() {
  vec4 t = slot(vMode >> 1, vUV);
  if ((vMode & 1) == 0) { outColor = t * vCol; return; }
  float a = max(t.a, 1e-4);
  vec3 u = t.rgb / a;
  float hi = max(u.r, max(u.g, u.b)), lo = min(u.r, min(u.g, u.b));
  float pivot = 128.0 / 255.0;
  vec3 tinted = hi <= pivot
    ? vCol.rgb * (hi / pivot)
    : mix(vCol.rgb, vec3(1.0), (hi - pivot) / (1.0 - pivot));
  vec3 c = mix(tinted, u, smoothstep(0.08, 0.30, hi - lo));
  outColor = vec4(c, 1.0) * (t.a * vCol.a);
}`;

// The tile layer: a whole level's worth of one-tile sprites in ONE draw, with
// no per-tile work on the CPU. A single triangle covers the viewport; each
// pixel finds its world tile, reads that tile's atlas cell from a small index
// texture (two bytes a tile), and fetches its art-pixel from the cell. The CPU
// only ever uploads the index: a few bytes a tile when the state changes,
// instead of repainting and re-uploading chunk canvases.
//
// Below one screen pixel per art-pixel the cell's art would alias into
// shimmer, so the shader fades to the cell's AVERAGE colour (one texel per
// cell, precomputed) -- the GPU form of the old pre-smoothed far-zoom mirror.
// Coordinates are highp: a level is thousands of art-pixels wide, past what
// mediump can address exactly, and the classic mobile failure is art that
// swims as precision runs out across the map.
const TILE_VS = `#version 300 es
void main() {
  vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}`;
export const TILE_FS = `#version 300 es
precision highp float;
precision highp int;
uniform highp sampler2D uTex0;  // index, RG8 per tile: r = atlas column (255 = empty), g = atlas row
uniform highp sampler2D uTex1;  // atlas, cells of uCell x uCell art-pixels, premultiplied
uniform highp sampler2D uTex2;  // one premultiplied texel per cell: its average
uniform highp sampler2D uTex3;  // the ground under the plants, premultiplied (when uGround > 0)
uniform float uGround;          // ground texels per tile, or 0 to draw the plants alone
uniform vec2 uRes;              // canvas size, px
uniform vec2 uOrigin;           // screen px of world (0,0)
uniform float uScale;           // target px per tile
uniform float uZoom;            // screen px per tile: what the fade reads
uniform vec2 uSize;             // level size, tiles
uniform float uCell;            // art-pixels per tile
uniform float uAlpha;
out vec4 outColor;
void main() {
  vec2 px = vec2(gl_FragCoord.x, uRes.y - gl_FragCoord.y);
  vec2 w = (px - uOrigin) / uScale;
  if (w.x < 0.0 || w.y < 0.0 || w.x >= uSize.x || w.y >= uSize.y) discard;
  ivec2 tile = ivec2(floor(w));
  // The ground first, when this pass draws it too: one pass writes each
  // screen pixel once instead of a ground quad and a plant pass both.
  vec4 base = uGround > 0.0 ? texelFetch(uTex3, ivec2(floor(w * uGround)), 0) : vec4(0.0);
  vec2 id = texelFetch(uTex0, tile, 0).rg * 255.0 + 0.5;
  int col = int(id.x), row = int(id.y);
  if (col == 255) {
    if (base.a <= 0.0) discard;
    outColor = base;
    return;
  }
  vec4 avg = texelFetch(uTex2, ivec2(col, row), 0);
  // art-pixels per screen pixel: above 1 the art aliases, so fade to the average
  float far = clamp((uCell / uZoom - 0.75) * 2.0, 0.0, 1.0);
  vec4 c = avg;
  if (far < 1.0) {
    int n = int(uCell);
    ivec2 a = ivec2(floor(fract(w) * uCell));
    vec4 t = texelFetch(uTex1, ivec2(col * n + a.x, row * n + a.y), 0);
    c = mix(t, avg, far);
  }
  c *= uAlpha;
  c += base * (1.0 - c.a);
  if (c.a <= 0.0) discard;
  outColor = c;
}`;

// The composite of an offscreen target onto the screen (GLRenderer.present).
// A target holds the world at one pixel per art-pixel; the screen shows it
// `uK` times larger, offset by `uShift` px, a fraction of a pixel included.
// Plain nearest sampling at a scale that is not a whole number gives texels
// of uneven widths, and under a moving fraction the unevenness crawls: the
// shimmer pixel-art games avoid. This is the usual answer ("pixel art
// filtering"): each texel is solid, and only across its edge -- one screen
// pixel wide -- does a LINEAR fetch blend it with its neighbour. Crisp at any
// scale, and it moves by fractions of a pixel without crawling.
export const PRESENT_FS = `#version 300 es
precision highp float;
uniform highp sampler2D uTex0;  // the target, premultiplied, LINEAR
uniform vec2 uRes;              // screen size, px
uniform vec2 uShift;            // screen px of the target's top-left corner
uniform float uK;               // screen px per target px
uniform vec2 uSize;             // the part of the target drawn, px
uniform vec2 uCap;              // the target texture's full size, px
out vec4 outColor;
void main() {
  vec2 px = vec2(gl_FragCoord.x, uRes.y - gl_FragCoord.y);
  vec2 t = (px - uShift) / uK;
  if (t.x < 0.0 || t.y < 0.0 || t.x >= uSize.x || t.y >= uSize.y) discard;
  vec2 seam = floor(t + 0.5);
  t = seam + clamp((t - seam) * uK, -0.5, 0.5);
  vec4 c = texture(uTex0, vec2(t.x, uSize.y - t.y) / uCap);
  if (c.a <= 0.0) discard;
  outColor = c;
}`;

/** An offscreen colour target: `w` x `h` px drawn of a texture `cw` x `ch`. */
export interface Target { fb: WebGLFramebuffer; tex: WebGLTexture; w: number; h: number; cw: number; ch: number }

interface TexEntry {
  tex: WebGLTexture; rev: number; w: number; h: number; lastUse: number;
  /** Identity of the source this texture was last uploaded from — see srcId. */
  srcId: number;
}

/** A full-screen program, compiled ahead of use (see `warm`). */
interface Pass {
  prog: WebGLProgram;
  sh: WebGLShader[];
  ok: boolean;
  t0: number;
  u: Map<string, WebGLUniformLocation | null>;
}

export class GLRenderer {
  private gl: WebGL2RenderingContext;
  private verts = new Float32Array(MAX_QUADS * 4 * FLOATS_PER_VERT);
  private vertCount = 0; // quads currently buffered
  private vbo: WebGLBuffer;
  private uRes: WebGLUniformLocation;
  private textures = new Map<string, TexEntry>();
  private white: WebGLTexture;
  /** The textures bound to the batch's slots, unit i holding slotTex[i]. */
  private slotTex: WebGLTexture[] = [];
  private stats = { drawCalls: 0, quads: 0, uploadMs: 0, textures: 0 };
  private frameNo = 0;
  /** Resident-texture caps. A long-evolved world breeds a new phenotype
   *  (= a new atlas) with nearly every lineage, so an uncapped cache grows
   *  with world AGE — hundreds of mipmapped 768px textures will eventually
   *  thrash any phone GPU. Two pools: full-res atlases (~3MB each, only
   *  needed at sprite zoom where few phenotypes fit on screen) stay scarce;
   *  quarter-res mips (~0.2MB each, what the herd view stamps from) get
   *  room for every phenotype a zoomed-out screen can plausibly show.
   *  Layers, stamps and the white pixel are permanent. */
  private static readonly MAX_FULL_SPRITES = 48;
  private static readonly MAX_MIP_SPRITES = 320;
  /** Scratch canvas for cross-browser texSubImage2D patches: a tile region is
   *  copied here and uploaded from (0,0) — UNPACK_SKIP_* on DOM sources is
   *  spottier across browsers than a 12x12 blit is expensive. */
  private patchCv = document.createElement('canvas');
  private spriteProg!: WebGLProgram;

  constructor(cv: HTMLCanvasElement, allowSoftware = false) {
    const gl = cv.getContext('webgl2', { alpha: false, antialias: false });
    if (!gl) throw new Error('webgl2 unavailable');
    // A software GL (SwiftShader, llvmpipe) pays per PIXEL like a software
    // canvas but adds trilinear sampling on top — strictly worse than the 2D
    // fallback path. Only a real GPU should take this renderer.
    if (!allowSoftware) {
      const dbg = gl.getExtension('WEBGL_debug_renderer_info');
      const name = dbg ? String(gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL)) : '';
      if (/swiftshader|llvmpipe|software|basic render/i.test(name)) {
        throw new Error('software webgl: ' + name);
      }
    }
    this.gl = gl;
    this.parallel = gl.getExtension('KHR_parallel_shader_compile');
    this.timer = gl.getExtension('EXT_disjoint_timer_query_webgl2');
    const prog = gl.createProgram()!;
    for (const [type, src] of [[gl.VERTEX_SHADER, VS], [gl.FRAGMENT_SHADER, FS]] as const) {
      const sh = gl.createShader(type)!;
      gl.shaderSource(sh, src);
      gl.compileShader(sh);
      if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) {
        throw new Error('shader: ' + gl.getShaderInfoLog(sh));
      }
      gl.attachShader(prog, sh);
    }
    // Pin attribute slots to the vertexAttribPointer indices below — linkers
    // are free to order them otherwise.
    gl.bindAttribLocation(prog, 0, 'aPos');
    gl.bindAttribLocation(prog, 1, 'aUV');
    gl.bindAttribLocation(prog, 2, 'aCol');
    gl.bindAttribLocation(prog, 3, 'aMode');
    gl.linkProgram(prog);
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) {
      throw new Error('link: ' + gl.getProgramInfoLog(prog));
    }
    gl.useProgram(prog);
    this.spriteProg = prog;
    this.uRes = gl.getUniformLocation(prog, 'uRes')!;
    gl.uniform1iv(gl.getUniformLocation(prog, 'uTex'), Array.from({ length: SLOTS }, (_, i) => i));

    // One interleaved dynamic VBO + a static index buffer of quad triangles.
    this.vbo = gl.createBuffer()!;
    gl.bindBuffer(gl.ARRAY_BUFFER, this.vbo);
    gl.bufferData(gl.ARRAY_BUFFER, this.verts.byteLength, gl.DYNAMIC_DRAW);
    const idx = new Uint16Array(MAX_QUADS * 6);
    for (let q = 0; q < MAX_QUADS; q++) {
      const v = q * 4, i = q * 6;
      idx[i] = v; idx[i + 1] = v + 1; idx[i + 2] = v + 2;
      idx[i + 3] = v; idx[i + 4] = v + 2; idx[i + 5] = v + 3;
    }
    const ibo = gl.createBuffer()!;
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, ibo);
    gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, idx, gl.STATIC_DRAW);
    const stride = FLOATS_PER_VERT * 4;
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, stride, 0);
    gl.enableVertexAttribArray(1);
    gl.vertexAttribPointer(1, 2, gl.FLOAT, false, stride, 8);
    gl.enableVertexAttribArray(2);
    gl.vertexAttribPointer(2, 4, gl.FLOAT, false, stride, 16);
    gl.enableVertexAttribArray(3);
    gl.vertexAttribPointer(3, 1, gl.FLOAT, false, stride, 32);

    // Premultiplied-alpha compositing throughout (canvas uploads premultiply).
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
    gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, true);

    // The 1x1 white texture that solid-colour quads (dots, fills) sample.
    this.white = gl.createTexture()!;
    gl.bindTexture(gl.TEXTURE_2D, this.white);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE,
      new Uint8Array([255, 255, 255, 255]));
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
  }

  /** Starts a frame: clears to the given colour at the given backing size. */
  private vw = 1;
  private vh = 1;
  private screenW = 1;
  private screenH = 1;
  private targets = new Map<string, Target>();

  // ---- GPU timing ---------------------------------------------------------
  // EXT_disjoint_timer_query_webgl2, where the browser has it (many desktop
  // and Android GPUs; never SwiftShader): GPU time per layer, which no JS
  // clock can see. One query per section -- they cannot nest -- read back
  // frames later, when the GPU has finished with them.
  private timer: { TIME_ELAPSED_EXT: number; GPU_DISJOINT_EXT: number } | null = null;
  private timing: { q: WebGLQuery; name: string } | null = null;
  private pending: Array<{ q: WebGLQuery; name: string }> = [];
  private spareQueries: WebGLQuery[] = [];
  /** GPU ms per section name (EMA), and their sum per frame; empty and -1
   *  without timer queries. */
  readonly gpuMs = new Map<string, number>();
  gpuTotalMs = -1;

  /** Ends the running GPU section, if any, and starts one named `name`
   *  (null: none). Work batched before the call counts to the old one. */
  gpuSection(name: string | null): void {
    if (!this.timer) return;
    const gl = this.gl;
    this.flush();
    if (this.timing) {
      gl.endQuery(this.timer.TIME_ELAPSED_EXT);
      this.pending.push(this.timing);
      this.timing = null;
    }
    if (name === null || this.pending.length > 64) return; // a GPU that never answers: stop asking
    const q = this.spareQueries.pop() ?? gl.createQuery()!;
    gl.beginQuery(this.timer.TIME_ELAPSED_EXT, q);
    this.timing = { q, name };
  }

  /** Reads back every finished query (in begin, once a frame). */
  private gpuCollect(): void {
    if (!this.timer || this.pending.length === 0) return;
    const gl = this.gl;
    const disjoint = gl.getParameter(this.timer.GPU_DISJOINT_EXT) as boolean;
    let total = 0, any = false;
    while (this.pending.length > 0) {
      const p = this.pending[0];
      if (!gl.getQueryParameter(p.q, gl.QUERY_RESULT_AVAILABLE)) break;
      this.pending.shift();
      if (!disjoint) {
        const ms = (gl.getQueryParameter(p.q, gl.QUERY_RESULT) as number) / 1e6;
        const old = this.gpuMs.get(p.name);
        this.gpuMs.set(p.name, old === undefined ? ms : old + (ms - old) * 0.1);
        any = true;
      }
      this.spareQueries.push(p.q);
    }
    if (any) {
      for (const v of this.gpuMs.values()) total += v;
      this.gpuTotalMs = total;
    }
  }

  /** Sizes every later draw to a w x h target. The sprite program is the
   *  bound one between passes (fullscreen restores it). */
  private viewport(w: number, h: number): void {
    this.vw = w; this.vh = h;
    this.gl.viewport(0, 0, w, h);
    this.gl.uniform2f(this.uRes, w, h);
  }

  /**
   * Draws from here on into the offscreen target `key`, w x h px, cleared to
   * the premultiplied colour `clear` -- or, with `key` null, onto the screen
   * again. A target's texture only grows, in steps of 256 px: its size
   * follows the zoom, and reallocating it on every frame of a pinch would be
   * an allocation a frame.
   */
  renderTo(key: string | null, w = 0, h = 0, clear: [number, number, number, number] = [0, 0, 0, 0]): Target | null {
    const gl = this.gl;
    this.flush();
    if (key === null) {
      gl.bindFramebuffer(gl.FRAMEBUFFER, null);
      this.viewport(this.screenW, this.screenH);
      return null;
    }
    let t = this.targets.get(key);
    if (!t || t.cw < w || t.ch < h) {
      if (t) { gl.deleteFramebuffer(t.fb); gl.deleteTexture(t.tex); }
      const cw = Math.ceil(w / 256) * 256, ch = Math.ceil(h / 256) * 256;
      const tex = gl.createTexture()!;
      this.bindForUpload(tex);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, cw, ch, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      const fb = gl.createFramebuffer()!;
      gl.bindFramebuffer(gl.FRAMEBUFFER, fb);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, tex, 0);
      t = { fb, tex, w, h, cw, ch };
      this.targets.set(key, t);
    }
    t.w = w; t.h = h;
    gl.bindFramebuffer(gl.FRAMEBUFFER, t.fb);
    this.viewport(w, h);
    gl.clearColor(clear[0], clear[1], clear[2], clear[3]);
    gl.clear(gl.COLOR_BUFFER_BIT);
    return t;
  }

  /** Composites a target onto the screen, `k` screen px per target px, its
   *  corner at (sx, sy) screen px (PRESENT_FS), within `clip` if given. */
  present(t: Target, k: number, sx: number, sy: number, clip: number[] | null = null): void {
    this.fullscreen('present', PRESENT_FS, [t.tex], {
      uK: k, uShift: [sx, sy], uSize: [t.w, t.h], uCap: [t.cw, t.ch],
    }, clip);
  }
  begin(w: number, h: number, r: number, g2: number, b: number): void {
    const gl = this.gl;
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    this.screenW = w; this.screenH = h;
    this.gpuCollect();
    this.viewport(w, h);
    gl.clearColor(r, g2, b, 1);
    gl.clear(gl.COLOR_BUFFER_BIT);
    this.vertCount = 0;
    this.slotTex.length = 0; // textures evicted since may have left the slots
    this.stats = { drawCalls: 0, quads: 0, uploadMs: 0, textures: 0 };
  }

  /** Flushes what remains and reports the frame's batching stats. */
  end(): { drawCalls: number; quads: number; uploadMs: number; textures: number } {
    this.flush();
    // Counted first, with a prefix test per key and nothing allocated: the
    // pools are almost always under their caps, and this runs every frame.
    let full = 0, mip = 0;
    for (const key of this.textures.keys()) {
      const p = GLRenderer.pool(key);
      if (p === 1) full++; else if (p === 2) mip++;
    }
    if (full > GLRenderer.MAX_FULL_SPRITES) this.evictPool(1, GLRenderer.MAX_FULL_SPRITES);
    if (mip > GLRenderer.MAX_MIP_SPRITES) this.evictPool(2, GLRenderer.MAX_MIP_SPRITES);
    this.frameNo++;
    this.stats.textures = this.textures.size;
    return this.stats;
  }

  /** Which sprite pool a texture key belongs to: 1 the full-resolution
   *  atlases (`atlas:`, `corpse:`), 2 their mips (`atlasm:`, `corpsem:`),
   *  0 neither (layers, stamps: permanent). */
  private static pool(key: string): number {
    if (key.startsWith('atlas:') || key.startsWith('corpse:')) return 1;
    if (key.startsWith('atlasm:') || key.startsWith('corpsem:')) return 2;
    return 0;
  }

  /** LRU-trims one sprite pool to its cap — but NEVER a texture drawn this
   *  frame. When more phenotypes are on screen than the cap, evicting live
   *  textures makes every frame delete-and-reupload the same atlases; the
   *  resulting frame-time spike trips the adaptive dot LOD and the whole
   *  view oscillates between sprites and blocks. Briefly exceeding the cap
   *  is the far cheaper failure. */
  private evictPool(which: number, cap: number): void {
    const pool: Array<[string, TexEntry]> = [];
    for (const ent of this.textures) {
      if (GLRenderer.pool(ent[0]) === which) pool.push(ent);
    }
    if (pool.length <= cap) return;
    pool.sort((a, b) => a[1].lastUse - b[1].lastUse);
    let excess = pool.length - cap;
    for (const [key, e] of pool) {
      if (excess === 0 || e.lastUse >= this.frameNo) break;
      this.gl.deleteTexture(e.tex);
      this.textures.delete(key);
      excess--;
    }
  }

  /**
   * Uploads (or re-uses) a canvas as a texture. `rev` is the caller's revision
   * counter for mutable canvases (the composited world layers); bump it and
   * the texture re-uploads, leave it and the GPU copy is trusted. Mipmaps are
   * generated on upload so minification (far zoom) resolves to smooth
   * coverage — the GPU equivalent of the 2D path's precomputed mips — while
   * magnification stays NEAREST so art-pixels are fat and crisp.
   */
  private texFor(key: string, src: TexImageSource & { width: number; height: number },
      rev: number): TexEntry {
    const gl = this.gl;
    let e = this.textures.get(key);
    if (!e) {
      e = { tex: gl.createTexture()!, rev: -1, w: 0, h: 0, lastUse: 0, srcId: 0 };
      this.textures.set(key, e);
    }
    e.lastUse = this.frameNo;
    if (e.rev !== rev) {
      const t0 = performance.now();
      this.flush(); // never mutate a texture the buffered quads still sample
      this.bindForUpload(e.tex);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, src);
      gl.generateMipmap(gl.TEXTURE_2D);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      e.rev = rev;
      e.w = src.width;
      e.h = src.height;
      this.stats.uploadMs += performance.now() - t0;
    }
    return e;
  }

  /** Drops a cached texture (e.g. when a level's layers are rebuilt). */
  evict(key: string): void {
    const e = this.textures.get(key);
    if (e) {
      this.gl.deleteTexture(e.tex);
      this.textures.delete(key);
    }
  }

  /**
   * Drops every world-layer texture belonging to one level — called when the
   * viewer leaves it. Layer keys carry their level (`family:level` or
   * `family:level:chunk`) so two floors can never share a cache entry; without
   * this they would also never share the MEMORY, and a three-storey world
   * would hold three worlds' worth of layers on a phone GPU. Sprite atlases
   * are untouched: they are level-agnostic and have their own LRU pools.
   *
   * <p>The view down -- the floors below as seen through this level's holes
   * -- is tagged with the floor it SHOWS, not the floor it is seen from, so
   * it is not matched by level: every below texture goes. They are cheap to
   * put back (their canvases stay cached on the CPU side) and, left alone,
   * they were the one family nothing ever evicted: a tour of a sky level at
   * near zoom pinned the surface's and the cave's chunks on the GPU for the
   * life of the tab.
   */
  evictLevel(level: number): void {
    const families = new Set(['ground', 'groundlo', 'veg', 'veglo', 'canopy', 'canopylo']);
    const below = new Set(['below', 'belowlo']);
    const tag = String(level);
    for (const key of [...this.textures.keys()]) {
      const part = key.split(':');
      if (part.length >= 2 && (below.has(part[0])
          || (families.has(part[0]) && part[1] === tag))) {
        this.evict(key);
      }
    }
  }

  /**
   * A world-layer quad: like sprite(), but the texture has NO mipmaps (layers
   * barely minify — they live at art resolution) and a bumped revision can be
   * reconciled by PATCHING the changed tile rects instead of re-uploading the
   * whole canvas. The full upload of a level-sized layer plus its mipmap
   * chain was a rhythmic multi-millisecond hitch on phones, every single
   * vegetation poll; the compositor already knows the handful of tiles it
   * repainted, so the GPU copy follows the same increments. A null patch
   * list, a size change, or a skipped revision falls back to a full upload.
   */
  /** A stable id per source object, so a texture can tell "same canvas, new
   *  content" (patch or re-upload) from "a different canvas that happens to
   *  reuse the key". Weakly held: assigning an id never keeps a canvas alive. */
  private srcIds = new WeakMap<object, number>();
  private nextSrcId = 1;
  private srcId(src: object): number {
    let id = this.srcIds.get(src);
    if (id === undefined) {
      id = this.nextSrcId++;
      this.srcIds.set(src, id);
    }
    return id;
  }

  layer(key: string, src: TexImageSource & { width: number; height: number }, rev: number,
      patches: Array<[number, number, number, number]> | null,
      dx: number, dy: number, dw: number, dh: number, alpha = 1): void {
    const e = this.layerUpload(key, src, rev, patches);
    this.push(e.tex, 0, 0, 1, 1, dx, dy, dw, dh, alpha, alpha, alpha, alpha, 0);
  }

  /** A layer's texture brought up to date (patched where it can be) without
   *  drawing it: for a pass that draws it itself (tileLayer's ground). */
  layerUpload(key: string, src: TexImageSource & { width: number; height: number }, rev: number,
      patches: Array<[number, number, number, number]> | null): TexEntry {
    const gl = this.gl;
    let e = this.textures.get(key);
    if (!e) {
      e = { tex: gl.createTexture()!, rev: -1, w: 0, h: 0, lastUse: 0, srcId: 0 };
      this.textures.set(key, e);
    }
    e.lastUse = this.frameNo;
    // A cached texture is stale unless BOTH its revision and the object it was
    // uploaded from still match. Revision alone is not enough: a caller that
    // rebuilds its canvases and restarts their revisions — the vegetation layer
    // does exactly that on every level change — hands back key/rev pairs this
    // cache has already seen, and the GPU keeps showing the old level's art.
    // That was "grass from the wrong level": cycle surface -> -2 and the caves
    // wore the meadow's grass, because chunk `veg:37` was rev 1 on both.
    const id = this.srcId(src);
    if (e.rev !== rev || e.srcId !== id) {
      const t0 = performance.now();
      this.flush(); // never mutate a texture the buffered quads still sample
      this.bindForUpload(e.tex);
      // Patching is only valid against the very same source, one revision on.
      const patchable = e.rev >= 0 && rev - e.rev === 1 && patches !== null
        && e.srcId === id && e.w === src.width && e.h === src.height;
      if (patchable) {
        const pg = this.patchCv.getContext('2d')!;
        for (const [x, y, w, h] of patches) {
          if (this.patchCv.width < w || this.patchCv.height < h) {
            this.patchCv.width = Math.max(this.patchCv.width, w);
            this.patchCv.height = Math.max(this.patchCv.height, h);
          }
          pg.clearRect(0, 0, w, h);
          pg.drawImage(src as CanvasImageSource, x, y, w, h, 0, 0, w, h);
          gl.texSubImage2D(gl.TEXTURE_2D, 0, x, y, w, h, gl.RGBA, gl.UNSIGNED_BYTE, this.patchCv);
        }
      } else {
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, src);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
        e.w = src.width;
        e.h = src.height;
      }
      e.rev = rev;
      e.srcId = id;
      this.stats.uploadMs += performance.now() - t0;
    }
    return e;
  }

  /** A textured quad: source rect (pixels of `src`) to destination rect
   *  (canvas pixels), modulated by alpha. Quads sharing a texture batch.
   *  Pass `tint` (an rgb int) to draw a colour-neutral creature bake through
   *  the ramp-tint shader mode — the quad colour becomes the creature's rgb. */
  sprite(key: string, src: TexImageSource & { width: number; height: number }, rev: number,
      sx: number, sy: number, sw: number, sh: number,
      dx: number, dy: number, dw: number, dh: number, alpha = 1, tint = -1): void {
    const e = this.texFor(key, src, rev);
    if (tint >= 0) {
      this.push(e.tex,
        sx / e.w, sy / e.h, (sx + sw) / e.w, (sy + sh) / e.h,
        dx, dy, dw, dh,
        ((tint >> 16) & 255) / 255, ((tint >> 8) & 255) / 255, (tint & 255) / 255, alpha, 1);
    } else {
      this.push(e.tex,
        sx / e.w, sy / e.h, (sx + sw) / e.w, (sy + sh) / e.h,
        dx, dy, dw, dh, alpha, alpha, alpha, alpha, 0);
    }
  }

  /** Full-screen programs by name, each TILE_VS with its own fragment shader. */
  private passes = new Map<string, Pass>();
  /** Compiles off the main thread where the browser offers it (most do). */
  private parallel: { COMPLETION_STATUS_KHR: number } | null = null;

  /**
   * Start compiling a pass's program without waiting for it. A shader the
   * size of the flow one takes over a hundred milliseconds to compile in
   * software and tens on a phone; asked for on first use, that is a hitch
   * the first time a river scrolls into view. Started at load, it is done
   * before it is needed.
   */
  warm(name: string, fs: string): void {
    if (this.passes.has(name)) return;
    const gl = this.gl, prog = gl.createProgram()!, sh: WebGLShader[] = [];
    for (const [type, src] of [[gl.VERTEX_SHADER, TILE_VS], [gl.FRAGMENT_SHADER, fs]] as const) {
      const s = gl.createShader(type)!;
      gl.shaderSource(s, src);
      gl.compileShader(s);
      gl.attachShader(prog, s);
      sh.push(s);
    }
    gl.linkProgram(prog);
    this.passes.set(name, { prog, sh, ok: false, t0: performance.now(), u: new Map() });
  }

  /**
   * Whether a pass's program can draw now. Asking for the link status blocks
   * until the compile ends, so it is asked only once the browser says the
   * compile is done -- or, with no way to ask, a quarter second after it
   * started: the browser compiles in its GPU process meanwhile, so the ask
   * then finds the work finished rather than waiting on it.
   * Until then the pass is skipped: a frame without the water is invisible,
   * a frozen frame is not.
   */
  private ready(name: string, p: Pass): boolean {
    if (p.ok) return true;
    const gl = this.gl;
    if (this.parallel ? !gl.getProgramParameter(p.prog, this.parallel.COMPLETION_STATUS_KHR)
        : performance.now() - p.t0 < 250) return false;
    if (!gl.getProgramParameter(p.prog, gl.LINK_STATUS)) {
      const log = p.sh.map(s => gl.getShaderInfoLog(s)).join(' ');
      throw new Error(name + ' link: ' + gl.getProgramInfoLog(p.prog) + ' ' + log);
    }
    for (const s of p.sh) gl.deleteShader(s);
    p.sh = [];
    p.ok = true;
    return true;
  }

  /**
   * One full-screen pass of a fragment shader of the caller's: the shape every
   * GPU world layer takes (vegetation, flowing water). `textures` bind to
   * units 1.. in order; `uniforms` maps names to a number or a 2-vector. The
   * sprite batch is flushed first and its program restored after, so a pass
   * sits in the draw order exactly where it is called.
   */
  fullscreen(name: string, fs: string, textures: WebGLTexture[],
      uniforms: Record<string, number | [number, number]>, clip: number[] | null = null): void {
    const gl = this.gl;
    this.warm(name, fs);
    const p = this.passes.get(name)!;
    if (!this.ready(name, p)) return;
    this.flush();
    gl.useProgram(p.prog);
    const loc = (n: string) => {
      let l = p!.u.get(n);
      if (l === undefined) { l = gl.getUniformLocation(p!.prog, n); p!.u.set(n, l); }
      return l;
    };
    for (let i = 0; i < textures.length; i++) {
      gl.activeTexture(gl.TEXTURE1 + i);
      gl.bindTexture(gl.TEXTURE_2D, textures[i]);
      gl.uniform1i(loc('uTex' + i), 1 + i);
    }
    gl.activeTexture(gl.TEXTURE0);
    gl.uniform2f(loc('uRes'), this.vw, this.vh);
    for (const n in uniforms) {
      const v = uniforms[n], l = loc(n);
      if (typeof v === 'number') gl.uniform1f(l, v); else gl.uniform2f(l, v[0], v[1]);
    }
    if (clip === null) {
      gl.drawArrays(gl.TRIANGLES, 0, 3);
      this.stats.drawCalls++;
    } else {
      // Only inside these screen rects (x, y, w, h, top-left origin): a pass
      // that matters over a small part of the world -- the rivers -- costs
      // nothing elsewhere, rather than running its whole shader on every
      // pixel only to discard it.
      gl.enable(gl.SCISSOR_TEST);
      for (let i = 0; i + 3 < clip.length; i += 4) {
        const x = Math.max(0, Math.floor(clip[i])), y = Math.max(0, Math.floor(clip[i + 1]));
        const x1 = Math.min(this.vw, Math.ceil(clip[i] + clip[i + 2]));
        const y1 = Math.min(this.vh, Math.ceil(clip[i + 1] + clip[i + 3]));
        if (x1 <= x || y1 <= y) continue;
        gl.scissor(x, this.vh - y1, x1 - x, y1 - y);
        gl.drawArrays(gl.TRIANGLES, 0, 3);
        this.stats.drawCalls++;
      }
      gl.disable(gl.SCISSOR_TEST);
    }
    // Unbound again: a texture left on a unit the sprite program samples is
    // a feedback loop the moment sprites draw into it (an offscreen target
    // after its composite), and the slots no longer hold what they did.
    for (let i = 0; i < textures.length; i++) {
      gl.activeTexture(gl.TEXTURE1 + i);
      gl.bindTexture(gl.TEXTURE_2D, null);
    }
    gl.activeTexture(gl.TEXTURE0);
    gl.useProgram(this.spriteProg);
    this.slotTex.length = 0;
  }

  /** The GPU copy of a layer already drawn this frame (see layer()), for a
   *  pass that reads it -- or null if it has not been uploaded. */
  layerTexture(key: string): WebGLTexture | null {
    return this.textures.get(key)?.tex ?? null;
  }

  /** A raw-bytes texture (index or average), re-uploaded only when `rev`
   *  moves. NEAREST, no mipmaps, never premultiplied: these are numbers. */
  dataTex(key: string, data: Uint8Array, w: number, h: number,
      format: 'r' | 'rg' | 'rgba', rev: number): WebGLTexture {
    const gl = this.gl;
    let e = this.textures.get(key);
    if (!e) {
      e = { tex: gl.createTexture()!, rev: -1, w: 0, h: 0, lastUse: 0, srcId: 0 };
      this.textures.set(key, e);
    }
    e.lastUse = this.frameNo;
    if (e.rev !== rev) {
      const t0 = performance.now();
      this.bindForUpload(e.tex);
      gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false);
      gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1);
      const internal = format === 'r' ? gl.R8 : format === 'rg' ? gl.RG8 : gl.RGBA8;
      const fmt = format === 'r' ? gl.RED : format === 'rg' ? gl.RG : gl.RGBA;
      if (e.w === w && e.h === h) {
        gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, 0, w, h, fmt, gl.UNSIGNED_BYTE, data);
      } else {
        gl.texImage2D(gl.TEXTURE_2D, 0, internal, w, h, 0, fmt, gl.UNSIGNED_BYTE, data);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
        e.w = w; e.h = h;
      }
      gl.pixelStorei(gl.UNPACK_ALIGNMENT, 4);
      gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, true);
      e.rev = rev;
      this.stats.uploadMs += performance.now() - t0;
    }
    return e.tex;
  }

  /**
   * Draws a tile layer (see TILE_FS): `index` is two bytes per tile, row-major
   * over `cols` x `rows` (atlas column, 255 for nothing; atlas row), `atlas`
   * a canvas of `cell`-pixel cells, `avg` one RGBA texel per atlas cell.
   * `origin` is where world (0,0) lands on screen and `scale` the screen
   * pixels per tile. One draw call, whatever the zoom. With `ground` (a
   * layer's texture and its texels per tile) the pass draws that ground
   * under the plants too, in place of the ground's own quad.
   */
  tileLayer(key: string, index: Uint8Array, cols: number, rows: number, indexRev: number,
      atlas: HTMLCanvasElement, atlasRev: number, avg: Uint8Array, avgCols: number, avgRows: number,
      cell: number, ox: number, oy: number, scale: number, zoom: number, alpha = 1,
      ground: { tex: WebGLTexture; perTile: number } | null = null): void {
    this.flush();
    const idxTex = this.dataTex(key + ':idx', index, cols, rows, 'rg', indexRev);
    const avgTex = this.dataTex(key + ':avg', avg, avgCols, avgRows, 'rgba', atlasRev);
    const atl = this.texFor(key + ':atlas', atlas, atlasRev);
    const tex = [idxTex, atl.tex, avgTex];
    if (ground) tex.push(ground.tex);
    this.fullscreen('tile', TILE_FS, tex, {
      uOrigin: [ox, oy], uScale: scale, uZoom: zoom, uSize: [cols, rows], uCell: cell, uAlpha: alpha,
      uGround: ground ? ground.perTile : 0,
    });
  }

  /** Binds a texture to change it, on a unit no draw samples, and leaves
   *  that unit active: texImage2D and texParameteri act on the active unit.
   *  Every bind for drawing (push, fullscreen) picks its own unit. */
  private bindForUpload(tex: WebGLTexture): void {
    const gl = this.gl;
    gl.activeTexture(gl.TEXTURE0 + UPLOAD_UNIT);
    gl.bindTexture(gl.TEXTURE_2D, tex);
  }

  /** A solid premultiplied-colour quad (the dot LOD, fills). */
  quad(dx: number, dy: number, dw: number, dh: number,
      r: number, g: number, b: number, a: number): void {
    this.push(this.white, 0, 0, 1, 1, dx, dy, dw, dh, r * a, g * a, b * a, a, 0);
  }

  private push(tex: WebGLTexture,
      u0: number, v0: number, u1: number, v1: number,
      dx: number, dy: number, dw: number, dh: number,
      r: number, g: number, b: number, a: number, mode: number): void {
    if (this.vertCount >= MAX_QUADS) this.flush();
    let slot = this.slotTex.indexOf(tex);
    if (slot < 0) {
      if (this.slotTex.length === SLOTS) { this.flush(); this.slotTex.length = 0; }
      slot = this.slotTex.length;
      const gl = this.gl;
      gl.activeTexture(gl.TEXTURE0 + slot);
      gl.bindTexture(gl.TEXTURE_2D, tex);
      this.slotTex.push(tex);
    }
    mode += slot * 2;
    // Unrolled, no helper closure: this runs once per body per frame, and a
    // closure allocated per quad was measurable garbage at herd scale.
    const o = this.vertCount * 4 * FLOATS_PER_VERT;
    const v = this.verts;
    const x1 = dx + dw, y1 = dy + dh;
    v[o] = dx; v[o + 1] = dy; v[o + 2] = u0; v[o + 3] = v0;
    v[o + 4] = r; v[o + 5] = g; v[o + 6] = b; v[o + 7] = a; v[o + 8] = mode;
    v[o + 9] = x1; v[o + 10] = dy; v[o + 11] = u1; v[o + 12] = v0;
    v[o + 13] = r; v[o + 14] = g; v[o + 15] = b; v[o + 16] = a; v[o + 17] = mode;
    v[o + 18] = x1; v[o + 19] = y1; v[o + 20] = u1; v[o + 21] = v1;
    v[o + 22] = r; v[o + 23] = g; v[o + 24] = b; v[o + 25] = a; v[o + 26] = mode;
    v[o + 27] = dx; v[o + 28] = y1; v[o + 29] = u0; v[o + 30] = v1;
    v[o + 31] = r; v[o + 32] = g; v[o + 33] = b; v[o + 34] = a; v[o + 35] = mode;
    this.vertCount++;
    this.stats.quads++;
  }

  private flush(): void {
    if (this.vertCount === 0) return;
    const gl = this.gl;
    gl.bindBuffer(gl.ARRAY_BUFFER, this.vbo);
    gl.bufferSubData(gl.ARRAY_BUFFER, 0,
      this.verts.subarray(0, this.vertCount * 4 * FLOATS_PER_VERT));
    gl.drawElements(gl.TRIANGLES, this.vertCount * 6, gl.UNSIGNED_SHORT, 0);
    this.stats.drawCalls++;
    this.vertCount = 0;
  }
}
