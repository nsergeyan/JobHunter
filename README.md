# Job-Scout

A personalized job-ranking system for internship/junior Data Science, AI/ML, and
software engineering roles across Europe, built end-to-end: scraping, LLM-based
structured extraction, manual labeling, a trained ranking model, and a benchmark
against two untrained baselines.

Most "AI job agent" projects use an LLM to judge fit on every run and stop there.
The question this project actually investigates: **given a small set of
self-labeled examples, does training a model on them outperform simply asking an
LLM to judge fit, or embedding a stated preference and ranking by similarity?**
That comparison, not the scraping or the LLM call, is the point.

## Pipeline

```
scrape (Java) -> LLM extraction (Java) -> manual labeling (Python) -> ranking model + benchmark (Python)
```

- **Scraping**: ~120 companies across 5 ATS platforms (Workday, Greenhouse, Lever,
  Ashby, SmartRecruiters), each integrated against the platform's real posting API
  rather than a guessed URL slug or scraped HTML (several platforms return a
  convincing `200 OK` for a nonexistent company, which a naive slug-guessing
  approach would silently miss). Scope: internship/junior DS/AI/ML/software roles,
  Europe only (narrowed from Europe+US given how difficult US visa sponsorship has
  become for non-US candidates regardless of a company's nominal policy).
- **Extraction**: each raw posting is parsed into structured fields (skills,
  seniority, salary, language requirement, remote policy) via an LLM call
  constrained to a JSON schema, with two interchangeable providers (local Ollama,
  default; Gemini API) sharing one prompt so results are comparable model-vs-model
  rather than prompt-vs-prompt. A third mode (`gemini-then-ollama`) runs Gemini as
  primary and falls back to the local model per-posting on a Gemini error, so a
  rate limit or transient outage doesn't stall the whole batch.
- **Labeling**: a terminal CLI records a 0/1/2 fit rating (no/maybe/yes) against
  personal preference. 341 postings labeled.
- **Ranking + benchmark**: see Methodology below.

## Methodology

**Problem framing.** This is a small-sample, imbalanced, personalized ranking
problem: 341 labeled examples, ~13% strong positives, and the goal is a ranked
shortlist, not a binary classifier. That framing drives every choice below.

**Target variable.** The 0/1/2 label is collapsed to binary for training (`0` vs.
`{1, 2}`) purely for data efficiency: splitting three ways leaves too few
`2`s to learn from directly, but `{1, 2}` combined gives a workable 56/44 split.
So the `2`s are not simply lost inside that combined class, training applies a
`sample_weight` that counts each strong-fit (`2`) row more than a `1`, nudging
the ranking toward true yeses (see `STRONG_FIT_WEIGHT`). The original 0/1/2 label
is retained and used as the evaluation bar (`2` only) for precision@k, since "is
this worth training on" and "is this good enough to surface" are different bars,
and collapsing them would understate what the shortlist needs to deliver.

**Features.** Multi-hot skill indicators (skills seen ≥3 times, to avoid
one-off noise), one-hot seniority and remote-policy, and TF-IDF over title
unigrams+bigrams (bigrams specifically to keep phrases like "data scientist" or
"machine learning" intact instead of splitting them into two unrelated tokens).
Company name and salary are deliberately excluded: a third of postings share one
company, so company would partly encode "this specific employer" rather than
transferable signal, and salary is populated on <5% of postings.

**Model.** L2-regularized logistic regression. Chosen as the baseline specifically
*because* it's simple: with only ~320 rows and 60-100 engineered features, a
higher-capacity model (LightGBM) would be easy to overfit and hard to justify
without first establishing whether the simple, interpretable baseline already
underperforms. It hasn't been beaten by anything yet.

**Validation.** Stratified 5-fold cross-validation, not a single train/test split.
A single 80/20 split on ~320 rows is highly sensitive to which rows land in which
half; 5-fold rotation averages that variance away and reports it explicitly
(reported as mean ± std across folds) rather than presenting one run's number as
ground truth. Each fold's feature vocabulary (skills, TF-IDF terms) is fit on that
fold's training data only, to avoid leaking test-set vocabulary into training.

**Benchmark baselines.** Two untrained comparisons, run over the same postings:
- *LLM-as-judge*: the same local model scores each posting 0-100 against a
  written preference profile. Initially implemented as a discrete 0/1/2 rating
  matching the label scale, which produced suspiciously flat precision@k across
  k=5/10/20, diagnosed (not assumed) by inspecting the rating distribution, which
  showed 39% of postings tied at the top rating with no way to rank within that
  tie. Switching to a continuous 0-100 score roughly doubled precision@k, with no
  change to the underlying model or prompt intent.
- *Cosine similarity*: the same preference profile and every posting, embedded via
  `nomic-embed-text`, ranked by cosine similarity. No training data, no labels, no
  LLM reasoning, a pure semantic-similarity floor to compare the trained model
  against.

**Evaluation metric.** Precision@k, not accuracy or a single-threshold F1. The
downstream use case is a ranked shortlist a human reviews, so the metric that
matters is "how good are the top k results," not "what fraction of all 341
postings did the model classify correctly" (a metric that would be dominated by
the easy, unambiguous negatives). All three methods are scored against the same
322 English-only postings (19 postings requiring a non-English language are
excluded by a hard rule-based filter, not left to any model to infer), and the
trained model's scores are its **out-of-fold** predictions, so every method is
judged on postings it never trained on.

## Results

> Note: the table below is from the 201-label run. Labeling has since grown to
> 341 (322 English-only) and training now weights strong-fit rows. The trained
> model's out-of-fold precision@k on the current set is 0.60 / 0.60 / 0.45; the
> LLM-judge and cosine rows will be refreshed on the same set on the next full
> `ranking.benchmark` run before this section's conclusions are updated.

| | precision@5 | precision@10 | precision@20 |
|---|---|---|---|
| Logistic regression (out-of-fold) | 0.60 | 0.40 | 0.35 |
| LLM-as-judge (0-100 score) | 0.40 | 0.40 | 0.40 |
| Cosine similarity | 0.40 | 0.50 | 0.50 |

**Cosine similarity, no training data at all, matched or beat the trained model
past the top few results.** With only ~30 strong-positive labels to learn from,
a well-scoped heuristic is a genuinely competitive baseline, not a strawman. This
is the project's central empirical finding: training a model on scarce labeled
data isn't automatically better than a carefully written preference embedding, and
that's worth knowing *before* investing further in the trained approach rather
than after.

Logistic regression is sharpest at the very top of the ranking (k=5) and thins out
faster than cosine similarity as k grows, consistent with training on a coarser
signal (interested vs. not) than it's being evaluated against (strong yes only),
and with a small-sample model's confidence being concentrated in its clearest
cases.

## Future work

**Daily agent loop is intentionally not built yet.** Precision@k in the 0.35-0.60
range on ~30 positive examples means a top-5 shortlist can plausibly contain 2-3
misses: acceptable for a ranked list a human skims, not for an unattended
notifier acting on it. This needs substantially more labeled data, and likely an
ensemble of the three scoring methods rather than a single one, before it's worth
automating.

**Market analysis notebook** (skill demand, seniority mix across the scraped
companies) is on hold until more postings accumulate: 341 labeled postings isn't
enough volume to say anything statistically reliable about market-wide patterns.

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
├── analysis/            # placeholder for the market analysis notebook
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
`EXTRACTION_PROVIDER=gemini` in `.env` and fill in `GEMINI_API_KEYS`. To run Gemini
as primary with an automatic per-posting fallback to Ollama on error, set
`EXTRACTION_PROVIDER=gemini-then-ollama` instead (needs both Ollama running and
`GEMINI_API_KEYS` set).

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

## License

MIT - see [LICENSE](LICENSE).
