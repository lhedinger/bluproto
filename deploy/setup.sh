#!/usr/bin/env bash
# One-shot VPS setup for the bluproto world server (Ubuntu 24.04, Hetzner CX22).
# Run as root on the fresh VPS:
#   bash <(curl -fsSL https://raw.githubusercontent.com/lhedinger/bluproto/master/deploy/setup.sh)
# or clone the repo first and run deploy/setup.sh from it. Idempotent: safe to
# re-run for updates (git pull + rebuild).
set -euo pipefail

REPO_URL="${REPO_URL:-https://github.com/lhedinger/bluproto.git}"
BRANCH="${BRANCH:-master}"
APP_DIR=/opt/bluproto

echo "== bluproto deploy =="

# 1. Docker (official convenience script, skipped if present).
if ! command -v docker >/dev/null 2>&1; then
	echo "-- installing docker"
	curl -fsSL https://get.docker.com | sh
fi

# 2. Repo.
if [ ! -d "$APP_DIR/.git" ]; then
	echo "-- cloning $REPO_URL ($BRANCH)"
	git clone --branch "$BRANCH" "$REPO_URL" "$APP_DIR"
else
	echo "-- updating $APP_DIR"
	git -C "$APP_DIR" fetch origin "$BRANCH"
	git -C "$APP_DIR" checkout "$BRANCH"
	git -C "$APP_DIR" reset --hard "origin/$BRANCH"
fi
cd "$APP_DIR/deploy"

# 3. Config (.env): domain, command token, seed. Prompt only when missing.
if [ ! -f .env ]; then
	read -rp "Domain (e.g. bluproto.example.com): " DOMAIN
	read -rp "Command token (secret; viewers who have it may spawn/pause) [random]: " COMMAND_TOKEN
	COMMAND_TOKEN=${COMMAND_TOKEN:-$(head -c 18 /dev/urandom | base64 | tr -d '/+=')}
	read -rp "World seed [42]: " SEED
	SEED=${SEED:-42}
	printf 'DOMAIN=%s\nCOMMAND_TOKEN=%s\nSEED=%s\n' "$DOMAIN" "$COMMAND_TOKEN" "$SEED" > .env
	chmod 600 .env
	echo "-- wrote .env (command token: $COMMAND_TOKEN)"
fi

# 4. Origin certificate: Cloudflare dashboard -> SSL/TLS -> Origin Server ->
#    Create Certificate; paste the two PEM blocks into these files.
mkdir -p certs
if [ ! -s certs/origin.pem ] || [ ! -s certs/origin.key ]; then
	echo "!! Missing deploy/certs/origin.pem / origin.key (Cloudflare Origin Certificate)."
	echo "   Create it in Cloudflare (SSL/TLS -> Origin Server), save both files, re-run."
	exit 1
fi

# 5. Build and launch (world server + Caddy TLS edge).
echo "-- building and starting"
docker compose up -d --build

echo "== done. https://$(grep ^DOMAIN= .env | cut -d= -f2) =="
