---
name: capture-sim
description: Render a screenshot or short video of the Prototype_World simulation. Use when asked to take a screenshot, capture, film, record, or show a picture/video/gif of the sim, the world, or the ecosystem in action. Produces an MP4 video (plays inline on mobile), plus GIF and PNG.
---

# Capture a screenshot or short video of the sim

`SimCapture` renders the sim **off-screen** — it drives the real `View` render
pipeline into a `BufferedImage` — so no display server is needed. It writes,
per run: numbered PNG frames (`frames/`), an animated `sim.gif`, and a
`screenshot.png`. A short script then encodes the frames to **`sim.mp4`**.

**Prefer the MP4 for sharing.** Animated GIFs do not play smoothly on mobile
clients (they often show only the first frame), and the MP4 is also far smaller
(~15× here). Send `sim.mp4`; keep the GIF/PNG as fallbacks.

## Steps

1. Compile:

   ```bash
   ./build.sh
   ```

2. **Screenshot only** (single frame → `capture/screenshot.png`):

   ```bash
   java -Djava.awt.headless=true -cp bin net.hedinger.prototype.main.SimCapture capture 1
   ```

3. **Video.** Capture frames, then encode MP4. Args:
   `[outDir] [frames] [ticksPerFrame] [warmup] [cols] [rows] [lvls]`

   ```bash
   java -Djava.awt.headless=true -cp bin net.hedinger.prototype.main.SimCapture capture 45 5
   ./scripts/encode-mp4.sh capture 18          # [outDir] [fps]
   ```

   `encode-mp4.sh` uses `ffmpeg` if on PATH, otherwise the static binary from
   the `imageio-ffmpeg` pip package. If neither is present, install it once:

   ```bash
   pip install imageio-ffmpeg
   ```

4. Show the result with `SendUserFile` (`display: "render"`), pointing at
   `capture/sim.mp4` (preferred) — or `capture/screenshot.png` for a still.

## Notes

- **Lighting:** `warmup` ticks run before filming. The default (3000) lands on
  midday so the scene is bright and plants have grown in. For a night scene
  (darkness overlay, nocturnal Bullsquids active) use a warmup like `3600`.
  The cycle is `World.DAY_LENGTH` (2400) ticks; noon is at multiples of 2400 + 600.
- **What you'll see:** hatched tiles = walls, bright green bars = doors, green
  tint = flora, blue pools = water, red dots = predators/hostiles (with `!`
  alarm text), brown discs = nests, expanding rings = sound/LOS.
- **Cost:** a 45-frame capture on the default grid takes ~1–2 min and the MP4
  is a few hundred KB. Fewer frames or a smaller grid (e.g.
  `SimCapture capture 30 5 3000 20 20 2`) is faster.
- The MP4 is H.264 / yuv420p / faststart (mobile- and chat-friendly). Canvas is
  900×700 (both even, required by yuv420p); change `W`/`H` in `SimCapture.java`
  to resize and keep them even.
