// Wire types for the world server's JSON protocol (server/Protocol.java is
// the source of truth). Kept as plain shapes so a future binary encoding can
// swap in under the same interfaces.

export interface EntityState {
  id: number;
  kind: string; // "npc.*" | "item.food" | "item.crate" | "item.hazard" | "phero"
  x: number;
  y: number;
  z: number;
  dir: number;
  size: number; // body radius, tiles
  rgb: number;
  flags: number;
  attachedTo: number; // carrier id, or -1
  aux: number; // energy / strength / durability
  pheno: number; // procedural-creature atlas key, 0 if none (items, clouds)
}

export const F_DEAD = 1;
export const F_FLYING = 2;
export const F_GRABBED = 4;
export const F_CARRYING = 8;
export const F_MINDED = 16; // driven by an evolvable mind, not a hardcoded behaviour

// What the creature is visibly doing, packed into the spare high bits of `flags`
// (server: EntityState.ACTION_*). 0 = nothing worth drawing, which is most
// creatures most of the time — only notable acts get a badge.
export const ACTION_SHIFT = 5;
export const ACTION_MASK = 0xf << ACTION_SHIFT;
export const ACT_NONE = 0, ACT_ATTACK = 1, ACT_MATE = 2, ACT_FLEE = 3,
  ACT_GRAZE = 4, ACT_HUNT = 5, ACT_GRAB = 6, ACT_NEST = 7, ACT_AFFILIATE = 8;

/** The action code carried in an entity's flags. */
export function actionOf(flags: number): number {
  return (flags & ACTION_MASK) >> ACTION_SHIFT;
}

export interface HelloMsg {
  type: 'hello';
  seed: number;
  cols: number;
  rows: number;
  levels: number;
  tileSize: number;
  tick: number;
  paused: boolean;
  speed: number;
  layers: string[];
  chunkTiles: number; // baked ground is served as chunkTiles-square map chunks
  build: string; // server process id; changes on every restart/redeploy
}

export interface FullMsg { type: 'full'; tick: number; entities: EntityState[]; }
export interface DeltaMsg { type: 'delta'; tick: number; upsert: EntityState[]; gone: number[]; }
export interface StatusMsg { type: 'status'; tick: number; paused: boolean; speed: number; }
export interface AckMsg { type: 'ack'; cmd: string; tick: number; }
export interface ErrorMsg { type: 'error'; message: string; }

export type ServerMsg = HelloMsg | FullMsg | DeltaMsg | StatusMsg | AckMsg | ErrorMsg;

export type Command =
  | { cmd: 'spawnItem'; kind: string; x: number; y: number; z: number }
  | { cmd: 'pause' }
  | { cmd: 'resume' }
  | { cmd: 'speed'; value: number };
