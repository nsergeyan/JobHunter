# Job-Scout

See PROJECT_BRIEF.md for full scope, rationale, and build order. Key rules for working
in this repo:

## Build order — do not skip ahead
1. Scraper + database (Magnet.me, Indeed NL, Greenhouse/Lever career pages)
2. LLM extraction into structured fields (Pydantic schema)
3. Labeling CLI (0/1/2 fit rating) — no ML work before ~200-300 labels exist
4. Ranking model (logistic regression baseline first, then LightGBM if it wins)
5. Benchmark: trained model vs. LLM-as-judge vs. cosine similarity, precision@k
6. Daily agent loop (scan -> rank -> draft cover letters -> digest)
7. Market analysis notebook

## Hard constraints
- Never scrape LinkedIn (ToS risk to personal account) — deliberate exclusion.
- Every scraper needs rate limiting (visible sleep/backoff in code, not just claimed),
  a clear User-Agent, and error handling for layout changes.
- Respect robots.txt.
- Secrets via `.env` only, never committed.

## Decision ownership
The user owns architecture-level decisions: DB schema, labeling scheme, evaluation
metric, and which sources to scrape/prioritize. Implement and flag tradeoffs — don't
silently decide these. Smaller implementation details (e.g. which HTTP client to use)
are fine to pick with a brief rationale.

## Working style
Prefer small, working increments over speculative builds. First milestone: 200+ real
vacancies sitting in SQLite that the user can inspect via PyCharm's DB viewer.
