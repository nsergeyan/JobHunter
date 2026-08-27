"""Daily digest (build-order step 6): scan -> rank -> print + save.

This reuses the step-4 ranking model unchanged. It trains the same
FeatureBuilder + multinomial LogisticRegression on ALL labeled vacancies, then
predicts the expected rating (0..2, higher = closer to a "yes") on the UNLABELED
postings -- ones the scraper has added that you have not rated yet, so the model
has never trained on them -- and prints them best-first.

The eligible pool is "unlabeled, and scraped recently enough to plausibly still
be open". Filters then apply, and the difference between the two kinds matters:

  language              A hard constraint. A role requiring fluent Dutch is not
                        a role you can take, so it is dropped unconditionally.

  seniority, location   View preferences, configured in ranking.preferences and
                        overridable per run. The model still TRAINS on every
                        labeled posting regardless, and scores each posting
                        independently, so these narrow what you see without ever
                        changing the order. Top-k is taken AFTER them, so `-k 10`
                        with an internship-in-NL view means ten internships in
                        the Netherlands, not "ten postings, some of which match".

Labeling doubles as dismissal: the digest only shows unlabeled postings, so
rating a posting in the labeling CLI drops it from tomorrow's digest, and every
dismissal grows the training set the ranking runs on.

Later slices will draft cover letters for the top postings. This slice prints to
the terminal and writes a dated markdown copy, so an hour-long pipeline run
leaves something behind.
"""

import argparse
from datetime import date, timedelta
from pathlib import Path

import pandas as pd
from sklearn.linear_model import LogisticRegression

from ranking.baseline import FeatureBuilder, expected_rating
from ranking.data import load_labeled_vacancies, load_scrape_health, load_unlabeled_vacancies
from ranking.filters import (
    NETHERLANDS_LOCATION_TERMS,
    drop_non_english,
    matches_location,
    matches_seniority,
)
from ranking.preferences import LOCATION_INCLUDE, SENIORITY_INCLUDE

DEFAULT_TOP_K = 10
DEFAULT_DAYS = 14
DIGEST_DIR = Path(__file__).resolve().parents[2] / "data" / "digests"

# How far back the NEW marker reaches on a first run, when there is no previous
# digest to measure against. Without it the very first digest would shout NEW at
# every posting in the database, which is noise, not news.
NEW_FALLBACK_DAYS = 7


def previous_digest_date() -> date | None:
    """Date of the most recent saved digest strictly before today, or None.

    Reading it off the saved files is what makes "new since last digest" mean
    what it says even when you skip a few days: miss a week, and the next digest
    marks the whole week's arrivals as new rather than only yesterday's. Today's
    own file is excluded so re-running the digest twice in one day does not
    silently reset the marker to "nothing is new".
    """
    if not DIGEST_DIR.exists():
        return None
    today = date.today()
    dates = []
    for path in DIGEST_DIR.glob("*.md"):
        try:
            parsed = date.fromisoformat(path.stem)
        except ValueError:
            continue  # not a dated digest, e.g. a hand-written note
        if parsed < today:
            dates.append(parsed)
    return max(dates, default=None)


def new_since_boundary() -> date:
    """The cutoff the NEW marker uses: last digest's date, else a short fallback."""
    return previous_digest_date() or date.today() - timedelta(days=NEW_FALLBACK_DAYS)


def mark_new(ranked: pd.DataFrame, boundary: date) -> pd.DataFrame:
    """Add an is_new column: first seen on the board at or after `boundary`.

    first_seen is written once by the scraper and never refreshed, unlike
    scraped_at, so it is the only column that can answer "is this posting new to
    me". Rows with a missing or unparseable first_seen are treated as NOT new --
    conservative on purpose, since a missed NEW badge is cheaper than shouting
    about a posting from two months ago.
    """
    if ranked.empty or "first_seen" not in ranked.columns:
        return ranked.assign(is_new=pd.Series(dtype=bool))
    first_seen = pd.to_datetime(ranked["first_seen"], utc=True, errors="coerce")
    boundary_ts = pd.Timestamp(boundary, tz="UTC")
    return ranked.assign(is_new=(first_seen.notna() & (first_seen >= boundary_ts)))


def _apply_view_filters(
    df: pd.DataFrame,
    seniority_include: set[str] | None,
    location_include: set[str] | None,
) -> pd.DataFrame:
    """Narrow what you SEE, never what the model learned. Applied after scoring,
    which changes nothing about the order (scores are per-posting), but does mean
    top-k counts k postings that actually match.
    """
    if df.empty:
        return df
    keep = pd.Series(True, index=df.index)
    if seniority_include is not None:
        keep &= df["seniority"].apply(lambda s: matches_seniority(s, seniority_include))
    if location_include is not None:
        keep &= df["location"].apply(lambda loc: matches_location(loc, location_include))
    return df[keep].reset_index(drop=True)


def score_unlabeled(
    since_days: int | None = DEFAULT_DAYS,
    seniority_include: set[str] | None = SENIORITY_INCLUDE,
    location_include: set[str] | None = LOCATION_INCLUDE,
) -> tuple[pd.DataFrame, int]:
    """Train on all labeled data, score the eligible unlabeled postings, and
    return them sorted best-first alongside the pool size before the view
    filters were applied (so the caller can report what the filters cost).
    """
    labeled = drop_non_english(load_labeled_vacancies())
    unlabeled = drop_non_english(load_unlabeled_vacancies(since_days))
    pool_size = len(unlabeled)

    if unlabeled.empty:
        return unlabeled.assign(score=pd.Series(dtype=float)), pool_size

    builder = FeatureBuilder().fit(labeled)
    model = LogisticRegression(max_iter=1000, class_weight="balanced")
    model.fit(builder.transform(labeled), labeled["label"].to_numpy())

    scores = expected_rating(model.predict_proba(builder.transform(unlabeled)))
    ranked = unlabeled.assign(score=scores)
    ranked = _apply_view_filters(ranked, seniority_include, location_include)
    return ranked.sort_values("score", ascending=False).reset_index(drop=True), pool_size


def _describe_scope(
    since_days: int | None,
    seniority_include: set[str] | None,
    location_include: set[str] | None,
) -> str:
    window = (
        f"still listed as of a scrape in the last {since_days} days" if since_days else "any age"
    )
    seniority = ",".join(sorted(seniority_include)) if seniority_include else "all seniorities"
    # The location set is a long token list (country plus cities), so name it
    # rather than dumping every token into the header.
    if location_include is None:
        location = "anywhere"
    elif location_include == NETHERLANDS_LOCATION_TERMS:
        location = "Netherlands"
    else:
        location = ",".join(sorted(location_include))
    return f"unlabeled, {window}, seniority: {seniority}, location: {location}"


def format_digest(
    ranked: pd.DataFrame,
    top_k: int,
    pool_size: int,
    since_days: int | None,
    seniority_include: set[str] | None,
    location_include: set[str] | None = None,
    boundary: date | None = None,
) -> str:
    """Render the digest as markdown. Doubles as the terminal output, so the
    printed and saved versions can never drift apart.
    """
    scope = _describe_scope(since_days, seniority_include, location_include)
    lines = [f"# Job digest {date.today().isoformat()}", "", f"_{scope}_", ""]

    if ranked.empty:
        if pool_size:
            lines += [
                f"No postings matched the filters, though {pool_size} unlabeled "
                "postings are in range.",
                "",
                "Widen with `--all-seniority` / `--all-locations` (or drop "
                "`--new-only`), or edit `SENIORITY_INCLUDE` / `LOCATION_INCLUDE` "
                "in `ranking/preferences.py`.",
            ]
        else:
            lines += [
                "No unlabeled postings in range.",
                "",
                "Run the pipeline (`python -m orchestrator`) to scrape and extract "
                "fresh postings, or widen the window with `--days 0`.",
            ]
        return "\n".join(lines) + "\n"

    shown = min(top_k, len(ranked))
    filtered_note = f" (of {pool_size} before filtering)" if len(ranked) != pool_size else ""
    new_note = ""
    if boundary is not None and "is_new" in ranked.columns:
        new_note = f", {int(ranked['is_new'].sum())} new since {boundary.isoformat()}"
    lines += [
        f"**Top {shown} of {len(ranked)} matching postings**{filtered_note}{new_note}.",
        "Score is the model's expected rating, 0 (no) to 2 (yes).",
        "",
    ]

    for rank, (_, row) in enumerate(ranked.head(top_k).iterrows(), start=1):
        location = row.get("location") or "?"
        seniority = row.get("seniority") or "unknown"
        marker = " **NEW**" if row.get("is_new", False) else ""
        lines.append(
            f"{rank}. **{row['score']:.2f}**{marker}  "
            f"{row['title']} - {row['company']} ({location}) `{seniority}`"
        )
        url = row.get("url")
        if isinstance(url, str) and url:
            lines.append(f"   {url}")

    lines += ["", "---", "", "Rate these with `python -m labeling.cli` to drop them from the next digest."]
    return "\n".join(lines) + "\n"


def save_digest(markdown: str) -> Path:
    DIGEST_DIR.mkdir(parents=True, exist_ok=True)
    path = DIGEST_DIR / f"{date.today().isoformat()}.md"
    path.write_text(markdown, encoding="utf-8")
    return path


def format_health(failures: pd.DataFrame, companies_scraped: int) -> list[str]:
    """Lines reporting company scrapes whose most recent attempt failed.

    This is the counterweight to a scraper that fails softly. A stale board token
    or a changed JSON shape prints one line among hundreds and is never seen
    again, so the company just stops appearing and the digest looks fine, only
    shorter. Surfacing it next to the results is the point: a digest with nothing
    in it reads very differently once you know a third of the boards errored.
    """
    if companies_scraped == 0:
        return []
    if failures.empty:
        return ["", "---", "", f"_Scraper health: all {companies_scraped} company scrapes succeeded._"]

    lines = [
        "",
        "---",
        "",
        f"**Scraper health: {len(failures)} of {companies_scraped} company scrapes failed "
        "on their most recent run.**",
        "",
    ]
    for _, row in failures.iterrows():
        # `or` is not enough here: pandas turns a SQL NULL into NaN, and NaN is
        # truthy, so a source-wide failure would print the literal "nan".
        company = "(whole source)" if pd.isna(row["company"]) or not row["company"] else row["company"]
        # Errors carry a full response body, which can be a page of HTML.
        error = " ".join(str(row["error"]).split())[:160]
        lines.append(f"- `{row['source']}/{company}`: {error}")
    return lines


def build_digest(
    top_k: int,
    since_days: int | None,
    seniority_include: set[str] | None,
    location_include: set[str] | None,
    new_only: bool = False,
) -> tuple[str, pd.DataFrame]:
    """Score, mark newness, optionally narrow to new arrivals, and render.

    Shared by the CLI below and by orchestrator.py, so the two entry points can
    never drift apart on what a digest contains.
    """
    boundary = new_since_boundary()
    ranked, pool_size = score_unlabeled(since_days, seniority_include, location_include)
    ranked = mark_new(ranked, boundary)
    if new_only:
        ranked = ranked[ranked["is_new"]].reset_index(drop=True)
    markdown = format_digest(
        ranked, top_k, pool_size, since_days, seniority_include, location_include, boundary
    )
    failures, companies_scraped = load_scrape_health()
    health = format_health(failures, companies_scraped)
    if health:
        markdown = markdown.rstrip("\n") + "\n" + "\n".join(health) + "\n"
    return markdown, ranked


def _parse_seniority(args: argparse.Namespace) -> set[str] | None:
    if args.all_seniority:
        return None
    if args.seniority:
        return {s.strip().lower() for s in args.seniority.split(",") if s.strip()}
    return SENIORITY_INCLUDE


def _parse_location(args: argparse.Namespace) -> set[str] | None:
    if args.all_locations:
        return None
    if args.location:
        return {loc.strip().lower() for loc in args.location.split(",") if loc.strip()}
    return LOCATION_INCLUDE


def main() -> None:
    parser = argparse.ArgumentParser(description="Rank unlabeled postings and print a digest.")
    parser.add_argument("-k", "--top-k", type=int, default=DEFAULT_TOP_K, help=f"how many to show (default {DEFAULT_TOP_K})")
    parser.add_argument("--days", type=int, default=DEFAULT_DAYS, help=f"only postings scraped in the last N days, 0 for no limit (default {DEFAULT_DAYS})")
    parser.add_argument("--seniority", help="comma-separated seniority levels to show, overriding preferences.py (e.g. internship,junior)")
    parser.add_argument("--all-seniority", action="store_true", help="show every seniority, ignoring preferences.py")
    parser.add_argument("--location", help="comma-separated location tokens to show, overriding preferences.py (e.g. berlin,munich)")
    parser.add_argument("--all-locations", action="store_true", help="show every location, ignoring preferences.py")
    parser.add_argument("--new-only", action="store_true", help="show only postings first seen since the previous digest")
    parser.add_argument("--no-save", action="store_true", help="print only, do not write the markdown copy")
    args = parser.parse_args()

    since_days = args.days or None
    seniority_include = _parse_seniority(args)
    location_include = _parse_location(args)

    markdown, _ = build_digest(
        args.top_k, since_days, seniority_include, location_include, args.new_only
    )
    print(markdown)

    if not args.no_save:
        print(f"Saved to {save_digest(markdown)}")


if __name__ == "__main__":
    main()
