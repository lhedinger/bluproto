---
name: run-scenarios
description: Run the Prototype_World behavior scenario tests and produce a short MP4 of each. Use when asked to run the scenario tests, show ecosystem behaviors as videos, or verify/film specific interactions (hunting, fleeing, grazing, mating, scavenging).
---

# Run the scenario tests (with videos)

`ScenarioRunner` stages five specific ecosystem behaviors in small controlled
arenas, asserts a measurable outcome for each (so it's a pass/fail test), and
writes PNG frames. `scripts/run-scenarios.sh` then encodes one **MP4 per
scenario**. It exits non-zero if any scenario fails its assertion.

## Steps

1. Run everything (build + scenarios + encode):

   ```bash
   ./scripts/run-scenarios.sh capture/scenarios 14   # [outBaseDir] [fps]
   ```

   Needs ffmpeg for the MP4s — uses system ffmpeg or the static binary from
   `imageio-ffmpeg` (`pip install imageio-ffmpeg` if missing; see the
   `capture-sim` skill).

2. Each scenario's video is `capture/scenarios/<name>/sim.mp4`. Show them with
   `SendUserFile` (`display: "render"`). Prefer MP4 over GIF for mobile.

## The scenarios

| name       | asserts                                            |
|------------|----------------------------------------------------|
| `hunt`     | a hungry Bullsquid catches and kills a Houndeye    |
| `panic`    | a threatened herd flees and fear scent spreads     |
| `graze`    | grazers visibly deplete plant cover (no regrowth)  |
| `courtship`| two fed adults mate and one becomes pregnant       |
| `scavenge` | a predator reaches a carcass and feeds (energy up) |

Each films until its outcome plus a short tail, so videos are only a few
seconds. Reading the frames: **red dot** = predator (Bullsquid), **pale dot** =
prey (Houndeye), green tint = flora, blue pools = water, hatched border = walls,
`!` = alarm call.

## Adding a scenario

Subclass `ScenarioRunner.Scenario` (name, arena size, `maxTicks`, `setup`,
`success`) and add it to the list in `main`. `setup` builds the situation with
`Arena` helpers and `world.spawnEntity(...)`; `success` returns true once the
behavior is observed (query the world via the public engine APIs). Keep arenas
small and stage the actors close together so the outcome is reliable and the
video stays short.
