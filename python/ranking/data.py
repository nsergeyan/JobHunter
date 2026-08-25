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
"""

# Postings that have been extracted but not yet rated -- the ones a daily digest
# should score. The LEFT JOIN + IS NULL keeps only rows with no label row.
# Display columns (url, location, scraped_at) ride along for the digest output;
# the feature columns match LOAD_LABELED_SQL so FeatureBuilder.transform works
# unchanged.
#
# Labeling doubles as "handled": rating a posting removes it from every future
# digest, so the act of dismissing a posting also grows the training set.
LOAD_UNLABELED_SQL = """
SELECT v.id AS vacancy_id, v.title, v.company, v.location, v.url, v.scraped_at, v.raw_text,
       e.skills, e.seniority, e.remote_policy, e.language_requirement
FROM vacancies v
JOIN vacancy_extractions e ON e.vacancy_id = v.id
LEFT JOIN labels l ON l.vacancy_id = v.id
WHERE l.label IS NULL
"""

# Optional recency window. scraped_at is an ISO 8601 string, which sorts
# lexicographically in the same order it sorts chronologically, so a plain string
# comparison against an ISO cutoff is correct here without any date parsing.
RECENT_ONLY_SQL = " AND v.scraped_at >= :cutoff"


def load_labeled_vacancies() -> pd.DataFrame:
    conn = sqlite3.connect(DB_PATH)
    try:
        return pd.read_sql_query(LOAD_LABELED_SQL, conn)
    finally:
        conn.close()


def load_unlabeled_vacancies(since_days: int | None = None) -> pd.DataFrame:
    """Extracted-but-unrated postings, optionally limited to ones scraped within
    the last `since_days` days. `None` (or 0) means no time limit.
    """
    sql, params = LOAD_UNLABELED_SQL, {}
    if since_days:
        cutoff = datetime.now(timezone.utc) - timedelta(days=since_days)
        sql += RECENT_ONLY_SQL
        params = {"cutoff": cutoff.isoformat().replace("+00:00", "Z")}

    conn = sqlite3.connect(DB_PATH)
    try:
        return pd.read_sql_query(sql, conn, params=params)
    finally:
        conn.close()
