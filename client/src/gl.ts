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
const FLOATS_PER_VERT = 8; // x, y, u, v, r, g, b, a (premultiplied)

const VS = `#version 300 es
in vec2 aPos; in vec2 aUV; in vec4 aCol;
uniform vec2 uRes;
out vec2 vUV; out vec4 vCol;
void main() {
  gl_Position = vec4(aPos.x * 2.0 / uRes.x - 1.0, 1.0 - aPos.y * 2.0 / uRes.y, 0.0, 1.0);
  vUV = aUV;
  vCol = aCol;
}`;

const FS = `#version 300 es
precision mediump float;
in vec2 vUV; in vec4 vCol;
uniform sampler2D uTex;
out vec4 outColor;
void main() { outColor = texture(uTex, vUV) * vCol; }`;

interface TexEntry { tex: WebGLTexture; rev: number; w: number; h: number; }

export class GLRenderer {
  private gl: WebGL2RenderingContext;
  private verts = new Float32Array(MAX_QUADS * 4 * FLOATS_PER_VERT);
  private vertCount = 0; // quads currently buffered
  private vbo: WebGLBuffer;
  private uRes: WebGLUniformLocation;
  private textures = new Map<string, TexEntry>();
  private white: WebGLTexture;
  private boundKey: string | null = null;
  private stats = { drawCalls: 0, quads: 0 };

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
    gl.linkProgram(prog);
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) {
      throw new Error('link: ' + gl.getProgramInfoLog(prog));
    }
    gl.useProgram(prog);
    this.uRes = gl.getUniformLocation(prog, 'uRes')!;

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
  begin(w: number, h: number, r: number, g2: number, b: number): void {
    const gl = this.gl;
    gl.viewport(0, 0, w, h);
    gl.uniform2f(this.uRes, w, h);
    gl.clearColor(r, g2, b, 1);
    gl.clear(gl.COLOR_BUFFER_BIT);
    this.vertCount = 0;
    this.boundKey = null;
    this.stats = { drawCalls: 0, quads: 0 };
  }

  /** Flushes what remains and reports the frame's batching stats. */
  end(): { drawCalls: number; quads: number } {
    this.flush();
    return this.stats;
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
      e = { tex: gl.createTexture()!, rev: -1, w: 0, h: 0 };
      this.textures.set(key, e);
    }
    if (e.rev !== rev) {
      this.flush(); // never mutate a texture the buffered quads still sample
      gl.bindTexture(gl.TEXTURE_2D, e.tex);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, src);
      gl.generateMipmap(gl.TEXTURE_2D);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      e.rev = rev;
      e.w = src.width;
      e.h = src.height;
      this.boundKey = key; // texImage2D left it bound
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

  /** A textured quad: source rect (pixels of `src`) to destination rect
   *  (canvas pixels), modulated by alpha. Quads sharing a texture batch. */
  sprite(key: string, src: TexImageSource & { width: number; height: number }, rev: number,
      sx: number, sy: number, sw: number, sh: number,
      dx: number, dy: number, dw: number, dh: number, alpha = 1): void {
    const e = this.texFor(key, src, rev);
    this.push(key, e.tex,
      sx / e.w, sy / e.h, (sx + sw) / e.w, (sy + sh) / e.h,
      dx, dy, dw, dh, alpha, alpha, alpha, alpha);
  }

  /** A solid premultiplied-colour quad (the dot LOD, fills). */
  quad(dx: number, dy: number, dw: number, dh: number,
      r: number, g: number, b: number, a: number): void {
    this.push(' white', this.white, 0, 0, 1, 1, dx, dy, dw, dh, r * a, g * a, b * a, a);
  }

  private push(key: string, tex: WebGLTexture,
      u0: number, v0: number, u1: number, v1: number,
      dx: number, dy: number, dw: number, dh: number,
      r: number, g: number, b: number, a: number): void {
    if (key !== this.boundKey || this.vertCount >= MAX_QUADS) {
      this.flush();
      this.gl.bindTexture(this.gl.TEXTURE_2D, tex);
      this.boundKey = key;
    }
    let o = this.vertCount * 4 * FLOATS_PER_VERT;
    const v = this.verts;
    const put = (x: number, y: number, u: number, vv: number) => {
      v[o] = x; v[o + 1] = y; v[o + 2] = u; v[o + 3] = vv;
      v[o + 4] = r; v[o + 5] = g; v[o + 6] = b; v[o + 7] = a;
      o += FLOATS_PER_VERT;
    };
    put(dx, dy, u0, v0);
    put(dx + dw, dy, u1, v0);
    put(dx + dw, dy + dh, u1, v1);
    put(dx, dy + dh, u0, v1);
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
