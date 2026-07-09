---
name: run-tests
description: Compile and run the Prototype_World test suite (SimTest) — engine and ecosystem invariant checks. Use when asked to run the tests, verify nothing is broken, or gate a change before committing.
---

# Run the tests

`SimTest` is a self-contained invariant/smoke suite — no JUnit or external
jars. It prints `PASS`/`FAIL` per check and exits non-zero if anything fails,
so it doubles as a CI gate.

## Steps

1. Compile:

   ```bash
   ./build.sh
   ```

2. Run:

   ```bash
   java -cp bin net.hedinger.prototype.main.SimTest
   ```

   Exit code 0 = all passed, 1 = at least one failure.

## What it covers

- world hash ⇄ (col,row,lvl) round-trip
- A* pathfinding returns a stack ending at the goal tile
- flora grows on fertile floor and is reduced by grazing
- the day/night cycle spans a full light range
- genome inheritance stays near parent values and mutation perturbs genes
- spawn/death `WorldListener` events fire
- the ecosystem ticks thousands of steps without crashing and no total die-off

## Adding a test

Add a `testXxx()` method to `src/net/hedinger/prototype/main/SimTest.java` and
call it from `main`, using `check("name", condition)`. Keep it dependency-free
(plain assertions via `check`) so it stays runnable without a build tool.
