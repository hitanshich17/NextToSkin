# NextToSkin

Answer a one-minute quiz and get a morning and night skincare routine built from products across every brand, within your budget. Brands build routines from their own lines; NextToSkin compares them all.

> NextToSkin is not medical advice. Severe or unusual skin problems should be seen by a dermatologist.

## Architecture

| Service | Stack | Role |
|---|---|---|
| `core-api` | Java 21, Spring Boot | Products, ingredients, quiz, routine rules, replacements, feedback |
| `ai-service` | Python, FastAPI | Label reading, embeddings, explanations, ranking |
| PostgreSQL + pgvector | | Products, canonical ingredients, similarity search |
| Redis | | Caching repeated lookups |
| S3 + SQS (LocalStack locally) | | Label images and async scan jobs |

Locally, everything runs in Docker. In production it runs on a single EC2 instance, with S3/SQS, provisioned by Terraform (see `infra/terraform`).

## Run it locally

Requirements: Docker (Docker Desktop, or [Colima](https://github.com/abiosoft/colima) on macOS: `brew install colima docker docker-compose && colima start`).

```bash
cp .env.example .env
docker compose up --build
```

Then check:

- Core API status: http://localhost:8080/api/v1/status  → `{"service":"core-api","status":"up","aiService":"up"}`
- Core API health (DB + Redis): http://localhost:8080/actuator/health
- AI service: http://localhost:8000/health and interactive docs at http://localhost:8000/docs

## Run tests

```bash
# Java (needs JDK 21). Database tests start PostgreSQL in Docker with Testcontainers
# and are skipped when Docker isn't running.
cd services/core-api && ./mvnw verify

# With Colima, tell Testcontainers where Docker is:
DOCKER_HOST=unix://$HOME/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./mvnw verify

# Python
cd services/ai-service
python -m venv .venv && source .venv/bin/activate
pip install -r requirements-dev.txt && pytest
```

## Import product data

Imports [Open Beauty Facts](https://world.openbeautyfacts.org) products and normalizes their ingredient lists (~20k products with ingredients, about a minute):

```bash
docker compose up -d postgres
scripts/import-obf.sh            # downloads the dump once (~100 MB) into data/
```

### Curated catalog

The products routines are built from live in `catalog/products.csv` (one row per product, with its full ingredient list and the page it came from) and `catalog/offers.csv` (one row per retailer and size, with price and link). Sizes in fl oz / oz are converted to ml / g.

```bash
scripts/import-catalog.sh        # rejects the whole import, listing every problem, if any row is invalid
```

## Project layout

```
services/core-api/     Spring Boot service (schema migrations in src/main/resources/db/migration)
services/ai-service/   FastAPI service
infra/localstack/      Creates the local S3 bucket and SQS queues
infra/terraform/       AWS deployment (coming later)
catalog/               Curated products and offers (CSV)
scripts/               Data import scripts
.github/workflows/     CI: Java tests, Python tests, Docker builds
```

## Roadmap

1. Foundation: services, local environment, CI ✅
2. Ingredient pipeline: Open Beauty Facts import, ingredient normalization ✅
3. US product catalog: curated products, prices, categories ✅
4. Quiz and routine rules engine (budget, climate from city)
5. Replacements and feedback ("tried it? liked it?")
6. AI: label reading, explanations, ranking model learned from feedback
7. Frontend, AWS deployment
8. Community home remedies

## Data sources

Product data from [Open Beauty Facts](https://world.openbeautyfacts.org), available under the [Open Database License](https://opendatacommons.org/licenses/odbl/1-0/).
