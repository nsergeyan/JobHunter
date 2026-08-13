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
- **An honest result** (509 labeled, 481 English-only):

  | method | precision@5 | precision@10 | precision@20 |
  |---|---|---|---|
  | Cosine similarity (untrained) | 0.60 | **0.60** | **0.50** |
  | Logistic regression (trained, out-of-fold) | 0.60 | 0.50 | 0.45 |
  | LLM-as-judge | 0.40 | 0.30 | 0.40 |

  On scarce, personalized data the trained model **does not** beat a simple similarity
  baseline. Reporting that plainly, instead of cherry-picking a win, is the point.
- **But the model learned something real.** Its weights recovered my actual preferences
  with nothing but `0/1/2` labels — *toward yes:* `data scientist`, `machine learning`,
  `Python`, `LLMs`; *toward no:* `data engineer`, `devops`, `thesis`, `C#`. It taught
  itself the line I actually draw (data *scientist* yes, data *engineer* no), which a
  similarity score cannot.

> The numbers are a **band, not decimals**: with ~64 positives across 5 folds, precision@k
> is coarse and noisy (per-fold p@5 = 0.56 ± 0.15). Full reliability figures in
> [Results](#results).

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

**Model.** L2-regularized multinomial logistic regression (one weight vector per class).
Chosen as the baseline *because* it's simple and interpretable: with ~481 rows and 60-100
engineered features, a higher-capacity model (LightGBM) would be easy to overfit and hard to
justify before establishing whether the simple baseline already underperforms. It does not
underperform a heuristic here (see Results), so the added complexity still isn't warranted.

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

All three methods scored on the **same 481 English-only postings**, out-of-fold for the
trained model. (This is the first run where all three share one dataset; earlier numbers
mixed label sets and shouldn't be compared.)

**precision@k**

| method | p@5 | p@10 | p@20 |
|---|---|---|---|
| Cosine similarity (untrained) | 0.60 | **0.60** | **0.50** |
| Logistic regression (out-of-fold) | 0.60 | 0.50 | 0.45 |
| LLM-as-judge | 0.40 | 0.30 | 0.40 |

**reliability of the trained model (per-fold, mean ± std)**

| | p@5 | p@10 | p@20 |
|---|---|---|---|
| Logistic regression | 0.56 ± 0.15 | 0.44 ± 0.14 | 0.34 ± 0.04 |

Two honest reads:

1. **At the very top (k=5), the model and cosine tie inside the noise.** The ±0.15 spread on
   p@5 (fold-to-fold swing ~0.41-0.71) is larger than the gap between methods, so any
   "winner" claim at k=5 would be over-reading.
2. **On longer lists (k=10, 20), cosine wins for real.** There the model's variance is tight
   (p@20 = 0.34 ± 0.04) and cosine (0.50) sits well outside it. For a top-10-or-20 shortlist,
   the actual use case, plain semantic similarity is currently the better ranker.

The trained model still has genuine signal, p@20 is ~2.5× the 13% base rate, and it is
**interpretable**. Its learned coefficients recovered my real preferences:

- *toward yes:* `data scientist`, `machine learning`, `learning engineer`, `scientist`,
  `Python`, `Large Language Models`, `ai deployment`
- *toward no:* `data engineer`, `devops`, `developer`, `thesis` / `master thesis`, `C#`,
  `Kotlin`, `TypeScript`

That's a real, inspectable finding a similarity score can't produce: the model taught itself
the exact distinction I draw between data *scientist* (yes) and data *engineer* (no).

**Caveats, stated plainly:**
- ~64 positives is thin; more labeled yeses would shrink the variance and could change the
  ordering.
- A couple of learned coefficients (`Scala`, `title:risk` toward yes) are almost certainly
  small-sample artifacts, not real preferences.
- The LLM-judge trails throughout; the continuous score ranks better than the discrete one,
  but it still underperforms both other methods.

**Why cosine wins, and what's next.** Cosine's advantage is structural: "fit" lives in the
*meaning* of a posting, which embeddings encode directly, while the trained model sees only
hand-engineered features and never the semantic signal. The experiment this benchmark
motivates is to **fold the semantic signal into the model**, adding the embedding (or the
cosine score itself) as features to the logistic regression, combining "what I've labeled"
with "what this posting means." A combined model that beats cosine would be an *earned* win
that keeps interpretability. That is the top modeling priority (see Future work).

---

## Future work

**Fold semantic signal into the trained model (top priority).** The benchmark points
straight here: add embedding / cosine-similarity features to the logistic regression, or a
small ensemble of the three scorers, and re-benchmark. The bar is clear and honest, beat the
cosine baseline it currently loses to.

**More labeled data.** With ~64 positives, per-fold precision@k carries real variance. More
labels would both steady the metric and give a trained model more room to separate itself
from a heuristic.

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
python -m labeling.cli      # label vacancies
python -m ranking.baseline  # train + cross-validate the ranking model (per-fold detail)
python -m ranking.digest    # rank unrated postings, print the top-k shortlist
python -m ranking.benchmark # compare trained model vs LLM-judge vs cosine similarity
```

The benchmark's LLM-judge and cosine steps also need a local embedding model:
`ollama pull nomic-embed-text`.

---

## License

MIT, see [LICENSE](LICENSE).
