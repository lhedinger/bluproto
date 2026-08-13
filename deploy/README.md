# Deploying the world server — Hetzner VPS + Cloudflare

The chosen public stack (MODERNIZATION.md Phase 5): a small always-on Hetzner
VPS runs the simulation in Docker behind a Caddy TLS edge; Cloudflare provides
the domain, DNS, and its proxy in front (hides the VPS IP, free edge TLS).
Viewing is open to anyone with the URL; every mutating command (spawn, pause,
speed, reset) requires the **command token**.

```
visitor ── https://yourdomain ──► Cloudflare edge (proxy, TLS)
                                        │  Full (strict), origin cert
                                        ▼
                                  Hetzner VPS
                                  Caddy :443 ── reverse proxy ──► server :7070
```

## One-time setup (~20 minutes)

**1 · Domain (Cloudflare).** Buy the domain on Cloudflare Registrar
(at-cost, WHOIS privacy included). It lands on Cloudflare DNS automatically.

**2 · VPS (Hetzner).** Cloud console → new server: type **CX22**, image
**Ubuntu 24.04**, add your SSH key. Note the public IPv4.

**3 · DNS + TLS (Cloudflare dashboard).**
- DNS → add an **A record**: name `@` (or a subdomain), value = the VPS IP,
  **Proxied** (orange cloud).
- SSL/TLS → Overview → mode **Full (strict)**.
- SSL/TLS → Origin Server → **Create Certificate** (defaults are fine,
  15-year validity). Keep both PEM blocks for step 4.

**4 · VPS bootstrap.** SSH in as root and run:

```bash
git clone https://github.com/lhedinger/bluproto.git /opt/bluproto
mkdir -p /opt/bluproto/deploy/certs
# paste the Cloudflare origin certificate + private key:
nano /opt/bluproto/deploy/certs/origin.pem
nano /opt/bluproto/deploy/certs/origin.key
bash /opt/bluproto/deploy/setup.sh
```

The script installs Docker, asks for the domain / command token / seed
(written to `deploy/.env`), pulls the world-server image (built in CI, see
below) or builds it locally on first boot, and starts server + Caddy +
watchtower with `restart: unless-stopped` — the world survives reboots.

*Private repo?* Add a read-only deploy key first (GitHub → repo → Settings →
Deploy keys) and clone over SSH instead.

**5 · Open it.** `https://yourdomain` shows the live world for anyone.
To act on it, open `https://yourdomain/#t=YOURTOKEN` — the page attaches the
token to every command. Without it, spawning/pausing is refused (viewing
still works).

## Updating: push to master, it deploys itself

Every push to `master` runs **`.github/workflows/publish.yml`**, which builds
a multi-arch (amd64 + arm64) image and pushes it to GHCR
(`ghcr.io/lhedinger/bluproto:latest`). On the VPS, the **watchtower** service
polls that package every couple of minutes and, when a new image lands, pulls
it and recreates the server container in place — env vars, the recording
volume, and TLS all preserved. No SSH key, no build on the VPS.

**One-time, after the first publish:** make the package public so the VPS can
pull it without credentials — GitHub → your repo → **Packages** → `bluproto`
→ *Package settings* → **Change visibility** → *Public*. (Private also works,
but then watchtower needs a registry login — see its docs.)

Need an immediate update without waiting for the poll, or to change `.env`?
Re-run the script — it pulls the latest image and restarts:

```bash
bash /opt/bluproto/deploy/setup.sh
```

## Operations crib sheet

```bash
cd /opt/bluproto/deploy
docker compose logs -f server     # world log
docker compose restart server     # bounce the sim (fresh world, same seed)
docker compose up -d --build      # rebuild after a manual git pull
curl -s https://yourdomain/api/health   # {"ok":true,"tick":...}
```

Reset to a fresh world from anywhere:

```bash
curl -X POST https://yourdomain/api/world/reset \
     -H "X-Command-Token: YOURTOKEN" -d '{"seed": 7}'
```

## Metrics & replay

```bash
curl https://yourdomain/api/metrics            # JSON: tick, tickMs, entities, viewers, heap, uptime, visitors
curl https://yourdomain/metrics                # Prometheus text (for scraping)
```

`visitors` is how many distinct addresses have contacted this server since it
started, and `httpRequests` how many times. Addresses are hashed against a salt
generated at boot and never written down, so the server can count its audience
but cannot name anyone in it — and the numbers do not survive a restart, which is
what "since it started" already meant. `visitorsCapped` turns true if the
distinct count hits its 20 000 ceiling, at which point read it as "at least".

```bash
curl https://yourdomain/api/world/recording -o recording.json   # download the session
curl "https://yourdomain/api/replay?tick=5000"                  # reconstruct the world at tick 5000
curl -X POST https://yourdomain/api/replay -d @recording.json   # replay a downloaded recording
```

A recording is just the world's seed plus the (tiny) log of commands applied to
it; because the sim is deterministic, that reproduces any moment exactly. The
live session's recording is also written to the `bluproto-data` volume every
few seconds (`RECORD_DIR=/data`), so a reboot never loses viewers' spawns.
