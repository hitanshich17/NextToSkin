#!/usr/bin/env bash
# Imports Open Beauty Facts products into the local database.
#
#   docker compose up -d postgres     # the database must be running
#   scripts/import-obf.sh             # downloads the dump once (~100 MB), then imports
#   scripts/import-obf.sh --refresh   # downloads the latest dump first
#
# Data: Open Beauty Facts (https://world.openbeautyfacts.org), Open Database License (ODbL).
set -euo pipefail

source "$(dirname "$0")/db-env.sh"
URL="https://static.openbeautyfacts.org/data/openbeautyfacts-products.jsonl.gz"
DUMP="$ROOT/data/openbeautyfacts-products.jsonl.gz"

if [[ ! -f "$DUMP" || "${1:-}" == "--refresh" ]]; then
  mkdir -p "$ROOT/data"
  echo "Downloading $URL"
  curl -fL --progress-bar -o "$DUMP.part" "$URL"
  mv "$DUMP.part" "$DUMP"
fi

cd "$ROOT/services/core-api"
./mvnw -q spring-boot:run \
  -Dspring-boot.run.arguments="--spring.main.web-application-type=none --nexttoskin.import.obf-file=$DUMP"
