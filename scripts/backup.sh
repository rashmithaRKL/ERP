#!/bin/sh
# Authenticated encryption to an age public key. Private key is not needed on the live server.
set -eu
: "${BACKUP_RECIPIENT:?Set the recovery age public key}"
command -v age >/dev/null || { echo 'Install age from its official distribution first.' >&2; exit 1; }
DEX_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$DEX_ROOT"
DEX_BACKUP_DIR=${1:-.local/backups}
mkdir -p "$DEX_BACKUP_DIR"
chmod 700 "$DEX_BACKUP_DIR"
DEX_DUMP=$(mktemp "$DEX_BACKUP_DIR/.dump.XXXXXX")
trap 'rm -f "$DEX_DUMP"' EXIT HUP INT TERM
chmod 600 "$DEX_DUMP"
docker compose exec -T db pg_dump -U dexlanka -d dexlanka --format=custom > "$DEX_DUMP"
DEX_OUTPUT="$DEX_BACKUP_DIR/dexlanka-$(date -u +%Y%m%dT%H%M%SZ).dump.age"
age --recipient "$BACKUP_RECIPIENT" --output "$DEX_OUTPUT" "$DEX_DUMP"
chmod 600 "$DEX_OUTPUT"
DEX_DIGEST=$(shasum -a 256 "$DEX_OUTPUT" | cut -d ' ' -f 1)
printf '%s\n' "$DEX_DIGEST" > "$DEX_OUTPUT.sha256"
printf 'Encrypted backup created: %s\n' "$DEX_OUTPUT"
