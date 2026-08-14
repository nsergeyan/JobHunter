# Job-Scout

**A personalized job-ranking system trained on my own labeled data, plus an honest benchmark of whether that actually beats the LLM approaches everyone else ships.**

Internship / junior Data Science, AI/ML, and software engineering roles across Europe.
Built end to end: web scraping → LLM structured extraction → hand-labeling → a trained
ranking model → a rigorous benchmark against two untrained baselines.

**Stack:** Java 21 / Gradle (scraping, extraction, database) · Python / scikit-learn
(modeling & evaluation) · SQLite · Ollama + Gemini (LLM extraction)

---

## TL;DR

- **Real, self-collected data.** ~500 live postings scraped from 5 applicant-tracking
  platforms (not a downloaded dataset), each hand-labeled `0/1/2` for personal fit.
- **A real experiment, not a demo.** Does a model *trained* on those labels beat (a) an
  LLM asked to judge fit, and (b) ranking by embedding similarity? Measured with
  **precision@k on out-of-fold predictions**, all three on the same set.
- **The finding, as a research arc.** With hand-crafted features only, the trained model
  *lost* to a plain embedding-similarity baseline. I diagnosed why (the baseline carried a
  semantic signal my model lacked), fed that signal in as a feature, and the model went from
  losing to a **wash**, making that feature its single most important input:

  | 509 labeled, 481 English-only | p@5 | p@10 | p@20 |
  |---|---|---|---|
  | Logistic regression **+ semantic feature** (trained) | 0.80\* | 0.50 | 0.50 |
  | Cosine similarity (untrained baseline) | 0.60 | 0.60 | 0.50 |
  | Logistic regression, hand-crafted features only | 0.60 | 0.50 | 0.45 |
  | LLM-as-judge | 0.40 | 0.30 | 0.40 |

  <sub>\*p@5 is noisy at this sample size, read the table as a band, not decimals. Details in [Results](#results).</sub>
- **Why the trained model is the one that matters.** The cosine baseline ranks by similarity
  to a profile I wrote by hand, so it can never learn from my actual decisions. The trained
  model did: it learned to *reject* `data engineer` despite reading almost identically to
  `data scientist` (yes), a distinction a similarity score structurally can't make, and one
  that only sharpens as I label more data.

---

## Scope: what this project is (and isn't)

This is a **data-science project**. The effort, and the interesting part, is the modeling
and evaluation: turning messy real postings into a labeled dataset, engineering features,
picking a target and a metric that match the real use case, and honestly comparing three
approaches on held-out data.

The **daily agent loop** (scrape nightly → rank → send me the top 10) is *deliberately* the
last and easiest step. It's glue over pieces that already work, so it carries little risk
and little insight, which is not where a portfolio should spend its evidence. The ranking
half already exists (a digest that scores and prints the top *unrated* postings);
unattended automation is [future work](#future-work). The substance is everything before it.

---

## Pipeline

```
scrape (Java) → LLM extraction (Java) → manual labeling (Python) → ranking model + benchmark (Python)
```

- **Scraping.** ~140 companies across 5 ATS platforms (Workday, Greenhouse, Lever, Ashby,
  SmartRecruiters), each integrated against the platform's real posting API rather than a
  guessed URL slug or scraped HTML (several platforms return a convincing `200 OK` for a
  nonexistent company, which a naive slug-guessing approach silently miss-counts). Jobs are
  identified by the platform's **own stable posting id, not their URL**, so a posting whose
  URL drifts over time does not reappear as a duplicate row or double-count in training.
  Scope: internship/junior DS/AI/ML/software roles, Europe only (narrowed from Europe+US
  given how hard US visa sponsorship has become for non-US candidates).
- **Extraction.** Each raw posting is parsed into structured fields (skills, seniority,
  salary, language requirement, remote policy) via an LLM call constrained to a JSON schema,
  with two interchangeable providers (local Ollama, default; Gemini API) sharing one prompt
  so results compare model-vs-model rather than prompt-vs-prompt. A third mode
  (`gemini-then-ollama`) falls back to the local model per-posting on a Gemini error, so a
  rate limit doesn't stall the batch.
- **Labeling.** A terminal CLI records a `0/1/2` fit rating (no/maybe/yes) against personal
  preference. 509 postings labeled.
- **Ranking + benchmark.** See [Methodology](#methodology) and [Results](#results).

---

## Methodology

**Problem framing.** This is a small-sample, imbalanced, personalized *ranking* problem:
509 labeled examples, ~13% strong positives, and the goal is a ranked shortlist, not a
binary classifier. That framing drives every choice below.

**Target variable.** The `0/1/2` label is treated as an ordinal scale (no < maybe < yes)
rather than collapsed to binary. A multinomial logistic regression predicts `P(no)`,
`P(maybe)`, `P(yes)` per posting, combined into an *expected rating*,
`0·P(no) + 1·P(maybe) + 2·P(yes)`, which the ranking sorts on. This keeps the training
objective aligned with the evaluation bar: precision@k rewards true yeses (label `2`) at the
top of the list, and an ordinal score can place a confident yes above a maybe. An earlier
binary framing (`0` vs `{1,2}`) put yes and maybe in one class and so could not separate
them where it matters most. Class imbalance is handled with `class_weight="balanced"`.

**Features.** Multi-hot skill indicators (skills seen ≥3 times, to avoid one-off noise),
one-hot seniority and remote-policy, and TF-IDF over title unigrams+bigrams (bigrams to keep
phrases like "data scientist" or "machine learning" intact). Company name and salary are
deliberately excluded: a third of postings share one company, so company would partly encode
"this specific employer" rather than transferable signal, and salary is populated on <5% of
postings.

**Semantic feature (the key experiment).** On top of those, one more: the cosine similarity
between each posting's embedding and my preference profile, min-max scaled on the *training
fold only* (so it stays leakage-free and on the same 0-1 range as the rest, not over- or
under-weighted by regularization). This deliberately hands the model the exact signal the
cosine baseline uses, so it can *combine* semantics with the personal preferences the baseline
can't learn. See [Results](#results).

**Model.** L2-regularized multinomial logistic regression (one weight vector per class).
Chosen as the baseline *because* it's simple and interpretable: with ~481 rows and 60-100
engineered features, a higher-capacity model (LightGBM) would be easy to overfit and hard to
justify before establishing how the simple baseline does. With the semantic feature it is
competitive with the cosine heuristic (see Results), so the extra capacity, and lost
interpretability, of a tree model still isn't warranted at this sample size.

**Validation.** Stratified 5-fold cross-validation, not a single train/test split. A single
80/20 split on ~481 rows is highly sensitive to which rows land where; 5-fold rotation
averages that variance and reports it explicitly (mean ± std across folds). Each fold's
feature vocabulary (skills, TF-IDF terms) is fit on that fold's training data only, to avoid
leaking test-set vocabulary into training.

**Benchmark baselines.** Two untrained comparisons, run over the same postings, held fixed
as reference points (not tuned to compete):
- *LLM-as-judge.* The same local model scores each posting 0-100 against a written
  preference profile. An initial discrete `0/1/2` version produced suspiciously flat
  precision@k, diagnosed (not assumed) by inspecting the rating distribution: 39% of
  postings tied at the top rating with no way to rank within the tie. A continuous 0-100
  score roughly doubled precision@k, same model and prompt intent.
- *Cosine similarity.* The same preference profile and every posting, embedded via
  `nomic-embed-text`, ranked by cosine similarity. No training, no labels, no LLM reasoning:
  a pure semantic-similarity floor to compare the trained model against.

**Evaluation metric.** Precision@k, not accuracy or a single-threshold F1. The use case is a
ranked shortlist a human reviews, so what matters is "how good are the top k," not "what
fraction of all postings were classified correctly" (a metric dominated by the easy,
unambiguous negatives). All three methods are scored on the same 481 English-only postings
(28 postings requiring a non-English language are removed by a hard rule-based filter, not
left to a model to infer), and the trained model's scores are its **out-of-fold**
predictions, so every method is judged only on postings it never trained on.

---

## Results

Two rounds, all methods scored on the **same 481 English-only postings**, out-of-fold for the
trained model.

### Round 1: hand-crafted features only

| method | p@5 | p@10 | p@20 |
|---|---|---|---|
| Cosine similarity (untrained) | 0.60 | **0.60** | **0.50** |
| Logistic regression (hand-crafted features) | 0.60 | 0.50 | 0.45 |
| LLM-as-judge | 0.40 | 0.30 | 0.40 |

The trained model **lost** to a plain embedding-similarity baseline, behind at k=10 and 20,
tied at k=5. Honest starting point: on scarce data, a well-scoped heuristic is a hard floor.

**Diagnosis, not assumption.** Cosine's edge is structural, "fit" lives in the *meaning* of a
posting, which embeddings encode directly, while the model saw only hand-crafted features
(skills, seniority, title n-grams) and never the semantic signal. So the fix was clear: give
the model that signal.

### Round 2: add the semantic feature

Feed the cosine-similarity score into the model as one more leakage-free, scaled feature:

| method | p@5 | p@10 | p@20 |
|---|---|---|---|
| Logistic regression **+ semantic feature** | 0.80\* | 0.50 | 0.50 |
| Cosine similarity (untrained) | 0.60 | 0.60 | 0.50 |
| Logistic regression (hand-crafted only) | 0.60 | 0.50 | 0.45 |

<sub>\*p@5 out-of-fold. Per-fold reliability: p@5 = 0.56 ± 0.23, p@20 = 0.35 ± 0.04. Read the top-k as a band, the p@5 jump is one extra correct posting and sits inside the noise.</sub>

The model closed the gap from losing to a **wash** (now wins p@5, ties p@20, trails only at
p@10), and it made the semantic feature its **single strongest coefficient** (2.69, ahead of
even `data scientist` at 2.58). The model doesn't merely tolerate the signal, it relies on it
more than any hand-crafted feature.

**Honest caveats.** This is a wash, not a decisive win: at this sample size precision@k is
coarse (p@5 swings ±0.23 across folds), so the claim is "caught up," not "beat it." The gain
is modest partly because the semantic feature *overlaps* with the title n-grams the model
already had (useful, but partly redundant). And a couple of learned coefficients (`Scala`,
`title:risk` toward yes) are almost certainly small-sample artifacts.

### Why the trained model is still the right tool

Two things it does that a similarity baseline structurally **cannot**:

1. **It learns my contradictions.** Its coefficients recovered my real preferences from labels
   alone, *toward yes:* `data scientist`, `machine learning`, `Python`, `LLMs`; *toward no:*
   `data engineer`, `devops`, `thesis`, `C#`. It learned to reject `data engineer` even though
   it reads almost identically to `data scientist`. Cosine can't tell them apart.
2. **It improves with data.** The cosine baseline is frozen, equally good at 500 labels or
   5,000. The trained model climbs as I label more, which is why more data (below) is the top
   lever, and why a wash today is expected to become a win.

Given the semantic signal, the trained model is competitive with the baseline it used to lose
to *and* is the only method that learns personal taste and improves over time. Pushing it to a
decisive win is [future work](#future-work).

---

## Future work

**More labeled data (top lever).** With ~64 positives, precision@k is noisy, and the trained
model's personal-preference signal needs more examples to overtake the generic semantic floor.
Highest-leverage next step, and the one most likely to turn today's wash into a clear win.
Labeling is ongoing.

**Full embedding vector as features.** Round 2 added one *summarized* semantic number (cosine
to the profile). Feeding the model the raw embedding vector gives it the full semantic space to
combine with personal preferences, more power, though more overfitting risk at this sample
size, so it's an experiment to measure, not assume.

**LightGBM, once data allows.** A tree model only earns the swap from the interpretable
logistic regression if it actually beats it on the benchmark, more justified as the dataset
grows.

**Daily agent loop (the easy, deferred step).** The ranking digest exists (scores and prints
the top unrated postings); what's left is scheduling it and delivering the top 10 somewhere
(file/email/phone). Intentionally last: it's glue, not data science, and low-signal for a
portfolio.

**Market-analysis notebook** (skill demand, seniority mix across scraped companies) is on
hold until more postings accumulate; ~500 isn't enough to say anything statistically
reliable about market-wide patterns.

---

## Project structure

```
JobHunterTech/          # Java: scraper, database, LLM extraction, (later) agent loop
├── build.gradle
├── src/main/java/com/jobscout/
│   ├── scraper/         # shared infra (HttpFetcher, TargetRegion, relevance patterns) +
│   │   ├── workday/ greenhouse/ lever/ ashby/ smartrecruiters/   # one scraper per ATS platform
│   ├── extraction/      # Extractor interface + Gemini/Ollama implementations, shared prompt
│   ├── db/              # SQLite schema init + upsert (identity keyed on stable posting id)
│   ├── Main.java             # runs all scrapers
│   └── ExtractionMain.java   # runs LLM extraction over unextracted vacancies
└── src/test/java/com/jobscout/
python/
├── labeling/            # terminal CLI for 0/1/2 fit labeling
├── ranking/             # feature engineering, logistic regression baseline, digest, benchmark
├── analysis/            # placeholder for the market-analysis notebook
└── tests/
data/                    # gitignored, local SQLite file lives here
```

---

## Setup

Java side:

```bash
cd JobHunterTech
cp ../.env.example ../.env  # fill in your own values, shared at the repo root
./gradlew test
./gradlew run               # runs all configured scrapers, writes into ../data/job_scout.db
```

Gradle's daemon needs a JDK it supports as its runtime (JDK 26 was too new for Gradle 9.2 at
setup time). If `./gradlew` fails with "Unsupported class file major version", point
`JAVA_HOME` at an older JDK (21 works) before running it.

LLM extraction defaults to a local Ollama model (free, no rate limits):

```bash
brew install ollama && brew services start ollama && ollama pull qwen3:8b
```

`./gradlew run` always runs `Main` (the scrapers); run `ExtractionMain`'s `main()` directly
from your IDE. To use Gemini instead, set `EXTRACTION_PROVIDER=gemini` in `.env` and fill in
`GEMINI_API_KEYS` (or `gemini-then-ollama` for Gemini-primary with per-posting Ollama
fallback).

Python side (see [python/README.md](python/README.md) for details):

```bash
cd python
python3 -m venv .venv
source .venv/bin/activate
pip install -e ".[dev]"
python -m labeling.cli                # label vacancies
python -m ranking.baseline            # cross-validate the model (add --semantic for the embedding feature)
python -m ranking.digest              # rank unrated postings, print the top-k shortlist
python -m ranking.benchmark           # trained model vs LLM-judge vs cosine (add --skip-judge to skip the slow judge)
```

The benchmark's LLM-judge and cosine steps also need a local embedding model:
`ollama pull nomic-embed-text`.

---

## License

MIT, see [LICENSE](LICENSE).
