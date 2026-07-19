# Job-Scout

A personal job-search agent for junior Data Science/AI roles in the Netherlands, and a
portfolio project demonstrating the full DS lifecycle — not just LLM orchestration.

Most "AI job agent" projects use an LLM to judge fit on every run. Job-Scout instead
trains a real ranking model on self-labeled fit data and benchmarks it against
LLM-as-judge and embedding-similarity baselines. See [PROJECT_BRIEF.md](PROJECT_BRIEF.md)
for the full scope, rationale, and build order.

## Status

Scraper + database layer built and tested (Magnet.me, StudentJob.nl, Workday-hosted
companies e.g. Zendesk). LLM extraction, labeling, and ranking are not built yet.

## Language split

The scraping/database/labeling/agent-loop layers are written in **Java** (learning
goal). The ranking-model work (steps 4-5 of the build order) will be **Python**, since
scikit-learn/LightGBM have no comparable Java equivalent. Both share the same SQLite
file.

## Project structure

```
JobHunterTech/        # Java: scraper, database, (later) labeling CLI + agent loop
├── build.gradle
├── src/main/java/com/jobscout/
│   ├── scraper/       # one class per source (Magnet.me, StudentJob.nl, Workday)
│   ├── db/            # SQLite schema init + upsert
│   └── Main.java
└── src/test/java/com/jobscout/
python/                # Python: ranking model + benchmark (added when that work starts)
data/                  # gitignored, local SQLite file lives here
```

## Setup

```bash
cd JobHunterTech
cp ../.env.example ../.env  # fill in your own values -- shared at the repo root
./gradlew test
./gradlew run           # runs all scrapers, writes into ../data/job_scout.db
```

Gradle's own daemon needs a JDK it supports as its runtime (JDK 26 was too new for
Gradle 9.2 at the time this was set up) — if `./gradlew` fails with "Unsupported
class file major version", point `JAVA_HOME` at an older JDK (21 works) before
running it.

## Build order

1. Scraper + database
2. LLM extraction into structured fields
3. Labeling CLI
4. Ranking model
5. Benchmark (trained model vs. LLM-as-judge vs. embedding similarity)
6. Daily agent loop
7. Market analysis notebook

Each step builds on the previous one's data — see PROJECT_BRIEF.md before skipping ahead.
