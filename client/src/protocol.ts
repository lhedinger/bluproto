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
