#!/usr/bin/env bash
set -euo pipefail
# Run from repository root, where .env and docker-compose.prod.yml are located.
cd "$(dirname "$0")/.."
set -a
source ./.env
set +a
mkdir -p ./backups
chmod 700 ./backups
stamp=$(date -u +%Y%m%dT%H%M%SZ)
out="./backups/agendai-${stamp}.dump"
docker compose -f docker-compose.prod.yml exec -T db sh -c 'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$out"
chmod 600 "$out"
echo "Backup created: $out"
echo "IMPORTANT: backup contains plaintext decrypted? Database pg_dump contains encrypted fields, but metadata and dates remain visible. Store securely off-server."
