# Working on this repo

## Trunk-based development

`master` is the trunk. It is the only long-lived branch, it is always releasable,
and **its history is never rewritten** — no force-push, no reset, no rebase of
master itself, ever. Everything else is a short-lived branch that exists to be
merged and then forgotten.

### The loop

1. **Rebase before starting work.** Begin every task from the current trunk:

   ```
   git fetch origin master
   git rebase origin/master          # or: git checkout -B <branch> origin/master
   ```

   Starting from a stale base means integrating against a world that no longer
   exists, and the conflicts surface at merge time when they are expensive
   instead of at the start when they are cheap.

2. **Keep the branch short-lived.** One coherent change. A branch that lives for
   days is a long-lived branch wearing a feature branch's name, and it
   accumulates exactly the divergence trunk-based development exists to avoid.

3. **Rebase again before merging**, so the branch is a straight-line continuation
   of trunk and CI tests the commits that will actually land — not an older base
   that happened to pass.

4. **Merge with rebase**, never squash or a merge commit:

   ```
   merge_method: "rebase"
   ```

   Squashing throws away the commit boundaries that were chosen deliberately, and
   a merge commit puts a fork in a history that has no forks in it. Rebasing
   keeps trunk a readable straight line where every commit is a real step that
   built and passed on its own. Write commits worth keeping — they are what lands.

5. **After the merge**, reset the branch onto the new trunk for the next task.
   The rebase-merge re-applies the commits onto master under new SHAs, so the
   branch's old copies are spent history:

   ```
   git fetch origin master && git checkout -B <branch> origin/master
   ```

   Force-with-lease is fine here, and **only** here: the branch is being pointed
   at work that is already merged. Never force anything at master.

### Conflicts

Rebase the feature branch onto trunk and resolve there. Do not merge trunk *into*
the feature branch to fix a conflict — that leaves a merge commit inside a branch
that is about to be rebased, and the conflict comes back.

## Balance work: tune the system, not the seed

Generation-zero creatures are **not** meant to be well configured. Founders are
noisy seeding — a spread of genomes to start selection from — and a default that
happens to sit at an optimum is a coincidence, not a goal. So when a constant
looks wrong, the question is never "what value would make the default creature
behave well", it is "does the system have a shape that lets a population find
its own way there, and does it survive while it looks".

Two consequences worth holding on to:

- **Do not move a gene's default to make it optimal.** If founders sit on the
  wasteful side of some curve, that is a gradient for selection to climb, which
  is the machine working. Move a default only when the seed is *unviable* — when
  gen-0 cannot make a living at all, the niche dies before selection can act,
  and nothing adapts to anything.
- **Judge a calibration on the population, not on the fixture.** A constant is
  right when the world stays alive across a range of it and the behaviour bends
  in the direction the mechanism implies. Run the demo world either side of the
  change and read the guild counts; a scenario passing is necessary and nowhere
  near sufficient.

Viability of the seed is therefore the real constraint on how hard a knob can be
turned, and it is worth measuring directly rather than inferring. When travel
cost was recalibrated, the ceiling turned out to be a founder predator's ability
to run prey down at all — healthy at 0.8, gone by 1.0 — and that, not the
arithmetic, is what picked the number.

## Pull requests

- Do not open a PR unless it was asked for.
- CI (`.github/workflows/ci.yml`) runs `./gradlew check`. Run it locally first;
  it is the same command.
- Merge on green.
