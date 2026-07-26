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
