# Modernization — sim in a backend, world in the browser

**Goal:** the simulation runs headless in a **backend service**, and the living
world is watched — and touched — **in real time from a website** that works
well on a phone. The Swing window stops being the only pair of eyes on the
world.

Decisions taken up front: the **Java engine stays** (it is the product; the 60
scenario tests keep guarding it), the web client gets **full interaction**
(spawn items, poke the world, drive the sim — not just spectate), the site
ships to a **small public URL**, and the desktop app **survives until web
parity, then retires**.

Interaction and determinism are reconciled by one rule: the browser never
mutates the world directly. Every action becomes a **command**, queued and
applied at a tick boundary. `seed + ordered command log ⇒ the exact same
world`, so reproducibility — the project's defining property — survives human
meddling, and replay falls out for free.

## Where we start from

An honest inventory of what helps and what hurts:

- **Helps:** the sim core is already headless-capable — `World.think()` runs
  without a window (the whole scenario suite and `SnapshotRenderer` prove it).
  JDK is already 21. Assets are tiny (1.3 MB). The tick cadence is a settled
  convention (33 ticks/s). Determinism is seed-driven and test-pinned.
- **Hurts:** rendering is *interleaved with the domain* — 45 of 64 files touch
  `java.awt`, because every entity draws itself (`draw(Graphics, View)`).
  There is no build tool (plain `javac`), no CI, and engine globals
  (`Utils.seed`, `PrototypeWorld.stopwatch`, `ResourceManager.tileSize`) assume
  one world per process.

The single biggest planning decision follows from the "hurts" list: we do
**not** try to purge AWT from the engine up front. Headless AWT is fine on a
server, and the Java renderer stays useful as the *visual source of truth* for
baking. Decoupling happens at the **data boundary** — snapshots out, commands
in — not by rewriting 45 files.

## Architecture target

```
┌────────────────────────── backend (Java 21) ──────────────────────────┐
│  engine (headless)     SimulationRunner         web layer (Javalin)   │
│  World.think() ◄──     fixed 33 t/s loop        WS out: state 10 Hz   │
│  deterministic         pause/step/speed         WS in:  commands      │
│       ▲                snapshot() per tick ──►  REST: world info      │
│       └── command queue (applied at tick boundary, logged) ◄──┘       │
│                                                 static: baked layers, │
│                                                 built frontend        │
└───────────────────────────────────────────────────────────────────────┘
                                   │  JSON (deflate), later binary
                                   ▼
┌────────────────────────── browser (TS + Vite) ────────────────────────┐
│  Canvas/WebGL viewport · interpolates 10 Hz state to 60 fps ·         │
│  touch pan/pinch-zoom · tap-to-follow / tap-to-act · responsive ·     │
│  pixelated scale · sim controls (pause/speed/reseed)                  │
└───────────────────────────────────────────────────────────────────────┘
```

**The two seams.** Everything hangs on one boundary with two directions:

- **Snapshots out** — an immutable `WorldSnapshot` DTO per tick (entities:
  id, kind, x, y, z, heading, size, color, flags; plus events). The broadcaster
  fans it out off-thread; slow clients drop deltas and re-sync with a full
  snapshot, so viewers can never back-pressure the simulation.
- **Commands in** — `SpawnItem(kind, x, y)`, `Pause`, `SetSpeed`, `Reseed`,
  later `SpawnCreature(genome…)`. Commands are validated, stamped with the
  tick they will apply at, appended to the **command log**, and consumed by the
  sim thread at the next tick boundary. Replaying `seed + log` reproduces the
  world byte-for-byte — pinned by a scenario test like every other invariant.

**Render split.** Three kinds of visuals, three transports:

1. **Static layers** (tile map, pixel ground, shrubs): baked server-side by the
   *existing* Java renderer into PNGs, fetched once per world. Zero porting,
   pixel-perfect parity for the most style-defining art.
2. **Dynamic entities** (creatures, items, pheromone clouds): streamed as
   compact state; the client draws them. v1 draws readable primitives (oriented
   genome-coloured bodies, crate/food/hazard glyphs); parity comes in Phase 4
   via **server-baked sprite atlases** — the server renders each phenotype's
   frames to a small atlas on demand, so the Java `ProcCreature` code remains
   the single visual truth and nothing is hand-ported twice.
3. **Cosmetic dynamics** (tall-grass bending, wind): client-side effects,
   recreated from the same deterministic hash math — or omitted at first; they
   are gameplay-inert by design.

**Why these tools.**

| Choice | Pick | Why / rejected alternatives |
|---|---|---|
| Backend language | Java 21 (keep) | The engine *is* the product; a rewrite is not modernization. |
| Build | Gradle, multi-module (`engine`, `desktop`, `server`) | Dependency mgmt + CI + packaging; Maven fine too, Gradle tersest. |
| Web framework | Javalin (embedded Jetty) | First-class WebSockets, ~no ceremony at 10K LOC. Spring Boot rejected as oversized. |
| Wire format | JSON + permessage-deflate first; binary (flat arrays) only if measured to matter | Debuggability beats bytes until ~1k entities. |
| Frontend | Vite + TypeScript + Canvas2D (PixiJS/WebGL only if profiling demands) | A few hundred sprites at 60 fps is comfortably Canvas2D territory. |
| Sim-in-browser (TeaVM/JS port) | **Rejected** | Backend-authoritative is the requirement; one deterministic truth. |
| Frame streaming (MJPEG/WebRTC) | **Rejected** as destination | Bandwidth-hostile on mobile, no client camera. (Fine as a day-one debug trick.) |

**Protocol sketch.**

- `hello` → `{worldId, cols, rows, levels, tileSize, tick, staticLayerUrls[]}`
- `full`  → `{tick, entities:[{id, kind, x, y, z, dir, size, color, flags}]}`
- `delta` → `{tick, upsert:[…], gone:[ids]}` at 10 Hz (positions quantized);
  client interpolates toward each update, so motion is smooth at 60 fps while
  the wire stays light (~300 entities ≈ a few KB/s deflated — phone-friendly).
- `cmd`   → client-to-server `{type, args…}`; server acks with the tick it was
  scheduled for (or a rejection), and the effect arrives in a later `delta`
  like any other world event — there is no client-side prediction to reconcile.

## Phases

Each phase lands green (all scenarios pass), committed, and shippable on its own.

| Phase | Delivers | Definition of done |
|---|---|---|
| **0 · Foundations** | Gradle multi-module; GitHub Actions running `SimTests` on every push; desktop app still launches | `gradle :engine:test` runs the suite in CI; no behaviour change |
| **1 · The seam** | `SimulationRunner` (fixed 33 t/s, pause/step/speed, wall-clock-free); `WorldSnapshot` DTO; **command queue + log** applied at tick boundaries; engine no longer reaches into `PrototypeWorld` statics | Console main runs a world in real time; scenario tests pin *both* directions: snapshot streams are deterministic, and `seed + command log` replays byte-identical |
| **2 · Backend service** | Javalin app: create world from seed, WS state stream **and command intake**, baked static-layer endpoints, health; Dockerfile | `docker run` → watch deltas with `websocat`, inject a `SpawnItem` command, see it appear in the stream |
| **3 · Web client v1** | Vite/TS canvas viewer: static layers under interpolated entities; mouse + touch pan/pinch; tap-to-follow; **action UI: spawn food/crate/hazard at a tapped tile, pause/resume/speed**; responsive, `image-rendering: pixelated` | **Watch — and feed — a live world from a phone** over the LAN |
| **4 · Visual parity + inspect** | Server-baked creature sprite atlases; pheromone haze, corpses, items, carry links; debug-overlay toggle; tap an entity → info panel (genome, energy, state); minimap; richer actions (spawn creature from a genome preset) | Web view reads like the Swing view; desktop app enters retirement watch |
| **5 · Public URL + ops** | Deploy (Fly.io/Render/VPS) behind TLS; command channel gated by a shared token (view open, meddling authenticated); replay endpoint (serve a stored `seed + command log`); metrics (tick ms, entity count, viewers) | A URL you can open from anywhere; a long-running world to check on over morning coffee |

**Sequencing rationale.** 0→1 is the safety net (build + CI) followed by the
one seam the whole plan hangs on — and commands land *with* snapshots in
Phase 1 because they are two directions of the same boundary, cheapest to
design together. 2 and 3 are thin once 1 exists and can proceed in parallel.
4 is the aesthetic and inspection work, deferred until a phone shows a moving
world, because primitives suffice to validate the pipeline. 5 is production
concerns, last on purpose.

## What stays, what goes

- **Scenario suite stays the regression backbone** — it runs on the engine
  module, windowless, in CI, unchanged. New tests pin the seam: same seed →
  identical snapshot stream; same seed + command log → identical world.
- **Swing desktop app survives** through Phase 4 as the local dev harness and
  parity reference, then retires (`:desktop` module deleted or left dormant).
- **Recording workflow upgrades:** today's `-Dsimtest.record` PNG pipeline
  keeps working; Phase 5's replay makes "recordings" a server feature — a
  stored seed + command log is smaller than any video and perfectly faithful.

## Risks & mitigations

- **Rendering parity is the big unknown-cost item** → contained by baking
  (static layers now, sprite atlases in Phase 4) so Java stays the sole visual
  truth; the client only composites.
- **Public URL + interaction = strangers can poke the world** → the command
  channel is token-gated from the day it goes public; viewing stays open.
  Commands are rate-limited and validated server-side (bounds, kinds, budgets).
- **Engine globals block multi-world** → explicitly deferred; single world per
  process is fine for every phase here. De-globalization becomes its own
  later track if multiple concurrent worlds are ever wanted.
- **Threading (sim vs broadcast vs command intake)** → immutable snapshots
  handed off once per tick; commands cross threads through one queue consumed
  only at tick boundaries; broadcaster owns its own pacing.
- **Mobile bandwidth/battery** → 10 Hz deltas + deflate + quantized coords;
  the client renders nothing when the tab is hidden.

## Open questions (deliberately parked)

- **Binary protocol** — only if a measured world (1k+ entities) makes JSON the
  bottleneck; the snapshot DTO is designed so the encoding can swap under it.
- **Spawn-creature UX** — presets vs a genome editor; decide during Phase 4
  when the inspector panel exists to display genomes at all.
- **Multiple worlds / rooms** — requires the de-globalization track; revisit
  once the single public world has lived for a while.
