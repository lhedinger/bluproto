# Rendering optimizations: the inventory

Parked work, kept here until it is picked up. Each entry says what it is, why
it is on the list, and how to tell it worked. Networking (how creature motion
reaches the screen) is a separate track and is not listed here.

## What the cost is

Measured on 2026-11-18 against a local seed-7 world, headless Chromium with
SwiftShader (software GL), 900×620. SwiftShader inflates every absolute
number, so read these as a ranking, not as phone timings.

- **Our own JavaScript is not the cost.** `npm run bench` shows about 3 ms of
  JS per frame (layers 0.7–1.8, entities 0.8, tail 0.4–0.7), 14–80 draw calls
  and under 2,000 quads, against frames of 60–86 ms.
- **The cost is pixels.** Every layer is a full-screen alpha blend, a CPU
  canvas repainted and re-uploaded, or a second page canvas the browser
  composites. Turning layers off one at a time at close zoom:

  | layers on | frame | JS |
  |---|---|---|
  | all | 91 ms | 7.1 ms |
  | without vegetation | 60 ms | 3.2 ms |
  | without water glints | 65 ms | 4.9 ms |
  | without the overlay canvas | 70 ms | 4.5 ms |
  | without canopy | 72 ms | 4.7 ms |
  | without vegetation, canopy, parallax, below-floor bodies and flow | 28 ms | 0.8 ms |

  One run each, so differences under about 10 ms are noise. At fit zoom the
  frame pacer caps redraws, so fit-zoom numbers measure the cap.

The probe that produced the table: a Playwright script that loads
`/?gl=1&<flag>=0`, nudges the camera every frame so the pacer always draws,
and reads `window.__bluPerf` (`frameMs`, `drawMs`, `cbMs`, `drawCalls`).

## The inventory

Ordered by expected gain for the effort.

### 1. Vegetation as a GPU tile layer -- done
- **Shipped:** `GLRenderer.tileLayer` draws the level's vegetation in one
  draw call: a two-byte-per-tile index (atlas column, atlas row) and a sprite
  atlas painted a plant kind per frame; far zoom fades to each cell's average
  colour. The chunk canvases remain for the Canvas2D path only.
- **Measured** (same profile, 3 runs each, SwiftShader):
  - first 8 s after load: JS per frame median 2.8 -> 1.2 ms, the layer
    section's p95 5.7 -> 1.0 ms;
  - near zoom, steady: JS per frame 3.4 -> 2.6 ms (still), 2.1 -> 1.8 ms
    (panning); draw calls 38 -> 29 (still);
  - frame intervals unchanged (p50 50 ms near): SwiftShader rasterizes on the
    CPU, so a full-screen shader pass costs it what the blended quads did. The
    GPU side of this change cannot be measured here; a phone can.
- A first cut built the whole atlas in one go: a 110-190 ms long task as the
  world first appeared. Built a row a frame, it is gone.

#### What it replaced
- **Now:** five grass states per tile, painted on the CPU into per-chunk
  canvases (`render.ts`, at most `VEG_CHUNKS_PER_FRAME = 2` repaints a frame),
  each its own texture and quad.
- **Instead:** the tilemap-shader technique. One byte per tile in an `R8`
  texture holds the state; one quad covers the view; the fragment shader reads
  the tile's state and samples the matching cell of a small sprite atlas. A
  vegetation delta becomes a `texSubImage2D` of a few bytes. Art stays a pure
  function of position (ART-STYLE), so the dither and motifs carry over.
- **Done when:** the vegetation row of the table above costs a few ms, and a
  side-by-side screenshot at 48 px/tile matches today's.

### 2. The overlay canvas into the WebGL pass
- **Now:** rings, doors, switches, links and glyphs are drawn with Canvas2D on
  `#fx`, a second full-screen canvas the browser composites every frame
  (`render.ts`, the `ov*` arrays).
- **Instead:** quads in the existing sprite batcher, from a small glyph atlas.
- **Done when:** `#fx` is gone from the GL path and the overlay row costs
  nothing measurable.

### 3a. Flowing water on the GPU -- done
- **Shipped:** `FLOW_FS` (flow.ts) draws every river feature in one
  full-screen pass: the drift of the bake's own water pixels, dashes, rapids,
  fall lips and sheets, cascades, plunge pools, springs and rock faces. It
  reads the full-resolution ground texture and a four-byte record per tile
  built once per level, and runs on the playback clock, so the marks move
  every frame instead of every two ticks.
- **Measured:** texture uploads in a still near view 6.0 ms/s -> 0; the CPU
  per-pixel repaint of every visible river chunk is gone from the WebGL path.
  JS per frame cannot settle it on software GL: two identical runs differ by
  0.5 ms, and a heavy shader there blocks the next frame's GL calls, which
  shows up as JS time a real GPU would not charge.
- Side by side with the CPU painter at 48 px/tile, a spring, rapids and a
  fall read the same; rings are drawn as distance bands, not stamps.

### 3c. The concealment veil on the GPU -- done
- **Shipped:** `VEIL_FS` (render.ts) makes veilTile's decision per pixel
  from the ground texture already on the GPU and one byte of cover per tile:
  colour-keyed covers keep their key pixels, canopy a hashed 2x2 dither of
  its ground; far zoom shows each covered tile's ground at its kind's share.
  The level-sized veil canvas (29 MB at 288 x 176), its patch uploads and its
  far-zoom mirror are gone from the WebGL path. Duct lids stay sprites.
- **Measured:** the canopy's upload cost (turning it off saved 3.5 ms/s still
  and 8.5 ms/s panning) is now within noise (0 and 1.2 ms/s). A creature under
  canopy is veiled with it on and crisp with it off, as before.

### 3b. Water glints on the GPU -- done
- **Shipped:** `GLINT_FS` (fx.ts): each art-pixel checks the one glint its
  4-px cell can hold, hashed exactly as `glintsFor` hashes it, and whether it
  is lit at this tick. The water mask is the only upload, once per level. The
  per-frame list walk and Canvas2D fills on the overlay are gone from the
  WebGL path.
- A first cut checked the 3x3 cells around every pixel; a glint never leaves
  its own cell, so one is enough, and a land pixel stops at one fetch.
- **Measured:** glints on against off is within noise on software GL (0.5 ms
  JS still, none panning).

### 4. Fewer full-screen passes
- **Now:** ground, vegetation, canopy veil, flow and the below-floor parallax
  are separate blended quads over the same pixels.
- **Instead:** one pass that samples several textures and composes them in the
  shader, so each screen pixel is written once.
- **Done when:** the all-layers frame approaches the all-off frame.

### 5. One texture for every creature sprite, drawn instanced
- **Now:** one texture per creature shape, so the batcher breaks its batch at
  every shape change (a draw call per run of one shape).
- **Instead:** a `TEXTURE_2D_ARRAY` (or one shared atlas) with the shape as a
  layer index, and instanced quads carrying position, frame, tint and layer.
  The whole herd is one draw call. Check `MAX_ARRAY_TEXTURE_LAYERS`.
- **Done when:** draw calls stop growing with the number of shapes on screen.

### 6. Revisit the frame pacer
- **Now:** frames are skipped while little moves, down to a 10 fps floor
  (`PACE_FLOOR_MS = 100`, `main.ts`).
- **Why later:** once creature motion is smooth, a 10 fps floor reads as its
  own stutter; once the layers above are cheap, more redraws are affordable.
- **Done when:** a slow pan and a resting herd both look continuous at fit zoom.

### 7. Per-frame allocations
- `renderGL` builds five overlay arrays, a `Set` for lids, object spreads and
  string texture keys every frame; `cam.worldToScreen` returns a new object
  per call; `GLRenderer.end()` runs a regex over every texture key twice a
  frame (`gl.ts`, `evictPool`). Worth a pass guided by the long-task counter,
  not a rewrite.

### 8. Pixel-snapped camera
- Round the camera to whole screen pixels at the current zoom so pans do not
  shimmer, the usual pixel-art rule.

## How to measure

- `npm run bench` (client/bench/bench.mjs) for the static zoom ladder.
- The flag bisect above for per-layer cost.
- A real phone for absolute numbers: SwiftShader only ranks.
- Missing and worth adding: a scripted pan-and-zoom run reporting p99 and
  worst frame (every recorded client failure so far happened in motion), and
  GPU timer queries (`EXT_disjoint_timer_query_webgl2`) where available.

## Sources

- [WebGL2 Fundamentals: 2D tilemaps](https://webgl2fundamentals.org/webgl/lessons/webgl-qna-webgl-2d-tilemaps.html)
- [Phaser TilemapGPULayer](https://app.unpkg.com/phaser@4.2.1/files/src/tilemaps/TilemapGPULayer.js)
- [gl-tiled](https://englercj.github.io/2018/01/07/gl-tiled)
- [Godot tile map shader](https://github.com/AndreVallestero/godot-tile-map-shader)
- [WebGL2 Fundamentals: textured sprites with instanced drawing](https://webgl2fundamentals.org/webgl/lessons/webgl-qna-drawing-textured-sprites-with-instanced-drawing.html)
- [Reducing draw calls](https://abratabia.com/mobile-browser-performance/reducing-draw-calls.php)
