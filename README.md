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

- **Scraping**: ~140 companies across 5 ATS platforms (Workday, Greenhouse, Lever,
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
problem: 431 labeled examples, ~13% strong positives, and the goal is a ranked
shortlist, not a binary classifier. That framing drives every choice below.

**Target variable.** The 0/1/2 label is treated as an ordinal scale
(no < maybe < yes) rather than collapsed to binary. A multinomial logistic
regression predicts `P(no)`, `P(maybe)`, `P(yes)` per posting, and these are
combined into an *expected rating*, `0·P(no) + 1·P(maybe) + 2·P(yes)`, which is
the value the ranking sorts on. This keeps the training objective aligned with
the evaluation bar: precision@k rewards true yeses (label `2`) at the top of the
list, and an ordinal score can rank a confident yes above a maybe. An earlier
binary framing (`0` vs. `{1, 2}`, with a per-row `sample_weight` bump for `2`s)
put yes and maybe in one class and so could not separate them where it matters
most; switching to the ordinal expected rating lifted out-of-fold precision@5/@10
from 0.60/0.50 to 0.80/0.70. Class imbalance (~13% strong positives) is handled
with `class_weight="balanced"` rather than a hand-tuned per-row weight.

**Features.** Multi-hot skill indicators (skills seen ≥3 times, to avoid
one-off noise), one-hot seniority and remote-policy, and TF-IDF over title
unigrams+bigrams (bigrams specifically to keep phrases like "data scientist" or
"machine learning" intact instead of splitting them into two unrelated tokens).
Company name and salary are deliberately excluded: a third of postings share one
company, so company would partly encode "this specific employer" rather than
transferable signal, and salary is populated on <5% of postings.

**Model.** L2-regularized multinomial logistic regression (one weight vector per
class). Chosen as the baseline specifically *because* it's simple: with only ~405
rows and 60-100 engineered features, a higher-capacity model (LightGBM) would be
easy to overfit and hard to justify without first establishing whether the
simple, interpretable baseline already underperforms.

**Validation.** Stratified 5-fold cross-validation, not a single train/test split.
A single 80/20 split on ~405 rows is highly sensitive to which rows land in which
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
matters is "how good are the top k results," not "what fraction of all 431
postings did the model classify correctly" (a metric that would be dominated by
the easy, unambiguous negatives). All three methods are scored against the same
405 English-only postings (26 postings requiring a non-English language are
excluded by a hard rule-based filter, not left to any model to infer), and the
trained model's scores are its **out-of-fold** predictions, so every method is
judged on postings it never trained on.

## Results

> Note: the trained-model row is current (431 labels, 405 English-only,
> out-of-fold, ordinal expected-rating model). The LLM-judge and cosine rows are
> from the earlier 201-label run and are pending a re-run of `ranking.benchmark`
> on the current set before a head-to-head conclusion is drawn.

| | precision@5 | precision@10 | precision@20 |
|---|---|---|---|
| Logistic regression, ordinal (out-of-fold) | 0.80 | 0.70 | 0.50 |
| LLM-as-judge (0-100 score) *(201-label run)* | 0.40 | 0.40 | 0.40 |
| Cosine similarity *(201-label run)* | 0.40 | 0.50 | 0.50 |

**Aligning the training target with the evaluation bar was the single largest
gain.** Moving from a binary (interested vs. not) target to an ordinal
expected-rating score lifted out-of-fold precision@5/@10 from 0.60/0.50 to
0.80/0.70 on the same postings, with no change to features or validation. The
binary model was optimizing a coarser signal than it was being graded on; scoring
`0·P(no) + 1·P(maybe) + 2·P(yes)` lets it rank a confident yes above a maybe,
which is exactly what precision@k measures.

Two caveats. First, with ~55 strong positives spread across 5 folds, each fold
holds only ~11 yeses, so single-run precision@k still carries meaningful variance;
the improvement is clear, but the exact figures should be read as approximate.
Second, on the earlier 201-label set an untrained cosine-similarity baseline
matched or beat the trained model past the top few results, a reminder that a
well-scoped heuristic is a competitive floor on scarce data. Whether the ordinal
model now clears that floor across all k is exactly what the pending benchmark
re-run will settle.

## Future work

**Daily agent loop is intentionally not built yet.** Even at a top-5 precision of
0.80, a shortlist can still contain a miss, and with ~55 positive examples the
per-run variance is real: acceptable for a ranked list a human skims, not yet for
an unattended notifier acting on it. This needs substantially more labeled data,
and likely an ensemble of the three scoring methods rather than a single one,
before it's worth automating.

**Market analysis notebook** (skill demand, seniority mix across the scraped
companies) is on hold until more postings accumulate: 431 labeled postings isn't
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
