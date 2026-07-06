#!/usr/bin/env bash
# Bootstraps AppRole authentication on the local dev-mode Vault started via
# `docker compose up -d vault` (see docker-compose.yml and section 6 of
# docs/arquitectura-facturacion-electronica-cr.md).
#
# Dev-mode Vault auto-unseals with a root token but has neither AppRole auth,
# the transit engine, nor kv-v2 configured — this script does that one-time
# setup. It is idempotent: safe to re-run any time after the container is up
# (re-running only issues a fresh secret-id, which is expected/desired since
# secret-ids are meant to be short-lived).
#
# Usage (from repo root, after `docker compose up -d postgres vault`):
#   ./scripts/vault-setup-local.sh
#
# Prints VAULT_ROLE_ID / VAULT_SECRET_ID at the end so they can be exported
# into the shell or copied into .env, alongside VAULT_ADDR.

set -euo pipefail

cd "$(dirname "$0")/.."

COMPOSE_SERVICE="${VAULT_COMPOSE_SERVICE:-vault}"
VAULT_ADDR_IN_CONTAINER="${VAULT_ADDR_IN_CONTAINER:-http://127.0.0.1:8200}"
POLICY_NAME="empresa-secretos"
TRANSIT_KEY="empresa-datos-kek"
APPROLE_NAME="fractall-backend"

log() { echo "==> $*" >&2; }

log "Locating dev-mode root token from '$COMPOSE_SERVICE' container logs..."
ROOT_TOKEN="$(docker compose logs "$COMPOSE_SERVICE" 2>/dev/null | grep -m1 -oE 'Root Token: .*' | awk '{print $3}')"

if [[ -z "$ROOT_TOKEN" ]]; then
  echo "ERROR: could not find the dev-mode root token in 'docker compose logs $COMPOSE_SERVICE'." >&2
  echo "Is the Vault container up? Try: docker compose up -d postgres $COMPOSE_SERVICE" >&2
  exit 1
fi
log "Root token found."

vault_exec() {
  docker compose exec -T \
    -e VAULT_ADDR="$VAULT_ADDR_IN_CONTAINER" \
    -e VAULT_TOKEN="$ROOT_TOKEN" \
    "$COMPOSE_SERVICE" vault "$@"
}

log "Enabling approle auth method (idempotent)..."
vault_exec auth list -format=json | grep -q '"approle/"' \
  || vault_exec auth enable approle

log "Enabling transit secrets engine (idempotent)..."
vault_exec secrets list -format=json | grep -q '"transit/"' \
  || vault_exec secrets enable transit

log "Creating transit key '$TRANSIT_KEY' (idempotent)..."
vault_exec read -format=json "transit/keys/$TRANSIT_KEY" >/dev/null 2>&1 \
  || vault_exec write -f "transit/keys/$TRANSIT_KEY"

log "Enabling kv-v2 secrets engine at secret/ (idempotent)..."
vault_exec secrets list -format=json | grep -q '"secret/"' \
  || vault_exec secrets enable -path=secret -version=2 kv

log "Writing policy '$POLICY_NAME' (read/create/update on empresas/* and the transit paths this phase needs)..."
printf '%s' "$(cat <<EOF
path "secret/data/empresas/*" {
  capabilities = ["read", "create", "update"]
}

path "transit/keys/${TRANSIT_KEY}" {
  capabilities = ["read", "create", "update"]
}

path "transit/datakey/plaintext/${TRANSIT_KEY}" {
  capabilities = ["create", "update"]
}

path "transit/decrypt/${TRANSIT_KEY}" {
  capabilities = ["create", "update"]
}
EOF
)" | vault_exec policy write "$POLICY_NAME" -

log "Creating AppRole role '$APPROLE_NAME' bound to policy '$POLICY_NAME' (idempotent)..."
vault_exec write "auth/approle/role/$APPROLE_NAME" \
  token_policies="$POLICY_NAME" \
  token_ttl=1h \
  token_max_ttl=4h \
  secret_id_ttl=0 \
  token_num_uses=0

log "Reading role-id..."
ROLE_ID="$(vault_exec read -field=role_id "auth/approle/role/$APPROLE_NAME/role-id")"

log "Generating a fresh secret-id..."
SECRET_ID="$(vault_exec write -field=secret_id -f "auth/approle/role/$APPROLE_NAME/secret-id")"

echo ""
echo "Bootstrap complete. Export these (or copy into .env):"
echo ""
echo "export VAULT_ADDR=${VAULT_ADDR_IN_CONTAINER}"
echo "export VAULT_ROLE_ID=${ROLE_ID}"
echo "export VAULT_SECRET_ID=${SECRET_ID}"
