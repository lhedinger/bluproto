---
name: run-headless-sim
description: Run the Prototype_World ecosystem simulation without a window and print population/flora/genome stats over time. Use when asked to run the sim headless, check ecosystem balance, watch predator/prey dynamics, or verify the simulation loop tick-by-tick from the terminal.
---

# Run the headless simulation

`HeadlessSim` advances the world without a GUI and prints a stats line every
2000 ticks: daylight phase, flora coverage, water tile count, live population
per species, cumulative births/deaths, and the average genome speed per
species (watch it drift to see selection at work).

## Steps

1. Compile (skip if `bin/` is already current):

   ```bash
   ./build.sh
   ```

2. Run it. Args are all optional: `[ticks] [cols] [rows] [lvls]`
   (defaults: 20000 ticks, 30×30 grid, 3 levels).

   ```bash
   java -Djava.awt.headless=true -cp bin net.hedinger.prototype.main.HeadlessSim 20000
   ```

## Notes

- Cost scales with live population, not just tick count — each metabolic
  animal runs radial neighbour searches every tick. A run that booms to
  many hundreds of animals gets slow; keep `ticks` ≤ ~20000 on the default
  grid, or shrink the grid (e.g. `20 20 2`) for quick checks.
- A healthy run shows both Houndeye (grazer) and Bullsquid (predator)
  persisting with oscillating counts and flora moving inversely to grazing
  pressure. Total die-off of either species means the balance constants
  (flora growth in `Tile.java`, energy/breeding in the species classes)
  need retuning.
- `-Djava.awt.headless=true` avoids any accidental display dependency.
