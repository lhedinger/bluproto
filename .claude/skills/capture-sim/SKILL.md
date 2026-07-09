---
name: capture-sim
description: Render a screenshot or a short animated video (GIF) of the Prototype_World simulation. Use when asked to take a screenshot, capture, film, or show a picture/video of the sim, the world, or the ecosystem in action.
---

# Capture a screenshot or short video of the sim

`SimCapture` renders the sim **off-screen** — it drives the real `View` render
pipeline into a `BufferedImage` and writes files with the built-in ImageIO. No
display server, Xvfb, ffmpeg, or ImageMagick required (none are installed
here). Output is PNG for stills and animated **GIF** for video (GIF is the
video format because ffmpeg isn't available).

## Steps

1. Compile:

   ```bash
   ./build.sh
   ```

2. Capture. Args (all optional):
   `[outDir] [frames] [ticksPerFrame] [warmup] [cols] [rows] [lvls]`

   **Screenshot** (single frame → `screenshot.png`):

   ```bash
   java -Djava.awt.headless=true -cp bin net.hedinger.prototype.main.SimCapture capture 1
   ```

   **Short video** (frames > 1 → `sim.gif` + a `screenshot.png` of frame 0):

   ```bash
   java -Djava.awt.headless=true -cp bin net.hedinger.prototype.main.SimCapture capture 60 6
   ```

3. Show the result to the user with `SendUserFile` (`display: "render"`),
   pointing at `capture/screenshot.png` or `capture/sim.gif`.

## Notes

- **Lighting:** `warmup` ticks run before filming. The default (3000) lands on
  midday so the scene is bright and plants have grown in. For a night scene
  (darkness overlay, nocturnal Bullsquids active) use a warmup like `3600`.
  The cycle is `World.DAY_LENGTH` (2400) ticks; noon is at multiples of 2400 + 600.
- **What you'll see:** hatched tiles = walls, bright green bars = doors, green
  tint = flora, blue pools = water, red dots = predators/hostiles (with `!`
  alarm text), brown discs = nests, expanding rings = sound/LOS.
- **Cost:** a 60-frame GIF at the default grid takes ~1–2 min and a few MB.
  Fewer frames or a smaller grid (e.g. `capture 40 6 3000 20 20 2`) is faster.
- Canvas is 900×700. Change `W`/`H` in `SimCapture.java` to resize.
