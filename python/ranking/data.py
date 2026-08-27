"""Loads vacancies (extracted fields, with or without a label) for the ranking model."""

import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pandas as pd

DB_PATH = Path(__file__).resolve().parents[2] / "data" / "job_scout.db"

LOAD_LABELED_SQL = """
SELECT v.id AS vacancy_id, v.title, v.company, v.raw_text,
       e.skills, e.seniority, e.remote_policy, e.language_requirement,
       l.label
FROM labels l
JOIN vacancies v ON v.id = l.vacancy_id
JOIN vacancy_extractions e ON e.vacancy_id = l.vacancy_id
ORDER BY v.id
"""

# Postings that have been extracted but not yet rated -- the ones a daily digest
# should score. The LEFT JOIN + IS NULL keeps only rows with no label row.
# Display columns (url, location, scraped_at, first_seen) ride along for the
# digest output; the feature columns match LOAD_LABELED_SQL so
# FeatureBuilder.transform works unchanged.
#
# Labeling doubles as "handled": rating a posting removes it from every future
# digest, so the act of dismissing a posting also grows the training set.
LOAD_UNLABELED_SQL = """
SELECT v.id AS vacancy_id, v.title, v.company, v.location, v.url,
       v.scraped_at, v.first_seen, v.raw_text,
       e.skills, e.seniority, e.remote_policy, e.language_requirement
FROM vacancies v
JOIN vacancy_extractions e ON e.vacancy_id = v.id
LEFT JOIN labels l ON l.vacancy_id = v.id
WHERE l.label IS NULL
  AND v.closed_at IS NULL
"""

# Row order has to be pinned. SQLite makes no ordering promise without ORDER BY,
# and the order it happens to return rows in feeds StratifiedKFold's shuffle and
# every tie-break in the final ranking -- so without this, two identical runs
# produced different precision@k, which makes any A/B comparison meaningless.
ORDER_BY_ID_SQL = " ORDER BY v.id"

# Optional LIVENESS window, and the distinction matters. The scraper refreshes
# scraped_at on every run for any posting still on its board (VacancyRepository's
# ON CONFLICT ... SET scraped_at = excluded.scraped_at), so this asks "was this
# still listed in a scrape within the last N days" -- NOT "was it posted
# recently". A posting first seen in July that is still open gets today's
# scraped_at and passes this filter, which is correct: it is still applicable.
#
# first_seen is the column that answers "is this new", and it is never touched
# on conflict. It rides along in the SELECT so the digest can mark new postings.
#
# closed_at is a third, separate thing: the scraper sets it once a posting has
# actually vanished from its board, so filtering on it stops the digest linking
# to dead applications. Only sources that read a company's whole listing set it.
#
# ISO 8601 strings sort lexicographically in the same order they sort
# chronologically, so a plain string comparison against an ISO cutoff is correct
# here without any date parsing.
STILL_LISTED_SQL = " AND v.scraped_at >= :cutoff"


def load_labeled_vacancies() -> pd.DataFrame:
    conn = sqlite3.connect(DB_PATH)
    try:
        return pd.read_sql_query(LOAD_LABELED_SQL, conn)
    finally:
        conn.close()


def load_unlabeled_vacancies(since_days: int | None = None) -> pd.DataFrame:
    """Extracted-but-unrated postings, optionally limited to ones still listed in
    a scrape within the last `since_days` days. `None` (or 0) means no limit.

    Note this is a liveness window, not a recency one: see STILL_LISTED_SQL.
    """
    sql, params = LOAD_UNLABELED_SQL, {}
    if since_days:
        cutoff = datetime.now(timezone.utc) - timedelta(days=since_days)
        sql += STILL_LISTED_SQL
        params = {"cutoff": cutoff.isoformat().replace("+00:00", "Z")}
    sql += ORDER_BY_ID_SQL

    conn = sqlite3.connect(DB_PATH)
    try:
        return pd.read_sql_query(sql, conn, params=params)
    finally:
        conn.close()


# Latest run per (source, company) that ended in an error. A company that failed
# once and recovered is not worth reporting -- only one whose MOST RECENT attempt
# failed is actually broken right now. SQLite's `IS` handles the NULL company used
# by sources that are not scraped per company, where plain `=` would never match.
LATEST_FAILURES_SQL = """
SELECT source, company, error, finished_at
FROM scrape_runs r
WHERE r.finished_at = (
    SELECT MAX(finished_at) FROM scrape_runs r2
    WHERE r2.source = r.source AND r2.company IS r.company
)
AND r.error IS NOT NULL
ORDER BY r.source, r.company
"""

COMPANY_COUNT_SQL = "SELECT COUNT(DISTINCT source || '/' || COALESCE(company, '')) AS n FROM scrape_runs"


def load_scrape_health() -> tuple[pd.DataFrame, int]:
    """Companies whose most recent scrape failed, plus how many were scraped at all.

    Returns an empty frame when scrape_runs does not exist yet: the table is
    created by the Java side, so a Python-only checkout can predate it, and a
    missing health section is a much better outcome than a crashed digest.
    """
    conn = sqlite3.connect(DB_PATH)
    try:
        failures = pd.read_sql_query(LATEST_FAILURES_SQL, conn)
        total = int(pd.read_sql_query(COMPANY_COUNT_SQL, conn)["n"].iloc[0])
        return failures, total
    except (sqlite3.OperationalError, pd.errors.DatabaseError):
        return pd.DataFrame(columns=["source", "company", "error", "finished_at"]), 0
    finally:
        conn.close()
