# python/

Labeling CLI, ranking model, and benchmark - see `../README.md` for the full
methodology. LLM extraction is Java (`../JobHunterTech`); labeling was originally
planned as Java too but moved to Python on 2026-07-21.

Reads/writes the same SQLite file the Java side uses: `../data/job_scout.db`.

## Modules

- **`labeling/`** - terminal CLI that shows one vacancy at a time and records a
  0/1/2 fit rating per keypress (step 3). `python -m labeling.cli`. See
  [Labeling order](#labeling-order) for why the queue is not random by default.
- **`ranking/`** - feature engineering, logistic regression baseline, and the
  three-way benchmark (steps 4-5):
  - `data.py` - loads labeled vacancies from SQLite.
  - `filters.py` - the two kinds of filter. HARD constraints drop a posting
    everywhere (it names a non-English language requirement). VIEW filters only
    narrow the digest (seniority, location, and whether a posting is *written*
    in another language). Written-language detection counts function words,
    which running prose cannot avoid.
  - `preferences.py` - the fit preference profile shared by the LLM-judge and
    cosine-similarity baselines.
  - `baseline.py` - the logistic regression model: feature engineering
    (multi-hot skills, one-hot seniority/remote policy, TF-IDF title n-grams),
    cross-validation repeated across 5 shuffles, precision@k and NDCG@k, a
    time-based split, and coefficient inspection. `python -m ranking.baseline`.
    Optional features: `--semantic` (embedding cosine), `--description` (TF-IDF
    over the posting body, off by default), `--compare` (both ways side by side).
  - `active.py` - orders the labeling queue by how uncertain the model is about
    each posting, measured as entropy over its predicted no/maybe/yes split.
  - `holdout.py` - the fixed 20% of postings reserved as an unbiased evaluation
    sample, derived by hashing the vacancy id rather than stored in a table.
  - `llm_judge.py` - scores each posting 0-100 against the preference profile
    via a local Ollama call.
  - `embeddings.py` - cosine similarity between the preference profile and
    each posting, via Ollama embeddings.
  - `benchmark.py` - runs all methods and reports precision@k and NDCG@k with
    95% bootstrap intervals, plus paired comparisons. `python -m ranking.benchmark`
    (`--skip-judge` drops the slow LLM-judge pass and finishes in about a minute).
    Two things it is careful about: untrained baselines get intervals too, since
    a fixed ranking is reproducible but not precise, and methods are compared on
    the SAME resampled postings rather than by eyeballing whether two intervals
    overlap, which understates what can be resolved.
  - `digest.py` - ranks unlabeled postings and prints/saves the shortlist
    (step 6), marks new arrivals, and appends a scraper-health footer.
    `python -m ranking.digest`.
- **`orchestrator.py`** - the daily pipeline (step 6): scrape -> extract -> rank
  -> digest, shelling out to the Java stages via Gradle.
  `python -m orchestrator`.
- **`analysis/`** - placeholder for step 7 (market analysis notebook).

## Daily pipeline

```bash
python -m orchestrator                 # scrape -> extract -> rank -> digest
python -m orchestrator --digest-only   # re-rank what's already stored (fast)
python -m ranking.digest               # the ranking half on its own
```

The digest ranks postings that are **unlabeled**, still open, and seen in a scrape
within the last `--days` (default 14), then applies the seniority and location
views from `ranking/preferences.py` (currently internships in the Netherlands).
Labeling a posting is also how you dismiss it: rated postings never appear again,
and the rating feeds the model that does the ranking.

Note what `--days` actually filters on. `scraped_at` is refreshed every time a
posting is seen still listed, so it answers "is this still open", not "is this
new". The column that answers newness is `first_seen`, written once and never
updated, and it drives the **NEW** badge instead. A posting is new if it was
first seen on or after the date of your previous saved digest, so skipping a few
days marks that whole stretch as new rather than only yesterday.

```bash
python -m ranking.digest --all-seniority          # ignore the seniority filter
python -m ranking.digest --all-locations          # ignore the location filter
python -m ranking.digest --seniority internship,junior
python -m ranking.digest --location berlin,munich
python -m ranking.digest --days 0 -k 20           # no time limit, top 20
python -m ranking.digest --new-only               # only postings new since the last digest
```

All three views are **display** filters: the model trains on every labeled
posting regardless, and scores each posting independently, so narrowing them
changes what you see but never the order. Top-k is applied after them, so `-k 10`
yields ten postings that match, not ten postings of which some match.

The third view is language. A posting that *names* a non-English requirement is a
hard drop, since it is a role you cannot take. A posting merely *written* in
German usually is not: the ad is German but the working language is often
English. Of the postings this catches, 28 were rated no, but 8 maybe and 3 yes,
so they stay in training and are only hidden from the digest.

```bash
python -m ranking.digest --all-languages   # show them, tagged `de`, `nl`, `fr`
```

Location is free text and differs per platform (`Amsterdam, NL`,
`Veldhoven, Netherlands`, `ACT (Amsterdam - Acanthus)`, bare `Eindhoven`), so
matching is on **tokens, not substrings**: a substring test for `nl` would also
match `Finland`. `NETHERLANDS_LOCATION_TERMS` in `ranking/filters.py` holds the
country tokens plus the city names needed for postings that name no country.

Each run writes `../data/digests/YYYY-MM-DD.md` (`--no-save` to skip), so a long
pipeline run leaves something behind. Those saved files are also what the NEW
badge measures against, so `--no-save` leaves the boundary where it was.

The digest ends with a scraper-health line: how many company scrapes succeeded,
and the error for any whose most recent attempt failed. Scrapers fail softly by
design (one bad board should not abort the run), which without this would mean a
company silently disappearing from your results for weeks.

## Labeling order

Labeling is the bottleneck: the model's ceiling is set by how many postings are
rated, and rating one costs real attention. By default the CLI offers the
postings the model is **least sure about** first, measured as the entropy of its
predicted no/maybe/yes distribution. A label on a posting it already scores
confidently mostly confirms what it knew.

The cost is that such labels are no longer a random sample of postings, so they
cannot honestly double as a test set: precision@k measured on them would describe
how hard the chosen postings were, not how good the shortlist is. So a fixed 20%
holdout (`ranking/holdout.py`, membership hashed from the vacancy id) is never
offered by the uncertainty sampler.

```bash
python -m labeling.cli                  # most uncertain first, holdout excluded
python -m labeling.cli --order random   # uniformly random, safe for evaluation
python -m labeling.cli --order holdout  # top up the evaluation sample
python -m labeling.cli --fix-conflicts  # re-rate jobs you rated inconsistently
```

## Duplicates, and why they matter

The same job reaches you more than once, for two reasons neither `external_id`
can catch. A company re-lists an expired job and the platform issues a **fresh
posting id**, or it submits the same job **twice at once** under consecutive ids
(one tagged with a city, one without). Roughly a quarter of Magnet.me and
SmartRecruiters postings sit in a duplicate group.

Two consequences. The model sees a duplicated job several times in training, so
it counts several times. And in cross-validation the copies can land in different
folds, letting the model score a test posting it effectively memorised, which
inflates precision@k precisely where you read it.

`--fix-conflicts` handles the labeling half: it shows one screen per job that was
duplicated **and rated inconsistently**, along with what you said last time, and
writes your answer to every copy. Only exact duplicates appear, same title,
company AND location. The same role in two cities is two jobs, and rating them
differently is a preference rather than a mistake.

Two runtime notes. Extraction is the slow stage, roughly 20 seconds per posting
through local Ollama, so a few hundred fresh postings takes an hour or more;
stages stream output live so you can watch progress. And Gradle 9.2 cannot run
on a JDK newer than it supports, so the orchestrator points the daemon at a
JDK 21 it finds automatically; set `GRADLE_JAVA_HOME` in the root `.env` if
yours lives somewhere unusual.

Environment variables set here pass through to the Java stages, which is how you
scope a run without editing anything:

```bash
SCRAPER_SOURCES=Lever,Ashby python -m orchestrator --skip-extract
SCRAPER_PARALLELISM=1 python -m orchestrator        # sequential, readable logs
```

## Setup

```bash
cd python
python3 -m venv .venv
source .venv/bin/activate
pip install -e ".[dev]"
```

`ranking/llm_judge.py` and `ranking/embeddings.py` call a local Ollama server:

```bash
ollama pull qwen3:8b            # same model the Java extraction step uses
ollama pull nomic-embed-text    # embedding model for cosine similarity
ollama serve                    # if not already running
```
