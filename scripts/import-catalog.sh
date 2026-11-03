#!/usr/bin/env bash
# Imports the hand-curated catalog (catalog/products.csv, catalog/offers.csv) into the local database.
# The whole import is rejected, with every problem listed, if any row is invalid.
#
#   docker compose up -d postgres
#   scripts/import-catalog.sh
set -euo pipefail
source "$(dirname "$0")/db-env.sh"

cd "$ROOT/services/core-api"
./mvnw -q spring-boot:run \
  -Dspring-boot.run.arguments="--spring.main.web-application-type=none --nexttoskin.import.catalog-dir=$ROOT/catalog"
