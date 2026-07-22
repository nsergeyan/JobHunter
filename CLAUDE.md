# Job-Scout

See PROJECT_BRIEF.md for full scope, rationale, and build order. Key rules for working
in this repo:

## Build order - do not skip ahead
1. Scraper + database - done. One scraper class per ATS platform (Workday,
   Greenhouse, Lever, Ashby, SmartRecruiters), ~80 companies verified against
   the real posting API. Europe-only scope (narrowed from Europe+US on
   2026-07-20 - see PROJECT_BRIEF.md for why).
2. LLM extraction into structured fields - done. Java, hand-built JSON
   schema (not Pydantic), two interchangeable providers (Ollama default,
   Gemini alternative) sharing one prompt.
3. Labeling CLI (0/1/2 fit rating) - done. 201 labels as of 2026-07-22.
4. Ranking model - done. Logistic regression baseline (multi-hot skills,
   one-hot seniority/remote policy, TF-IDF title n-grams), 5-fold
   cross-validated. LightGBM not attempted yet -- only reach for it if it
   actually beats this baseline on held-out data, not by default.
5. Benchmark: trained model vs. LLM-as-judge vs. cosine similarity,
   precision@k - done. See python/ranking/benchmark.py.
6. Daily agent loop (scan -> rank -> draft cover letters -> digest)
7. Market analysis notebook

## Hard constraints
- Never scrape LinkedIn (ToS risk to personal account) - deliberate exclusion.
- Every scraper needs rate limiting (visible sleep/backoff in code, not just claimed),
  a clear User-Agent, and error handling for layout changes.
- Respect robots.txt.
- Secrets via `.env` only, never committed.

## Decision ownership
The user owns architecture-level decisions: DB schema, labeling scheme, evaluation
metric, and which sources to scrape/prioritize. Implement and flag tradeoffs - don't
silently decide these. Smaller implementation details (e.g. which HTTP client to use)
are fine to pick with a brief rationale.

## Working style
Prefer small, working increments over speculative builds. First milestone: 200+ real
vacancies sitting in SQLite that the user can inspect via PyCharm's DB viewer.
