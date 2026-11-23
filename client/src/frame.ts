// The shape of one GL frame: what every layer is given, and where it draws.
//
// renderGL used to be one function three hundred lines long that prepared
// data, drew eight kinds of thing and decided their order as it went. It is
// now a list of layers (render.ts, GL_LAYERS), each a function of a Frame that
// does one job, and the list IS the draw order. A layer never touches WebGL
// directly: GLRenderer owns the state, and a layer only asks it for quads and
// passes.

import type { GLRenderer } from './gl';
import type { ChunkSource, WorldMeta } from './render';
import type { EntityState } from './protocol';
import type { WorldState } from './state';

/** A projection of the world into one render target. */
export interface Proj {
  /** Target px per tile. */
  readonly scale: number;
  /** The viewer's zoom, screen px per tile: what levels of detail and the
   *  parallax read, whatever target the layer draws into. */
  readonly zoom: number;
  screenX(x: number): number;
  screenY(y: number): number;
  worldToScreen(x: number, y: number): { x: number; y: number };
  screenToWorld(px: number, py: number): { x: number; y: number };
}

/** A camera into a target of w x h px, centred on world (cx, cy). */
export class View implements Proj {
  /** Where world (0, 0) lands, on whole target pixels: every tiled layer is
   *  placed from it, so they move as one picture (see renderGL). */
  readonly ox: number;
  readonly oy: number;
  constructor(readonly cx: number, readonly cy: number, readonly scale: number,
      readonly zoom: number, readonly w: number, readonly h: number) {
    this.ox = Math.round(this.screenX(0));
    this.oy = Math.round(this.screenY(0));
  }
  screenX(x: number): number { return this.w / 2 + (x - this.cx) * this.scale; }
  screenY(y: number): number { return this.h / 2 + (y - this.cy) * this.scale; }
  worldToScreen(x: number, y: number): { x: number; y: number } {
    return { x: this.screenX(x), y: this.screenY(y) };
  }
  screenToWorld(px: number, py: number): { x: number; y: number } {
    return { x: this.cx + (px - this.w / 2) / this.scale, y: this.cy + (py - this.h / 2) / this.scale };
  }
}

/** The viewer's vector marks, gathered while the layers run and drawn on the
 *  2D overlay canvas after them. */
export interface Marks {
  doors: EntityState[];
  switches: Array<[EntityState, EntityState | undefined]>;
  links: Array<[number, number, number, number]>;
  glyphs: Array<[number, number, number, number]>;
  rings: Array<['grabbed' | 'carrying' | 'follow' | 'selected', number, number, number]>;
}

/** What the caller hands renderGL each frame. */
export interface Scene {
  state: WorldState;
  meta: WorldMeta | null;
  chunkTiles: number;
  tilePx: number;
  getChunk: ChunkSource;
  veg: Uint8Array | null;
  vegRev: number;
  cover: Uint8Array | null;
  water: Uint8Array | null;
  playTick: number;
  nowMs: number;
  level: number;
  selection: { id: number | null; tile: { x: number; y: number; z: number } | null };
}

/** Everything a layer reads: the scene, plus what renderGL worked out once
 *  for the whole frame. */
export interface Frame extends Scene {
  glr: GLRenderer;
  meta: WorldMeta;
  /** Where the tiled world draws. */
  view: View;
  /** The screen, for bodies and everything stamped at screen resolution. */
  screen: View;
  /** Below one screen pixel per art pixel: the far-zoom mirrors. */
  lowZoom: boolean;
  /** Whether the world is tiled yet (chunk size known): the tiled layers
   *  wait for it. */
  tiled: boolean;
  /** The body the camera follows, for its ring. */
  followId: number | null;
  /** The level's ground canvas, when its chunks have landed. */
  ground: HTMLCanvasElement | null;
  /** Bodies in painter's order (state.drawRank). */
  tracks: ReturnType<WorldState['ordered']>;
  marks: Marks;
}

/** One job in the frame. `sec` names the HUD section its time counts to. */
export interface Layer {
  name: string;
  sec: 'secLayers' | 'secEnts' | 'secTail';
  draw(f: Frame): void;
}
