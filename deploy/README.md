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
(written to `deploy/.env`), builds the image, and starts server + Caddy with
`restart: unless-stopped` — the world survives reboots.

*Private repo?* Add a read-only deploy key first (GitHub → repo → Settings →
Deploy keys) and clone over SSH instead.

**5 · Open it.** `https://yourdomain` shows the live world for anyone.
To act on it, open `https://yourdomain/#t=YOURTOKEN` — the page attaches the
token to every command. Without it, spawning/pausing is refused (viewing
still works).

## Updating to the latest code

Re-run the script — it pulls and rebuilds:

```bash
bash /opt/bluproto/deploy/setup.sh
```

Or trigger the **Deploy** GitHub Action (`.github/workflows/deploy.yml`,
manual "Run workflow" button) after adding two repo secrets:
`DEPLOY_HOST` (e.g. `root@1.2.3.4`) and `DEPLOY_SSH_KEY` (a private key whose
public half is on the VPS).

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
curl https://yourdomain/api/metrics            # JSON: tick, tickMs, entities, viewers, heap, uptime
curl https://yourdomain/metrics                # Prometheus text (for scraping)
curl https://yourdomain/api/world/recording -o recording.json   # download the session
curl "https://yourdomain/api/replay?tick=5000"                  # reconstruct the world at tick 5000
curl -X POST https://yourdomain/api/replay -d @recording.json   # replay a downloaded recording
```

A recording is just the world's seed plus the (tiny) log of commands applied to
it; because the sim is deterministic, that reproduces any moment exactly. The
live session's recording is also written to the `bluproto-data` volume every
few seconds (`RECORD_DIR=/data`), so a reboot never loses viewers' spawns.

## Auto-deploy on push

Add two repo secrets (GitHub → Settings → Secrets → Actions):
`DEPLOY_HOST` (`root@your.vps.ip`) and `DEPLOY_SSH_KEY` (a private key whose
public half is in the VPS's `~/.ssh/authorized_keys`). Then every push to
`master` redeploys automatically; until the secrets are set the workflow skips
cleanly. You can also run it by hand from the Actions tab.
