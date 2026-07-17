# Job-Scout

A personal job-search agent for junior Data Science/AI roles in the Netherlands, and a
portfolio project demonstrating the full DS lifecycle — not just LLM orchestration.

Most "AI job agent" projects use an LLM to judge fit on every run. Job-Scout instead
trains a real ranking model on self-labeled fit data and benchmarks it against
LLM-as-judge and embedding-similarity baselines. See [PROJECT_BRIEF.md](PROJECT_BRIEF.md)
for the full scope, rationale, and build order.

## Status

Early scaffold — scraper and database schema only. No live scraping yet.

## Project structure

```
src/
├── scrapers/     # one module per source (Magnet.me, Indeed NL, Greenhouse, Lever)
├── db/           # SQLite schema + data access functions
├── extraction/   # LLM -> structured JSON parsing (Pydantic schemas)
├── labeling/     # CLI tool for recording fit ratings (0/1/2)
├── ranking/      # feature engineering + model training
└── analysis/     # market analysis notebooks
data/             # gitignored, local SQLite file lives here
tests/
```

## Setup

```bash
python -m venv .venv
source .venv/bin/activate
pip install -e .
cp .env.example .env  # fill in your own values
```

## Build order

1. Scraper + database
2. LLM extraction into structured fields
3. Labeling CLI
4. Ranking model
5. Benchmark (trained model vs. LLM-as-judge vs. embedding similarity)
6. Daily agent loop
7. Market analysis notebook

Each step builds on the previous one's data — see PROJECT_BRIEF.md before skipping ahead.
