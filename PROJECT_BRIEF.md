# Project Brief: Job-Scout (working name)

## What this is
A personal job-search agent + data science project for a 3rd-year Data
Science/AI student (Leiden University). It has two purposes at once:
1. A real tool I will use to find internship/junior DS/AI/ML/software
   engineering roles across Europe and the US — not restricted to the
   Netherlands. The idea: a good opportunity worth relocating/traveling for
   is worth surfacing, and applying more broadly is also just good interview
   practice.
2. A portfolio project demonstrating the full DS lifecycle: data
   collection, labeling, model training, evaluation, and applied statistics
   — not just LLM orchestration.

## Why it's structured this way
Similar "AI job agents" already exist (e.g. career-ops) but they all use
an LLM to judge fit every time. The differentiator here: train an actual
ranking/classification model on self-labeled data, and benchmark it
against LLM-as-judge and naive embedding similarity. That comparison is
the core data science contribution.

## Scope for v1 (build in this order — do not skip ahead)
1. **Scraper + database.** Company-by-company: manually find companies
   worth applying to, identify what ATS platform their careers page runs
   on, and scrape via that platform's public JSON API where one exists
   (Workday confirmed working, e.g. Zendesk; Greenhouse/Lever are likely
   future platforms as more companies get added). Scope per posting:
   internship or junior/graduate-level (not senior+), DS/AI/ML/software
   engineering, located in Europe or the US. Do NOT scrape LinkedIn
   directly (ToS risk to personal account) — this is a deliberate,
   documented exclusion, not an oversight. Magnet.me and StudentJob.nl
   (NL-only student job boards) were tried first and retired once the
   scope went global — see commit history around 2026-07-19 if the
   old approach is ever worth revisiting.
2. **LLM extraction.** Parse raw postings into structured fields (skills,
   seniority, salary if present, language requirement, remote policy)
   using an LLM call with a Pydantic schema for validation.
3. **Labeling tool.** A simple CLI that shows me a vacancy and records my
   own fit rating (0/1/2) into the database. This produces the training
   labels — no ML before this exists.
4. **Ranking model.** Once ~200-300 labels exist, train a model
   (start: LightGBM or logistic regression on engineered features +
   embeddings) to predict fit.
5. **Benchmark.** Compare trained model vs. LLM-as-judge vs. cosine
   similarity on a held-out, time-based test set. Report precision@k.
6. **Agent loop.** Daily scan -> rank -> draft tailored cover letters for
   top matches -> digest.
7. **Market analysis.** Once data accumulates over the semester, a
   notebook analyzing the junior DS/AI/software market across the
   companies scraped (skill demand, salary patterns, etc.).

## Tech constraints
- **Language split (revised from the original Python-only plan):** scraping,
  database, labeling CLI, and the daily agent loop (build-order steps 1-3, 6)
  are written in **Java** — a deliberate choice for Java learning experience,
  not a technical necessity. The ranking-model work (steps 4-5) stays in
  **Python**, since scikit-learn/LightGBM have no comparable Java equivalent.
  Both share one SQLite file.
- macOS, IntelliJ (Java side) / PyCharm (Python side, once that work starts).
- Database: SQLite to start (simple, works with PyCharm's/IntelliJ's DB viewer).
- Respect robots.txt and rate-limit all scrapers; this must be visible in
  code (sleep/backoff), not just claimed in docs.
- Config/secrets via `.env` at the repo root (shared by both languages),
  never committed.

## Repo structure
```
job-scout/
├── README.md
├── PROJECT_BRIEF.md
├── .env.example
├── .gitignore
├── JobHunterTech/        # Java: scraper, database, (later) labeling CLI + agent loop
│   ├── build.gradle
│   ├── src/main/java/com/jobscout/
│   │   ├── scraper/       # one class per source, shared BaseScraper/HttpFetcher
│   │   └── db/            # schema init + simple upsert functions
│   └── src/test/java/com/jobscout/
├── python/                # Python: ranking model + benchmark (added when that work starts)
└── data/                  # gitignored, local SQLite file lives here
```

## Working style (important for how Claude Code should help)
- I own the design decisions: schema design, labeling scheme, evaluation
  metric, which sources to scrape. Claude Code should implement, explain
  its choices when asked, and flag tradeoffs — not silently decide
  architecture for me.
- Prefer small, working increments over big speculative builds. First
  milestone: 200+ real vacancies sitting in SQLite that I can inspect.
- Every scraper needs: rate limiting, a clear User-Agent, and error
  handling for layout changes (sites change HTML often).

## Open notes / risks flagged during planning
- Indeed actively fights scrapers (Cloudflare/captchas) — not pursued.
- Prefer each ATS platform's public JSON API (Workday's `wday/cxs/...`,
  Greenhouse's `boards-api.greenhouse.io`, Lever's
  `api.lever.co/v0/postings/...`) over HTML scraping where available —
  less brittle than parsing markup, and one scraper class per platform
  covers every company on it.
- Company scope is intentionally broad (Europe + US, not just NL) — a
  single company's current openings can easily be zero after the
  seniority + region filters, so volume comes from adding many companies,
  not from loosening the per-posting filters.
- With only ~200-300 labeled examples, start the ranking model with
  regularized logistic regression as the baseline; only move to LightGBM
  if it actually beats that baseline on the held-out set.
- Labels reflect the author's personal fit judgment, not objective job
  quality — frame the benchmark as "a personalized ranker vs. generic
  LLM judging," not an objective quality claim.
