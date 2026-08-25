# python/

Labeling CLI, ranking model, and benchmark - see `../README.md` for the full
methodology. LLM extraction is Java (`../JobHunterTech`); labeling was originally
planned as Java too but moved to Python on 2026-07-21.

Reads/writes the same SQLite file the Java side uses: `../data/job_scout.db`.

## Modules

- **`labeling/`** - terminal CLI that shows one vacancy at a time and records a
  0/1/2 fit rating per keypress (step 3). `python -m labeling.cli`.
- **`ranking/`** - feature engineering, logistic regression baseline, and the
  three-way benchmark (steps 4-5):
  - `data.py` - loads labeled vacancies from SQLite.
  - `filters.py` - hard, rule-based filters applied before ranking (e.g. drops
    postings requiring a non-English language).
  - `preferences.py` - the fit preference profile shared by the LLM-judge and
    cosine-similarity baselines.
  - `baseline.py` - the logistic regression model: feature engineering
    (multi-hot skills, one-hot seniority/remote policy, TF-IDF title n-grams),
    5-fold cross-validation, and coefficient inspection.
    `python -m ranking.baseline`.
  - `llm_judge.py` - scores each posting 0-100 against the preference profile
    via a local Ollama call.
  - `embeddings.py` - cosine similarity between the preference profile and
    each posting, via Ollama embeddings.
  - `benchmark.py` - runs all three and reports precision@k side by side.
    `python -m ranking.benchmark`.
  - `digest.py` - ranks unlabeled postings and prints/saves the shortlist
    (step 6). `python -m ranking.digest`.
- **`orchestrator.py`** - the daily pipeline (step 6): scrape -> extract -> rank
  -> digest, shelling out to the Java stages via Gradle.
  `python -m orchestrator`.
- **`analysis/`** - placeholder for step 7 (market analysis notebook).

## Daily pipeline

```bash
python -m orchestrator                 # scrape -> extract -> rank -> digest
python -m orchestrator --digest-only   # re-rank what's already stored (fast)
python -m ranking.digest               # the ranking half on its own
```

The digest ranks postings that are **unlabeled** and scraped within the last
`--days` (default 14), then applies the seniority and location views from
`ranking/preferences.py` (currently internships in the Netherlands). Labeling a
posting is also how you dismiss it: rated postings never appear again, and the
rating feeds the model that does the ranking.

```bash
python -m ranking.digest --all-seniority          # ignore the seniority filter
python -m ranking.digest --all-locations          # ignore the location filter
python -m ranking.digest --seniority internship,junior
python -m ranking.digest --location berlin,munich
python -m ranking.digest --days 0 -k 20           # no time limit, top 20
```

Both views are **display** filters: the model trains on every labeled posting
regardless, and scores each posting independently, so narrowing them changes
what you see but never the order. Top-k is applied after them, so `-k 10` yields
ten postings that match, not ten postings of which some match.

Location is free text and differs per platform (`Amsterdam, NL`,
`Veldhoven, Netherlands`, `ACT (Amsterdam - Acanthus)`, bare `Eindhoven`), so
matching is on **tokens, not substrings**: a substring test for `nl` would also
match `Finland`. `NETHERLANDS_LOCATION_TERMS` in `ranking/filters.py` holds the
country tokens plus the city names needed for postings that name no country.

Each run writes `../data/digests/YYYY-MM-DD.md` (`--no-save` to skip), so a long
pipeline run leaves something behind.

Two runtime notes. Extraction is the slow stage, roughly 20 seconds per posting
through local Ollama, so a few hundred fresh postings takes an hour or more;
stages stream output live so you can watch progress. And Gradle 9.2 cannot run
on a JDK newer than it supports, so the orchestrator points the daemon at a
JDK 21 it finds automatically; set `GRADLE_JAVA_HOME` in the root `.env` if
yours lives somewhere unusual.

## Setup

```bash
cd python
python3 -m venv .venv
source .venv/bin/activate
pip install -e ".[dev]"
```

`ranking/llm_judge.py` and `ranking/embeddings.py` call a local Ollama server:

```bash
ollama pull qwen3:8b            # same model the Java extraction step uses
ollama pull nomic-embed-text    # embedding model for cosine similarity
ollama serve                    # if not already running
```
