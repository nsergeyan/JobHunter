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
- **`analysis/`** - placeholder for step 7 (market analysis notebook).

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
