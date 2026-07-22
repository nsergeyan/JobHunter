# Job-Scout

A personal job-search agent for internship/junior Data Science/AI/ML/software
engineering roles across Europe, and a portfolio project demonstrating the
full DS lifecycle - not just LLM orchestration.

Most "AI job agent" projects use an LLM to judge fit on every run. Job-Scout instead
trains a real ranking model on self-labeled fit data and benchmarks it against
LLM-as-judge and embedding-similarity baselines. See [PROJECT_BRIEF.md](PROJECT_BRIEF.md)
for the full scope, rationale, and build order.

## Status

**Scraper + database:** ~80 companies across 5 ATS platforms (Workday, Greenhouse,
Lever, Ashby, SmartRecruiters), each verified against the real posting API rather
than trusted from a guessed slug or the HTML page. Magnet.me/StudentJob.nl (NL-only
student job boards) were tried first and retired once the scope went global - see
git history around 2026-07-19. Region scope narrowed from Europe+US back to
Europe-only around 2026-07-20 (see PROJECT_BRIEF.md for why).

**LLM extraction:** two interchangeable providers (Ollama, local/free, default;
Gemini API, rate-limited free tier) sharing one prompt so results stay comparable
model-vs-model rather than prompt-vs-prompt.

**Labeling:** a terminal CLI (`python/labeling/`) for rating each scraped posting
0/1/2 (no/maybe/yes) against personal fit. 201 postings labeled as of 2026-07-22.

**Ranking model + benchmark:** a regularized logistic-regression baseline
(`python/ranking/`), evaluated with 5-fold cross-validation, compared against an
LLM-as-judge and a cosine-similarity-to-preference-profile baseline - all three
scored on precision@k (true positive = a "strong yes" label) against the same 195
held-out, English-only postings. Run `python -m ranking.benchmark` to reproduce.

| | precision@5 | precision@10 | precision@20 |
|---|---|---|---|
| Logistic regression (out-of-fold) | 0.60 | 0.40 | 0.35 |
| LLM-as-judge | 0.40 | 0.40 | 0.40 |
| Cosine similarity | 0.40 | 0.50 | 0.50 |

Two findings worth calling out:

- With only ~30 "strong yes" labels to train on, plain **cosine similarity** against
  a hand-written preference paragraph - no training data, no labels, just an
  embedding model - held up as well as (and past the top few results, better than)
  the trained model. A useful reminder that a trained ranker isn't automatically
  better than a well-scoped heuristic once labeled data is scarce.
- The **LLM-as-judge** result roughly doubled (precision@k from ~0.20 to ~0.40
  across the board) after a single fix: asking it to score fit 0-100 instead of a
  discrete 0/1/2. The 3-way scale gave the model only 3 possible answers, so most
  postings landed in the same bucket and "top-k" beyond that point was decided by
  arbitrary tie-breaking rather than real judgment - confirmed by checking the rating
  distribution before assuming the model's judgment itself was bad.

## Future work

**Daily agent loop (step 6) is intentionally not started yet.** With precision@k
still in the 0.35-0.60 range on ~30 positive examples, a "top 5" shortlist can
easily be 2-3 misses - fine for a ranked list a human skims, not reliable enough to
trust an unattended notifier acting on it. This needs meaningfully more labeled
data (and likely combining the three scoring methods rather than picking one)
before it's worth automating.

**Market analysis notebook (step 7)** is also on hold until more postings accumulate
over the semester - the point is analyzing market-wide patterns (skill demand,
seniority mix), which needs more volume than the current 201 labeled postings to
say anything reliable.

## Language split

Scraping, database, and LLM extraction (build-order steps 1-2) are written in
**Java** (a deliberate learning choice, not a technical necessity). The labeling
CLI, ranking model, and benchmark (steps 3-5) are **Python**, since scikit-learn
has no comparable Java equivalent and a small interactive CLI iterates faster
without a compile step. Both share the same SQLite file.

## Project structure

```
JobHunterTech/          # Java: scraper, database, LLM extraction, (later) agent loop
├── build.gradle
├── src/main/java/com/jobscout/
│   ├── scraper/         # shared infra (HttpFetcher, TargetRegion, relevance patterns) +
│   │   ├── workday/ greenhouse/ lever/ ashby/ smartrecruiters/  # one scraper class per ATS platform
│   ├── extraction/      # Extractor interface + Gemini/Ollama implementations, shared prompt
│   ├── db/              # SQLite schema init + upsert
│   ├── Main.java             # runs all scrapers
│   └── ExtractionMain.java   # runs LLM extraction over unextracted vacancies
└── src/test/java/com/jobscout/
python/
├── labeling/            # terminal CLI for 0/1/2 fit labeling
├── ranking/             # feature engineering, logistic regression baseline, benchmark
├── analysis/            # placeholder for step 7 (market analysis notebook)
└── tests/
data/                    # gitignored, local SQLite file lives here
```

## Setup

Java side:

```bash
cd JobHunterTech
cp ../.env.example ../.env  # fill in your own values -- shared at the repo root
./gradlew test
./gradlew run           # runs all configured scrapers, writes into ../data/job_scout.db
```

Gradle's own daemon needs a JDK it supports as its runtime (JDK 26 was too new for
Gradle 9.2 at the time this was set up) - if `./gradlew` fails with "Unsupported
class file major version", point `JAVA_HOME` at an older JDK (21 works) before
running it.

To run LLM extraction, the default provider is a local Ollama model (free, no rate
limits):

```bash
brew install ollama && brew services start ollama && ollama pull qwen3:8b
```

`./gradlew run` always runs `Main` (the scrapers) - run `ExtractionMain`'s `main()`
directly from your IDE instead. To use the Gemini API instead of Ollama, set
`EXTRACTION_PROVIDER=gemini` in `.env` and fill in `GEMINI_API_KEYS`.

Python side (see [python/README.md](python/README.md) for details):

```bash
cd python
python3 -m venv .venv
source .venv/bin/activate
pip install -e ".[dev]"
python -m labeling.cli      # label vacancies
python -m ranking.baseline  # train + cross-validate the ranking model
python -m ranking.benchmark # compare against LLM-as-judge and cosine similarity
```

The benchmark's LLM-judge and cosine-similarity steps also need a local Ollama
embedding model: `ollama pull nomic-embed-text`.

## Build order

1. Scraper + database - done
2. LLM extraction into structured fields - done
3. Labeling CLI - done (201 labels)
4. Ranking model - logistic regression baseline built and cross-validated
5. Benchmark (trained model vs. LLM-as-judge vs. embedding similarity) - built
6. Daily agent loop - not started, deliberately (see Future work below)
7. Market analysis notebook - not started

Each step builds on the previous one's data - see PROJECT_BRIEF.md before skipping ahead.

## License

MIT - see [LICENSE](LICENSE).
