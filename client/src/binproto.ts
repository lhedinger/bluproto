// Decoder for the binary entity stream (server/BinaryProtocol.java is the
// source of truth for the layout). The client connects with ?bin=1 and full /
// delta frames arrive as ArrayBuffers; control traffic (hello, status, ack)
// stays JSON text, and tooling that connects without the flag still gets the
// whole stream as JSON.
//
// The decoder rebuilds the SAME message shapes the JSON path produces
// (FullMsg / DeltaMsg with complete EntityState objects), so state, render and
// UI code never know the wire changed. It does that by keeping a client-side
// registry of birth records — an entity's static identity (kind, phenotype,
// colour, body) sent once when it first appears — and merging each 14-byte
// pose into it. Size is not on the wire at all after birth: growth is
// deterministic (a fixed rate toward the record's sizeMax), so it is computed
// from the frame tick, and frozen the moment the F_DEAD flag arrives.

import type { DeltaMsg, EntityState, FullMsg } from './protocol';
import { F_DEAD } from './protocol';

const POS_SCALE = 128, AUX_SCALE = 256; // mirrored from BinaryProtocol.java

interface Birth {
  kind: string;
  pheno: number;
  rgb: number;
  size: number;    // body at recTick (or frozen, once dead)
  sizeMax: number; // adult target; 0 = not growing
  rate: number;    // radius per tick
  recTick: number;
}

const kinds = new Map<number, string>();
const births = new Map<number, Birth>();

/** The deterministic body at `tick`, frozen at death (a corpse stops growing
 *  server-side the moment it dies; without the freeze it would keep inflating
 *  in the viewer). */
function sizeAt(b: Birth, tick: number, flags: number): number {
  if (b.sizeMax > 0) {
    // All in TILES (the wire unit). The sim quantises to whole pixels of
    // radius (1/32 tile); the continuous value here differs from it by less
    // than that, so no client-side rounding is worth the mismatch risk.
    const grown = Math.min(b.sizeMax, b.size + b.rate * Math.max(0, tick - b.recTick));
    if ((flags & F_DEAD) || grown >= b.sizeMax) {
      b.size = grown; // died, or fully grown: the body stops here
      b.sizeMax = 0;
    }
    return grown;
  }
  return b.size;
}

/** Decodes one binary frame into the JSON-shaped message, or null on an
 *  unknown frame type (a newer server; the caller should resync). */
export function decodeBin(data: ArrayBuffer): FullMsg | DeltaMsg | null {
  const v = new DataView(data);
  let o = 0;
  const u8 = () => v.getUint8(o++);
  const i8 = () => v.getInt8(o++);
  const u16 = () => { const x = v.getUint16(o, true); o += 2; return x; };
  const i16 = () => { const x = v.getInt16(o, true); o += 2; return x; };
  const i32 = () => { const x = v.getInt32(o, true); o += 4; return x; };
  const u32 = () => { const x = v.getUint32(o, true); o += 4; return x; };
  const f32 = () => { const x = v.getFloat32(o, true); o += 4; return x; };
  const f64 = () => { const x = v.getFloat64(o, true); o += 8; return x; };

  const type = u8();
  if (type !== 1 && type !== 2) return null;
  const tick = u32();
  const total = u32();

  const dictN = u16();
  const td = new TextDecoder();
  for (let i = 0; i < dictN; i++) {
    const id = u16();
    const len = u8();
    kinds.set(id, td.decode(new Uint8Array(data, o, len)));
    o += len;
  }

  if (type === 1) births.clear(); // a full re-baselines the level's population

  const birthN = u16();
  for (let i = 0; i < birthN; i++) {
    const id = i32();
    const kindId = u16();
    const pheno = f64();
    const rgb = u32();
    const size = f32();
    const sizeMax = f32();
    const rate = f32();
    births.set(id, {
      kind: kinds.get(kindId) ?? 'entity', pheno, rgb,
      size, sizeMax, rate, recTick: tick,
    });
  }

  const poseN = u16();
  const entities: EntityState[] = new Array(poseN);
  for (let i = 0; i < poseN; i++) {
    const id = i32();
    const x = u16() / POS_SCALE;
    const y = u16() / POS_SCALE;
    const dir = (u8() / 256) * Math.PI * 2;
    const z = i8();
    const flags = u16();
    const aux = i16() / AUX_SCALE;
    const b = births.get(id);
    entities[i] = {
      id, x, y, z, dir, flags, aux,
      kind: b ? b.kind : 'entity',
      pheno: b ? b.pheno : 0,
      rgb: b ? b.rgb : 0xffffff,
      size: b ? sizeAt(b, tick, flags) : 1,
      attachedTo: -1, // filled from the sparse attachment section below
    };
  }

  const goneN = u16();
  const gone: number[] = new Array(goneN);
  for (let i = 0; i < goneN; i++) {
    gone[i] = i32();
    births.delete(gone[i]);
  }

  const attachN = u16();
  if (attachN > 0) {
    const byId = new Map<number, EntityState>();
    for (const e of entities) byId.set(e.id, e);
    for (let i = 0; i < attachN; i++) {
      const id = i32();
      const carrier = i32();
      const e = byId.get(id);
      if (e) e.attachedTo = carrier;
    }
  }

  return type === 1
    ? { type: 'full', tick, entities, total }
    : { type: 'delta', tick, upsert: entities, gone, total };
}
