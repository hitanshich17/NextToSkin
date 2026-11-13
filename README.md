# NextToSkin

**A one-minute skincare quiz that builds a morning and night routine from products across every brand, within your budget.**

Brands only recommend routines made of their own products. NextToSkin compares products across brands, like a
flight search compares airlines, and explains why each product was picked. It is a portfolio project: no ads,
no affiliate links, and not medical advice.

> Status: the backend is working end to end (quiz → routine → feedback → AI explanation). The web frontend and
> AWS deployment are next.

## What it does

- **Quiz → routine.** Nine questions (skin type, up to two concerns, how often your skin reacts, ingredients to
  avoid, experience with actives, pregnancy, budget, city, optional skin tone) become a "Core 4" routine:
  AM cleanser, moisturizer, sunscreen; PM cleanser, treatment, moisturizer.
- **Evidence-based rules.** A rules engine picks every product. Each rule is graded by evidence and cited
  (AAD acne guidelines, FDA, ACOG and others) in [`docs/routine-rules.md`](docs/routine-rules.md): ingredient clashes
  (no retinoid with exfoliating acids), irritants for reactive or rosacea-prone skin, no retinoids in pregnancy,
  SPF 30+ only, mineral or tinted sunscreen when it matters.
- **Budget fit.** Starts from the best match for every step and swaps to cheaper products only where the budget
  requires it. It shows the upfront total, an estimated monthly cost and cheaper alternatives for each step.
- **Climate from your city.** Live 30-day UV, dew point and air quality from Open-Meteo adjust the routine:
  SPF 50+ first in strong sun, lighter or richer moisturizers in humid or dry air.
- **"Tried it?" feedback.** Like a product and it stays in your routine. Dislike it and it's replaced; if it
  irritated you, its suspect ingredients are removed from every step. Products with similar ingredient profiles
  to the ones you liked rank higher, found with **pgvector**.
- **AI explanations.** Claude Haiku explains *why* the routine was chosen, using only the rules' own reasons.
  Answers are checked automatically (no medical claims, no restated safety notes, no invented product names),
  cached in Redis, and replaced by a template explanation if the model is slow or fails a check.

## Architecture

```
            ┌─────────────────────────────┐        ┌───────────────────────────┐
quiz ──────▶│ core-api                    │───────▶│ ai-service                │
            │ Java 21 · Spring Boot 3.5   │  REST  │ Python 3.12 · FastAPI     │
            │ rules engine, budget fit,   │◀───────│ explanations (Claude API),│
            │ climate, feedback, catalog  │        │ answer validation         │
            └──────┬───────────┬──────────┘        └───────────────────────────┘
                   │           │
        PostgreSQL 16      Redis (climate and explanation cache)
        + pgvector         Open-Meteo (weather, air quality)
```

| Part | Technology | Why |
|---|---|---|
| Core API | Java 21, Spring Boot 3.5, JDBC, Flyway | Typed, well-tested rules engine and data access; versioned schema migrations |
| AI service | Python 3.12, FastAPI, Anthropic SDK | LLM work kept separate from safety-critical rules |
| Data | PostgreSQL 16 + pgvector | Products, ingredients, feedback; sparse ingredient vectors for similarity |
| Cache | Redis | City climate (24 h) and AI explanations, so each profile is explained and paid for once |
| Testing | JUnit 5, Testcontainers, PyTest | Database tests run against real PostgreSQL + pgvector |
| CI | GitHub Actions | Java tests, Python tests and a Docker Compose build on every push |

### Data

- **Curated catalog:** 103 popular US products across the four routine categories, with full ingredient lists read
  from brand pages and 139 offers (retailer, size, price). It lives in [`catalog/`](catalog/) and is imported
  through a validating pipeline that rejects the whole import, listing every problem, if any row is wrong.
- **Open Beauty Facts import:** about 20,000 products with ingredient lists, normalized to canonical ingredients
  (used for ingredient data, not routines).
- **Ingredient normalization:** label names, synonyms and slash names map to canonical ingredients, which are tagged
  (retinoid, AHA, fragrance, UV filter, ...). Ingredient order on the label is kept, because earlier means more.

## API

| Endpoint | What it does |
|---|---|
| `POST /api/v1/routines` | Quiz answers in; AM/PM routine, prices, alternatives, climate, notes and explanation out |
| `GET/PUT/DELETE /api/v1/feedback/{productId}` | "Tried it": liked or disliked, with an optional reason |
| `GET /api/v1/status` | Health of the core API and the AI service |

Users are anonymous: the browser sends a random client ID, and stored answers never include the pregnancy answer
or the city.

## Run it locally

Requirements: Docker (Docker Desktop, or [Colima](https://github.com/abiosoft/colima) on macOS:
`brew install colima docker docker-compose && colima start`).

```bash
echo "LLM_API_KEY=your-anthropic-key" > .env   # optional: without it, explanations use the template
docker compose up --build
scripts/import-catalog.sh                        # loads the curated catalog
```

Then try it:

```bash
curl -X POST localhost:8080/api/v1/routines -H 'Content-Type: application/json' -d '{
  "skinType": "OILY", "concerns": ["BREAKOUTS", "DARK_SPOTS"], "reactivity": "SOMETIMES",
  "activesExperience": "A_LITTLE", "pregnancy": "NO", "budgetCents": 8000, "city": "Phoenix, AZ"}'
```

## Tests

```bash
cd services/core-api && ./mvnw verify      # ~200 JUnit tests; database tests need Docker
cd services/ai-service && pytest           # PyTest
```

With Colima, point Testcontainers at its Docker socket:
`DOCKER_HOST=unix://$HOME/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./mvnw verify`

## Project layout

```
services/core-api/     Spring Boot service (rules engine, catalog, feedback; Flyway migrations)
services/ai-service/   FastAPI service (explanations)
catalog/               Curated products and offers (CSV)
docs/                  Quiz spec, routine rules with sources, feedback design
scripts/               Data import scripts
.github/workflows/     CI
```

## Roadmap

- [x] Ingredient pipeline and curated US catalog
- [x] Quiz, rules engine, budget fit, climate
- [x] Feedback and pgvector similarity
- [x] AI explanations
Next, event-driven scaling:

- [ ] Catalog changes published as `product-updated` events to **Kafka**, keyed by product ID for per-product
      ordering; consumers in a consumer group with retries, a dead-letter topic and versioned JSON schemas
- [ ] **Redis** cache-aside for recommendations, invalidated by catalog events
- [ ] Several recommender instances behind **Nginx**, with load tests measuring p95 latency
- [ ] Testcontainers integration tests against real Kafka as well as PostgreSQL
- [ ] Deployment on **AWS** (ECS), provisioned with Terraform and deployed by GitHub Actions

Product:

- [ ] Label reading: ingredient lists from product photos (vision model)
- [ ] Ranking model learned from feedback, evaluated against the rules
- [ ] Next.js frontend
- [ ] Community home remedies with moderation

## Data sources

Product data from [Open Beauty Facts](https://world.openbeautyfacts.org) under the
[Open Database License](https://opendatacommons.org/licenses/odbl/1-0/); weather and air quality by
[Open-Meteo.com](https://open-meteo.com) (CC BY 4.0); curated products from public brand pages.
