---
name: ship
description: Land finished work on this repo — rebase onto trunk, run ./gradlew check, open the PR, merge with rebase on green, then follow the deploy to the live server and confirm it is serving the new commit. Use this whenever the user says ship, land, merge, release, deploy, "push it live", "get this out", or asks whether a change is deployed or why the live world is still on an old build. Also use it when finishing a feature branch and the next question is what to do with it, and when a deploy has gone quiet or a merged change has not shown up at world.evarium.cc.
---

# Shipping bluproto

`master` is trunk, and a push to it *is* the deploy — there is no separate
release step, no SSH, nothing to run on the server. That makes landing a change
short but unforgiving: whatever merges is live within a couple of minutes, and
the history it merges into is never rewritten.

This skill is the sequence that gets a branch from finished to verifiably live.
The rules it enforces come from `CLAUDE.md` (trunk-based development) and the
deploy path in `deploy/README.md` and `.github/workflows/publish.yml`; read
those if something here doesn't match what you find.

## How the deploy actually works

Understanding this is most of the skill, because the failure modes only make
sense once you know the pipeline is *pull-based*:

```
push to master ──► publish.yml builds the image ──► GHCR :latest
                                                         │
                        Watchtower on the Hetzner VPS polls it ~every 120s
                                                         │
                                                         ▼
                                     new container, live at world.evarium.cc
```

Nothing pushes to the VPS. CI publishes an image and the box helps itself, so
"merged" and "deployed" are separated by a poll interval you do not control.
`publish.yml`'s `verify-deploy` job closes that gap: it polls
`https://world.evarium.cc/api/health` for up to 8 minutes waiting for `.commit`
to equal the short SHA it just built, and fails red if it never arrives.

Two consequences worth holding on to:

- **A merge is not a deploy.** Until `/api/health` reports the new commit, the
  old image is still serving. Never tell the user something is live on the
  strength of a green merge.
- **There is no rollback.** The deploy is a pull with no known-good to revert
  to; `latest` keeps pointing at a bad image until the *next* push to master
  replaces it. So a broken deploy is fixed by rolling forward, and that is why
  the checks below happen before the merge rather than after.

## The sequence

### 1 · Start from current trunk

```bash
git fetch origin master
git rebase origin/master
```

A branch built on a stale base integrates against a world that no longer
exists, and the conflicts surface at merge time — when they are expensive —
instead of now, when they are cheap. If the rebase conflicts, resolve it here
on the branch. Do not merge trunk *into* the branch to settle a conflict: that
leaves a merge commit inside a branch that is about to be rebased, and the
conflict comes back.

If the branch's PR has already been merged, it is spent history. Restart it
from trunk (`git checkout -B <branch> origin/master`) and treat the follow-up
as a new PR rather than stacking commits on merged work.

### 2 · Run the same check CI runs

```bash
./gradlew check
```

This is the whole gate — it runs the deterministic scenario suite (`SimTests`,
the project's regression backbone), the server tests, and compiles the Vite/TS
client. CI runs this exact command, so a local pass means the PR will be green,
and a local failure saves a round trip. Read the tail of the output for
`N passed, 0 failed` rather than trusting the exit code alone.

Two things that look like test failures but aren't:

- **Line endings.** `Grid.java` and `LayerRenderer.java` are CRLF; most of the
  tree is LF. A diff of thousands of unchanged lines in one file means a tool
  rewrote its endings. Check with `git diff --stat --ignore-all-space <file>` —
  if that shows a handful of lines, restore the endings before committing.
- **A scenario that only fails with `-Dsimtest.shots`.** The snapshot renderer
  is not on the gate; a failure there is a rendering bug, not a sim bug.

### 3 · Commit as work worth keeping

Trunk is merged by rebase, so the commits on the branch are the commits that
land on master, under new SHAs but with their boundaries intact. That is the
reason to split by idea rather than dumping one commit at the end: each commit
should build and pass on its own, because each one becomes a point in trunk's
history that someone may later bisect to.

Match the repo's commit voice — a short declarative title that says what the
world can now do ("The fourth trophic level: parasites ride the herd and drink
it"), then a body explaining *why*, including what was measured and what was
rejected. Keep model identifiers out of commits, PR titles and bodies entirely.

### 4 · Push and open the PR

```bash
git push -u origin <branch>
```

There is no `gh` CLI in this environment — use the `mcp__github__*` tools
(`create_pull_request`, `pull_request_read`, `merge_pull_request`,
`actions_list`, `get_job_logs`). Check for a PR template under `.github/`
first; if none exists, write the body as a short summary plus anything a
reviewer would otherwise have to reconstruct: measurements taken, alternatives
rejected, behaviour that changed for existing worlds.

Open a PR only when the user has asked for one. `CLAUDE.md` is explicit about
that, and so is the harness.

### 5 · Merge on green, by rebase

Wait for the `CI` workflow to pass on the PR head, then:

```
merge_pull_request(..., merge_method: "rebase")
```

Rebase, never squash and never a merge commit. Squashing throws away commit
boundaries that were chosen deliberately; a merge commit puts a fork in a
history that has no forks in it. Trunk stays a readable straight line where
every commit built and passed on its own.

Never force-push, reset, or rebase master itself. Force-with-lease is fine in
exactly one place: pointing your own feature branch at work that has already
merged.

### 6 · Follow it to the live server

The merge kicks `publish.yml`. Watch both of its jobs — `publish` (builds and
pushes the image) and `verify-deploy` (waits for the live server to report the
new commit). Then confirm it yourself:

```bash
curl -fsS https://world.evarium.cc/api/health
```

You want `.commit` to equal the first 7 characters of the merge commit's SHA.
The same payload carries `tick`, `entities`, `tickMillis`, `tickBudgetMillis`
and `keepingUp` — glance at `keepingUp`, since a change that makes the sim
miss its 30.3 ms tick budget doesn't crash, it just quietly runs slow, and this
is the only place that shows from outside.

Report the live commit and `keepingUp` back to the user. That is what "deployed"
means here; anything short of it is "merged".

### 7 · Reset the branch for the next task

The rebase-merge re-applied your commits under new SHAs, so the branch's copies
are spent:

```bash
git fetch origin master && git checkout -B <branch> origin/master
```

## When the deploy doesn't land

Work down this list rather than guessing — each step distinguishes a different
failure:

1. **`verify-deploy` red, `publish` green.** The image built but the live server
   never reported the commit. Usually the container is crash-looping on the VPS
   (an OOM at boot is the classic). The job's log prints the last commit it did
   see, which tells you whether the box is serving something older or nothing at
   all. There is no rollback — fix forward with another push to master.
2. **Both jobs green, but `/api/health` shows the old commit.** Almost always
   just the Watchtower poll; give it another couple of minutes before treating
   it as a problem.
3. **`curl` fails from a sandboxed session.** Outbound HTTPS goes through an
   agent proxy that may deny the CONNECT with a 403 — which is a policy denial,
   not the site being down. Confirm with
   `curl -sS "$HTTPS_PROXY/__agentproxy/status"` and look at
   `recentRelayFailures`. Say you could not reach it; do not report the world as
   down on the strength of a proxied 403.
4. **The image is stale on the VPS.** The GHCR package must be public for the
   box to pull without credentials. Worth checking once, after the very first
   publish, and essentially never again.

## What not to do

- Don't open a PR, merge, or push to master unless the user asked. Merging here
  publishes to a URL anyone can open.
- Don't merge red, and don't merge with a stale base — CI would have tested
  commits that aren't the ones landing.
- Don't claim a deploy without having seen the commit in `/api/health`.
