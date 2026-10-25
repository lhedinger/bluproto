// The two facts every drawing module shares with the Java renderer: the art
// lattice and its hash. Imported by render.ts and the modules split from it,
// so none of them has to import back into render.ts.

// Art-pixels per tile. The Java engine owns this number as
// GroundTextures.ART; the two languages cannot share a constant, so this copy
// must move with it or every baked layer lands off-lattice.
export const ART = 12;

/** Ported verbatim from GroundTextures.hash01 (32-bit int arithmetic via
 *  Math.imul) — the concealment gap mask must agree with the Java renderer's
 *  bit for bit, or /sprites shows the pipelines disagreeing. */
export function hash01(x: number, y: number, s: number): number {
  let h = (Math.imul(x, 374761393) + Math.imul(y, 668265263) + Math.imul(s, 2246822519 | 0)) | 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  return ((h ^ (h >>> 16)) & 0x7fffffff) / 0x7fffffff;
}

/** The spent grey of a dead body drawn without its sprite: a corpse's dot, in
 *  both renderers and on the minimap. */
export const CORPSE_RGB = 0x5a5f66;
/** A living body whose sprite has not landed yet: the dot, with no marker. */
export const PENDING_RGB = 0x555a63;
/** The void behind the world, wherever no ground is drawn. */
export const VOID_RGB = 0x14161a;

/** `rgb` as a CSS hex colour. */
export const hex = (rgb: number): string => '#' + rgb.toString(16).padStart(6, '0');
/** `rgb` as the 0..1 channels the GL batcher takes. */
export const rgbF = (rgb: number): [number, number, number] =>
  [((rgb >> 16) & 255) / 255, ((rgb >> 8) & 255) / 255, (rgb & 255) / 255];
