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
- **The finding, and the correction that matters more.** With hand-crafted features only, the
  trained model *lost* to a plain embedding-similarity baseline. I diagnosed why (the baseline
  carried a semantic signal my model lacked), fed that signal in as a feature, and precision@5
  jumped from 0.60 to 0.80. Then I rebuilt the measurement, and **most of that result did not
  survive it**:

  | 545 distinct jobs, 79 rated "yes" | ndcg@10, 95% interval |
  |---|---|
  | Logistic regression **+ description** | 0.77 [0.45-0.97] |
  | Cosine similarity (untrained baseline) | 0.75 [0.46-0.94] |
  | Logistic regression, hand-crafted only | 0.72 [0.39-0.96] |
  | Logistic regression **+ semantic feature** | 0.72 [0.40-0.96] |

  Nothing separates from anything else, and the ordering itself is unstable: changing 6.6% of
  the labels flips "+ description beats hand-crafted" from 11% of resamples to 90%. See
  [the instability finding](#the-finding-that-matters-none-of-this-is-stable), which is the
  real result here.
- **The measurement was the real work.** Four defects, each of which had inflated or distorted
  the earlier numbers: the evaluation was not reproducible (unordered SQL rows fed the fold
  shuffle, so two identical runs disagreed); one shuffle is a lottery (precision@5 swings across
  a band 0.36 wide); comparing methods by eyeballing overlapping intervals is the wrong test (a
  **paired bootstrap** on the same resampled postings is); and duplicated jobs leaked between
  training and test folds. The fifth, that the labeled set is small and non-randomly grown, is
  not fixable by better statistics and is the finding above.
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

**Semantic feature.** On top of those, one more: the cosine similarity
between each posting's embedding and my preference profile, min-max scaled on the *training
fold only* (so it stays leakage-free and on the same 0-1 range as the rest, not over- or
under-weighted by regularization). This deliberately hands the model the exact signal the
cosine baseline uses, so it can *combine* semantics with the personal preferences the baseline
can't learn. It was for a time the headline result of this project. It does not hold up: see
[Results](#results).

**Description features.** A capped TF-IDF over the posting body (300 terms, `max_df=0.8` to
strip boilerplate such as benefits lists and equal-opportunity statements). The body was the one
field never used as features, so the model read job *titles* but never what the job actually
involved. Currently the most promising of the three feature experiments, and still not
conclusive, so it ships behind `--description`.

**Model.** L2-regularized multinomial logistic regression (one weight vector per class).
Chosen as the baseline *because* it's simple and interpretable: with ~481 rows and 60-100
engineered features, a higher-capacity model (LightGBM) would be easy to overfit and hard to
justify before establishing how the simple baseline does. Since no feature variant yet
separates from the cosine heuristic (see Results), the extra capacity and lost interpretability
of a tree model plainly isn't warranted at this sample size: there is no gap for it to close
that could be measured if it did.

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

All methods scored on the same **545 distinct jobs, 79 rated "yes"**, out-of-fold for the
trained model. Every number carries a 95% bootstrap interval over resampled postings, so the
uncertainty means the same thing in every row.

"Distinct jobs" rather than postings, because the same job arrives more than once: companies
re-list expired roles under a fresh platform id, and sometimes submit one job twice at once
under consecutive ids. Around a quarter of Magnet.me and SmartRecruiters postings sit in a
duplicate group. Exact duplicates are collapsed to one row, and copies that differ only by city
are kept apart but forced into the same cross-validation fold, so the model is never tested on a
job it has already trained on. See [Duplicates](#duplicates-and-what-they-cost).

### The honest headline

**No method is separated from another at the 95% level.** Every interval overlaps every other.
What the data does support is a consistent *direction*, and the strongest of those is that
reading the job description helps.

| method | precision@10 | ndcg@10 |
|---|---|---|
| Logistic regression + description | **0.62** [0.30-0.90] | **0.77** [0.45-0.97] |
| Cosine similarity (untrained) | 0.55 [0.20-0.90] | 0.75 [0.46-0.94] |
| Logistic regression (hand-crafted) | 0.59 [0.20-0.90] | 0.72 [0.39-0.96] |
| Logistic regression + semantic | 0.59 [0.30-0.90] | 0.72 [0.40-0.96] |

precision@5 is omitted deliberately: its interval spans essentially [0.00-1.00], because it is
computed from five postings. Any ranking of methods by precision@5 at this scale is noise.

### Comparing methods properly

Overlapping intervals do **not** mean two methods are equivalent. Those intervals are dominated
by which postings the sample happened to contain, and every method faces that same luck. The
right test is paired: resample once, score both methods on the *same* postings, and look at the
distribution of the difference.

| comparison | ndcg@5 | ndcg@10 | ndcg@20 |
|---|---|---|---|
| + description **vs** hand-crafted | **90%** | **85%** | **78%** |
| + description **vs** cosine similarity | 52% | 54% | 49% |
| hand-crafted **vs** cosine similarity | 39% | 41% | 41% |
| + semantic **vs** hand-crafted | 37% | 42% | **19%** |

<sub>Share of 2000 paired bootstrap resamples where the first method scored higher. 50% is a
coin flip. None of these clears 95%, so all are directions rather than proofs.</sub>

Read naively, that table says the description features are a clear win at 78-90%. **They are
not, and the next section is the most important result in this project.**

### The finding that matters: none of this is stable

The table above was computed at 545 jobs. Re-running the identical code against the labeled set
as it stood a few hours earlier, before 9 labels were corrected and 27 added, gives the opposite
answer:

| labeled set | "+ description beats hand-crafted", ndcg@5 |
|---|---|
| as of 12:22 (583 labels) | **11%** of resamples |
| + 9 corrected labels | 34% |
| + 27 newly labeled postings (610) | **90%** |

**36 label changes, 6.6% of the dataset, moved the conclusion from "clearly worse" to "clearly
better".** A result that fragile is not a result. It is noise that happens to have a direction
on the day you look.

Two things follow, and both were invisible until this was checked:

**The bootstrap was measuring the wrong uncertainty.** It resamples which postings land in the
*evaluation*, treating the labeled set as fixed. But the labeled set is itself a small, growing
sample, and the variance from *which postings got labeled at all* dwarfs the variance the
bootstrap reports. The intervals above are real, and they are not the whole story.

**The new labels were not randomly chosen.** 24 of those 27 came from the uncertainty sampler,
which deliberately selects postings the model is least sure about, and those are precisely the
cases where two feature sets disagree most. So the additions systematically favour whichever
variant handles boundary cases better. That is the exact bias `ranking/holdout.py` exists to
guard against, appearing within hours of the sampler being switched on.

The honest conclusion at this sample size: **the trained model, the embedding baseline and every
feature variant are indistinguishable, and feature comparisons should not be run again until the
random holdout is large enough to evaluate on.** It currently holds 126 labels with 14 positives.

### Duplicates, and what they cost

Roughly a quarter of Magnet.me and SmartRecruiters postings are duplicates, for two reasons
`external_id` structurally cannot catch. A company re-lists an expired job and the platform
issues a **fresh posting id**, or it submits the same job **twice at once** under consecutive
ids, one tagged with a city and one without. `external_id` catches the same job resurfacing at a
new URL, which is a different problem.

That cost twice over. The model saw a duplicated job several times in training, so it counted
several times. And in cross-validation the copies could land in different folds, letting it
score a test posting it had effectively memorised, which inflates precision@k exactly where it
is read.

It also produced contradictory labels: 10 jobs had been rated inconsistently across 21 rows,
and 9 of the 10 were a July rating against an August one. That is preference drift over five
weeks rather than carelessness, and it sets a ceiling: no ranker can be more consistent with you
than you are with yourself. Resolving them algorithmically was tempting and wrong, since
majority vote decides only 4 of 17 conflicts and every tie-break beyond that invents a label
nobody chose, so `labeling.cli --fix-conflicts` asks instead.

The fix is two-part, because the two duplicate kinds want opposite treatment. Exact duplicates
(same title, company **and** location) collapse to one row. Copies differing only by city stay
as separate rows, since rating a role differently in Paris and London is a preference not an
error, but they are forced into the same cross-validation fold via `StratifiedGroupKFold`,
because their descriptions are near-identical and leak just as badly.

Worth stating plainly: cleaning this up moved every headline number by less than its own noise
band. The leakage was real and worth removing, and it was not what was holding the results back.

### What this replaced, and why it is worth showing

An earlier version of this benchmark, at 509 labels on a **single** cross-validation shuffle,
told a cleaner story: the model lost to cosine similarity on hand-crafted features, then adding
the semantic feature lifted precision@5 from 0.60 to 0.80 and closed the gap. The semantic
feature became the model's single largest coefficient, which seemed to confirm it.

Almost none of that survived better measurement, and the ways it failed are each instructive:

- **The evaluation was not reproducible.** A `SELECT` without `ORDER BY` let SQLite return rows
  in an arbitrary order, which fed both the fold shuffle and every tie-break. Two identical runs
  disagreed.
- **One shuffle is a lottery.** Repeating the cross-validation across 5 shuffles put precision@5
  in a band roughly 0.36 wide. The 0.80 was a friendly draw from it.
- **A large coefficient is not an improvement.** The semantic feature genuinely was the model's
  biggest weight, and the model genuinely did lean on it. It still did not rank better, which is
  the distinction that matters and the one the original write-up missed.
- **precision@k alone was the wrong lens.** It scores a *maybe* exactly like a *no* and cannot
  tell a well-ordered top five from the same five shuffled. NDCG@k, added alongside, is what
  showed the description features moving consistently while precision@k looked flat.

### Why the trained model is still the right tool

Being tied with a similarity heuristic is not the same as being redundant. Two things it does
that a frozen baseline structurally **cannot**:

1. **It learns contradictions.** Its coefficients recovered real preferences from labels alone,
   *toward yes:* `data scientist`, `machine learning`, `ai`, `deep`; *toward no:* `data
   engineer`, `devops`, `thesis`, `C#`. It rejects `data engineer` despite it reading almost
   identically to `data scientist`. Cosine cannot tell them apart at all.
2. **It improves with data.** The cosine baseline is frozen, equally good at 500 labels or
   5,000. The trained model climbs, which is why more labels is the top lever and why a tie
   today is not a tie forever.

It also surfaced something no heuristic would have: with description features on, it had learned
German function words as a signal for "no". That was a real preference being expressed through a
proxy, and it led directly to finding that the language filter was missing postings *written* in
another language while only catching those that *named* a requirement.

## Future work

**More labeled data (top lever).** With ~88 positives, precision@5 still carries a ±0.18
standard deviation, which is wide enough to swallow most feature experiments whole. Every open
question below is really the same question: is the dataset big enough to answer it yet?
Labeling is ongoing, now ordered by model uncertainty so each label buys more than it used to.

**Refresh the LLM-as-judge arm.** The trained, semantic, description and cosine rows are all
current at 574 labels. The LLM-judge row is not: it needs a judging pass over every posting,
roughly one to three hours of local inference, and has not been re-run since 509 labels.

**Settle the description-TF-IDF question.** Currently "promising, not proven": ahead of the
hand-crafted model in 73-79% of paired resamples across all three NDCG cutoffs, which is a real
direction but short of the 95% that would settle it. It flips to on-by-default the moment it
clears that bar, and not before.

**Work out how much data would settle any of this.** Every open question above is really the
same question, and a power analysis would turn "we need more labels" into a number: given the
observed effect sizes, how many labels before a difference this size becomes resolvable? That
is more useful than continuing to label blindly.

**Full embedding vector as features.** The semantic experiment fed the model one *summarized*
number (cosine to the profile), and it did not help. Feeding the raw embedding vector instead
gives it the full semantic space rather than a single projection of it, though with far more
overfitting risk at this sample size. An experiment to measure, not assume, and one that should
now be judged by paired bootstrap rather than a single split.

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
[Results](#results)) and `--time-split`.

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
