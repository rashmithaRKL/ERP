#!/bin/sh
# Restores to a new, isolated database. Does not overwrite the running database.
set -eu
: "${BACKUP_IDENTITY:?Set the recovery age identity file path}"
command -v age >/dev/null || { echo 'Install age from its official distribution first.' >&2; exit 1; }
DEX_INPUT=${1:?Usage: restore-recovery.sh encrypted-backup-file}
case "$DEX_INPUT" in /*) ;; *) DEX_INPUT="$PWD/$DEX_INPUT" ;; esac
DEX_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$DEX_ROOT"
DEX_DATABASE="dexlanka_recovery_$(date -u +%Y%m%d%H%M%S)"
DEX_TEMP=$(mktemp)
trap 'rm -f "$DEX_TEMP"' EXIT HUP INT TERM
chmod 600 "$DEX_TEMP"
DEX_EXPECTED=$(tr -d ' \r\n' < "$DEX_INPUT.sha256")
DEX_ACTUAL=$(shasum -a 256 "$DEX_INPUT" | cut -d ' ' -f 1)
if [ "$DEX_EXPECTED" != "$DEX_ACTUAL" ]; then echo 'Backup checksum mismatch; refusing restore.' >&2; exit 1; fi
age --decrypt --identity "$BACKUP_IDENTITY" --output "$DEX_TEMP" "$DEX_INPUT"
docker compose exec -T db createdb -U dexlanka "$DEX_DATABASE"
docker compose exec -T db pg_restore -U dexlanka --exit-on-error --single-transaction --no-owner -d "$DEX_DATABASE" < "$DEX_TEMP"
docker compose exec -T db psql -U dexlanka -d "$DEX_DATABASE" -v ON_ERROR_STOP=1 -c "SELECT COUNT(*) AS unbalanced_journals FROM (SELECT entry_id FROM journal_lines GROUP BY entry_id HAVING SUM(debit)<>SUM(credit)) e; SELECT COUNT(*) AS unreconciled_lots FROM stock_balances b LEFT JOIN (SELECT tenant_id,lot_id,SUM(delta) AS qty,SUM(value_delta) AS val FROM stock_ledger GROUP BY tenant_id,lot_id) l USING(tenant_id,lot_id) WHERE b.quantity<>COALESCE(l.qty,0) OR b.value_amount<>COALESCE(l.val,0);"
printf 'Restored into isolated database %s. Review validation results before any separate recovery cutover.\n' "$DEX_DATABASE"
