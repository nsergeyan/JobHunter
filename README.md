# Job-Scout

**A personalized job-ranking system trained on my own labeled data, plus an honest benchmark of whether that actually beats the LLM approaches everyone else ships.**

Internship / junior Data Science, AI/ML, and software engineering roles across Europe.
Built end to end: web scraping → LLM structured extraction → hand-labeling → a trained
ranking model → a rigorous benchmark against two untrained baselines.

**Stack:** Java 21 / Gradle (scraping, extraction, database) · Python / scikit-learn
(modeling & evaluation) · SQLite · Ollama + Gemini (LLM extraction)

---

## TL;DR

- **Real, self-collected data.** ~665 live postings scraped from 5 applicant-tracking
  platforms (not a downloaded dataset), 610 of them hand-labeled `0/1/2` for personal fit
  (307 no / 215 maybe / 88 yes).
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

  <sub>\*Measured at 509 labels on a **single** cross-validation shuffle. That number is now
  known to be optimistic: repeating the whole procedure across 5 shuffles shows p@5 swinging by
  ±0.18 or worse, so a single run lands anywhere in a wide band. See
  [Round 3](#round-3-separating-signal-from-noise), which is the honest reading of this table.</sub>
- **Measuring the measurement.** A later pass found the evaluation itself was not reproducible
  (unordered SQL rows fed the fold shuffle, so two identical runs disagreed), and that one
  shuffle is a lottery at this sample size. Both are fixed: runs are deterministic, every
  headline number is now reported as a mean ±std across 5 shuffles, and **NDCG@k** joins
  precision@k so the full `0/1/2` scale is actually used. This *lowered* the reported numbers,
  which is the point.
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

The **daily agent loop** (scrape → rank → shortlist) is *deliberately* the last and easiest
step. It's glue over pieces that already work, so it carries little risk and little insight,
which is not where a portfolio should spend its evidence. It now runs end to end from one
command (`python -m orchestrator`), which is where the glue stops being interesting;
unattended scheduling and cover-letter drafting are [future work](#future-work). The
substance is everything before it.

---

## Pipeline

```
scrape (Java) → LLM extraction (Java) → manual labeling (Python) → ranking model + benchmark (Python) → daily digest (Python)
```

- **Scraping.** ~140 companies across 5 ATS platforms (Workday, Greenhouse, Lever, Ashby,
  SmartRecruiters), each integrated against the platform's real posting API rather than a
  guessed URL slug or scraped HTML (several platforms return a convincing `200 OK` for a
  nonexistent company, which a naive slug-guessing approach silently miss-counts). Jobs are
  identified by the platform's **own stable posting id, not their URL**, so a posting whose
  URL drifts over time does not reappear as a duplicate row or double-count in training.
  Scope: internship/junior DS/AI/ML/software roles, Europe only (narrowed from Europe+US
  given how hard US visa sponsorship has become for non-US candidates). The company list
  lives in `config/companies.json` and is read at runtime, so adding a board is not a
  recompile.
- **Scraping, operationally.** Three things that matter more than they sound. Requests are
  paced **per host**, not globally, so the politeness guarantee (no server sees requests
  closer together than the configured delay) holds while the six scrapers run concurrently
  instead of queueing behind each other. Transient failures (429, 5xx, dropped connections)
  retry with exponential backoff and honour `Retry-After`, while a permanent 404 from a stale
  board token fails immediately rather than burning the rate-limit budget. And every company's
  scrape writes a `scrape_runs` row, so a board that changes its JSON shape surfaces in the
  next digest instead of silently disappearing from the results.
- **Extraction.** Each raw posting is parsed into structured fields (skills, seniority,
  salary, language requirement, remote policy) via an LLM call constrained to a JSON schema,
  with two interchangeable providers (local Ollama, default; Gemini API) sharing one prompt
  so results compare model-vs-model rather than prompt-vs-prompt. A third mode
  (`gemini-then-ollama`) falls back to the local model per-posting on a Gemini error, so a
  rate limit doesn't stall the batch.
- **Labeling.** A terminal CLI records a `0/1/2` fit rating (no/maybe/yes) against personal
  preference. 610 of the 665 scraped postings labeled so far, and labeling is ongoing.
  Postings are offered **most-uncertain-first** (highest entropy over the predicted
  no/maybe/yes distribution), since attention is the scarce resource and a label on a posting
  the model already scores confidently teaches it little. That biases the labeled set toward
  the model's blind spots, so a fixed 20% **holdout**, chosen by hashing the vacancy id, is
  never offered by the uncertainty sampler and only ever in random order, preserving an
  unbiased sample to evaluate on.
- **Ranking + benchmark.** See [Methodology](#methodology) and [Results](#results).
- **Daily loop.** `python -m orchestrator` runs the whole chain in one command and writes a
  dated markdown shortlist. The digest ranks only postings not yet labeled, so labeling
  doubles as dismissal and every dismissal grows the training set. Two view filters
  (seniority, location) narrow what's displayed without touching the model: it still trains
  on every labeled posting and scores each independently, so they never reorder the ranking.
  Postings first seen since the previous digest carry a **NEW** badge (`--new-only` shows just
  those), postings that have vanished from their board are excluded, and a scraper-health
  footer reports any company whose most recent fetch failed.

---

## Methodology

**Problem framing.** This is a small-sample, imbalanced, personalized *ranking* problem:
610 labeled examples, ~14% strong positives, and the goal is a ranked shortlist, not a
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
80/20 split on ~574 rows is highly sensitive to which rows land where; 5-fold rotation
averages that variance and reports it explicitly (mean ± std across folds). Each fold's
feature vocabulary (skills, TF-IDF terms) is fit on that fold's training data only, to avoid
leaking test-set vocabulary into training.

**Validation, take two: repeating the whole thing.** 5-fold rotation controls for *which rows
land in which fold*, but not for *which shuffle produced the folds*. With ~88 positives, one
shuffle is a lottery: moving a single posting changes p@5 by 0.20. So the entire
cross-validation is now repeated under 5 different shuffles and reported as a mean ± std
across them. Every headline number below is that mean. The single-shuffle figures this
replaced were meaningfully more flattering, which is exactly why the change was worth making.

**Reproducibility (a bug worth naming).** Two identical runs used to disagree. `SELECT`
without `ORDER BY` gives SQLite no obligation to return rows in a stable order, and that
arbitrary order fed both the fold shuffle and every tie-break in the final ranking. Row order
is now pinned and ties broken stably, so a re-run reproduces exactly. Any A/B comparison made
before that fix was partly measuring luck.

**Time-based validation.** Cross-validation shuffles labels together and assumes they are
interchangeable. They are not, quite: they arrive over time and taste drifts. A second
evaluation trains on the labels made first and tests on the ones made later, which is the
closest thing here to what the digest actually does. Caveat stated in the code: `labeled_at`
records when a posting was *rated*, not when it appeared, and rating order was random until
uncertainty sampling was introduced, so today this is close to a random split.

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
unambiguous negatives). Methods are scored on the same postings (those that *name* a
non-English requirement are removed by a hard rule-based filter, not left to a model to infer),
and the trained model's scores are its **out-of-fold** predictions, so every method is judged
only on postings it never trained on.

**Language, and why it is two filters rather than one.** A posting that *names* a Dutch
requirement is a role you cannot take, so it is dropped everywhere, training included. A
posting merely *written* in German is a different matter: the ad is German but the working
language is frequently English, which is routine at Bosch research and certain at Palantir. The
labels settled it. Of the postings caught by written-language detection, 28 were rated no but 8
were maybe and 3 were **yes**, so dropping them outright would contradict ratings already
given. Written language is therefore a *view* filter: hidden from the digest by default,
visible with `--all-languages` and tagged with a language code, and always kept in training,
where those 11 positive labels are real preference signal. Detection counts function words,
which prose cannot avoid and which are not borrowed the way technical nouns are.

**Second metric: NDCG@k.** Precision@k has two blind spots. It scores a *maybe* exactly like a
*no*, throwing away the middle of the very ordinal scale the model was built to predict, and
it cannot distinguish five yeses in the right order from the same five shuffled. NDCG@k uses
the `0/1/2` label directly as a gain, discounts by rank position, and normalises against the
best achievable ordering of the same labels, so 1.0 means "could not have ranked these
better". Gains are linear rather than the usual `2^rel - 1`, which on a three-point scale
would mostly assert that one yes is worth three maybes, a claim about preference nobody here
has made. Precision@k remains the headline so earlier results stay comparable.

---

## Results

Three rounds. Rounds 1 and 2 compare the trained model against the two untrained baselines and
were measured at **509 labels, 481 English-only, on a single cross-validation shuffle**. Round 3
re-examines the measurement itself and is the honest reading of the two tables above it.

> **Read rounds 1 and 2 as directional.** They predate the reproducibility fix and the
> repeated-shuffle protocol described in [Methodology](#methodology), so their exact decimals
> are not reproducible. The *ordering* of the three methods is the durable finding. Re-running
> the full three-way benchmark at the current label count is
> [pending](#future-work), because the cosine and LLM-judge arms need an embedding and judging
> pass over every posting.

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

### Round 3: separating signal from noise

The rounds above each rest on one cross-validation shuffle. Round 3 asks how much of that
survives repetition. Same model, same features, but the whole procedure repeated under 5
shuffles and reported as mean ± std, at the current **610 labels, 574 English-only**:

| method (5 shuffles, out-of-fold) | p@5 | p@10 | p@20 | ndcg@5 | ndcg@10 | ndcg@20 |
|---|---|---|---|---|---|---|
| Logistic regression (hand-crafted features) | 0.60 ±0.18 | 0.62 ±0.07 | 0.51 ±0.02 | 0.71 ±0.21 | 0.74 ±0.12 | 0.71 ±0.05 |
| ... **+ description TF-IDF** | 0.68 ±0.16 | 0.66 ±0.10 | 0.50 ±0.03 | 0.76 ±0.19 | 0.78 ±0.12 | 0.72 ±0.06 |

**The headline finding is about the error bars, not the means.** p@5 carries a standard
deviation of ±0.18. A single run can land anywhere from roughly 0.42 to 0.78 with nothing
changing but the shuffle, which is why the single-shuffle 0.80 reported in Round 2 should not
be read as a decimal. Every future claim of the form "X beats Y" has to clear that band or it
is noise. p@20 and ndcg@20 are far steadier (±0.02 to ±0.06), simply because ranking 20 of 574
is a less twitchy question than ranking 5.

**A third feature experiment: the description text.** The model read job *titles* but never the
posting body, the one field never used as features. Adding a capped TF-IDF over the description
(300 features, `max_df=0.8` to strip boilerplate like benefits lists and equal-opportunity
statements) is the cheapest untried idea, so it was measured rather than assumed. At ~535
labels it was a dead wash. At 610 it is ahead on five of six metrics, but every gap still sits
inside one standard deviation, so the honest verdict is **"promising, not proven"**. It ships
behind `--description`, off by default, precisely because the bar is "clears the noise band",
not "has a bigger mean". It is worth re-measuring as labels accumulate: the direction moving
consistently as the dataset grew by 75 labels is what you would expect from a real but
currently under-powered signal.

**Time-based split.** Training on the 459 earliest labels and testing on the 115 rated after
2026-08-13 gives p@5 0.80, p@10 0.80, ndcg@5 0.85. Encouraging, and closer to how the digest is
actually used, but it is a *single* split and therefore carries the same noise caveat as
anything else here.

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

**More labeled data (top lever).** With ~88 positives, precision@5 still carries a ±0.18
standard deviation, which is wide enough to swallow most feature experiments whole. Every open
question below is really the same question: is the dataset big enough to answer it yet?
Labeling is ongoing, now ordered by model uncertainty so each label buys more than it used to.

**Re-run the three-way benchmark.** Rounds 1 and 2 date from 509 labels and a single shuffle.
Re-running the trained model against LLM-as-judge and cosine similarity under the repeated
shuffle protocol is the direct next step, deferred only because both baselines need a full
embedding and judging pass over every posting.

**Settle the description-TF-IDF question.** Currently "promising, not proven" (Round 3): ahead
on five of six metrics, all inside one standard deviation. It flips to on-by-default the moment
it clears the noise band, and not before.

**Full embedding vector as features.** Round 2 added one *summarized* semantic number (cosine
to the profile). Feeding the model the raw embedding vector gives it the full semantic space to
combine with personal preferences, more power, though more overfitting risk at this sample
size, so it's an experiment to measure, not assume.

**LightGBM, once data allows.** A tree model only earns the swap from the interpretable
logistic regression if it actually beats it on the benchmark, more justified as the dataset
grows.

**Finishing the daily loop.** The pipeline runs end to end and now reports its own health (see
[Pipeline](#pipeline)); what's left is cover-letter drafting for the top postings, unattended
scheduling (launchd rather than cron on macOS, which App Nap otherwise starves), and delivery
beyond a local file. Intentionally last: it's glue, not data science, and low-signal for a
portfolio.

**Closing detection for the remaining sources.** Postings that vanish from a board are marked
closed for Greenhouse, Ashby and Lever, which walk a company's whole listing. Workday and
SmartRecruiters filter by title while paging and Magnet.me works from a cross-company sitemap,
so absence there is not evidence of closure and nothing is closed. Fixing that means having
those scrapers also return the unfiltered id set.

**Market-analysis notebook** (skill demand, seniority mix across scraped companies) is on
hold until more postings accumulate; ~665 isn't enough to say anything statistically
reliable about market-wide patterns. The `closed_at` timestamps now accumulating will make
"how long does a posting stay open" answerable alongside it.

---

## Project structure

```
config/companies.json    # the ~140 scraped companies, read at runtime (no recompile)
JobHunterTech/           # Java: scraper, database, LLM extraction
├── build.gradle
├── src/main/java/com/jobscout/
│   ├── scraper/         # shared infra + one scraper package per ATS platform
│   │   ├── HostRateLimiter.java   # per-host pacing, so parallel scrapers stay polite
│   │   ├── RetryPolicy.java       # which failures are transient, and how long to wait
│   │   ├── CompanyRegistry.java   # loads companies.json
│   │   ├── CompanyScrape.java     # per-company bookkeeping + closing vanished postings
│   │   ├── FilterVersion.java     # lets a filter change re-open past rejections
│   │   └── workday/ greenhouse/ lever/ ashby/ smartrecruiters/ magnetme/
│   ├── extraction/      # Extractor interface + Gemini/Ollama implementations, shared prompt
│   ├── db/              # SQLite schema init + upsert (identity keyed on stable posting id)
│   ├── Main.java             # runs the scrapers in parallel
│   └── ExtractionMain.java   # runs LLM extraction over unextracted vacancies
└── src/test/java/com/jobscout/
python/
├── orchestrator.py      # daily pipeline: scrape -> extract -> rank -> digest
├── labeling/            # terminal CLI for 0/1/2 fit labeling
├── ranking/             # feature engineering, model, digest, benchmark
│   ├── baseline.py      # features, ordinal model, repeated CV, precision@k + NDCG@k
│   ├── active.py        # uncertainty ordering for the labeling queue
│   ├── holdout.py       # the 20% reserved for unbiased evaluation
│   ├── filters.py       # hard constraints + view filters, incl. language detection
│   └── digest.py        # ranked shortlist, NEW badges, scraper-health footer
├── analysis/            # placeholder for the market-analysis notebook
└── tests/
data/                    # gitignored: SQLite file and dated digests live here
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

Scrapers run concurrently by default. Pacing is enforced per host inside the shared fetcher,
so overlapping scrapers never make one server see requests closer together than
`SCRAPER_MIN_DELAY_SECONDS`. Two knobs help when debugging: `SCRAPER_PARALLELISM=1` runs them
one at a time so the logs stop interleaving, and `SCRAPER_SOURCES=Lever,Ashby` limits the run
to named platforms. Companies are edited in `config/companies.json`, not in Java.

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
python -m labeling.cli                # label vacancies, most-uncertain-first
python -m labeling.cli --order random # uniformly random instead (safe for evaluation)
python -m ranking.baseline            # cross-validate the model, precision@k + NDCG@k across 5 shuffles
python -m ranking.baseline --compare  # with vs without the description features
python -m ranking.digest              # rank unrated postings, print the top-k shortlist
python -m ranking.digest --new-only   # only postings first seen since the last digest
python -m ranking.benchmark           # trained model vs LLM-judge vs cosine (add --skip-judge to skip the slow judge)
python -m orchestrator                # the whole pipeline: scrape -> extract -> rank -> digest
```

`ranking.baseline` also takes `--semantic` (the embedding feature, needs Ollama),
`--description` (TF-IDF over the posting body, off by default, see
[Round 3](#round-3-separating-signal-from-noise)) and `--time-split`.

`orchestrator.py` drives the two Java stages through Gradle and then ranks, so
one command goes from "nothing scraped today" to a shortlist. It continues past a
failed stage and reports what broke at the end, since the database is idempotent
and a partial scrape is still useful. Extraction runs at roughly 20 seconds per
posting on local Ollama, so expect a fresh scrape's extraction to take a while.
See [python/README.md](python/README.md) for the digest's filtering options.

The benchmark's LLM-judge and cosine steps also need a local embedding model:
`ollama pull nomic-embed-text`.

---

## License

MIT, see [LICENSE](LICENSE).
