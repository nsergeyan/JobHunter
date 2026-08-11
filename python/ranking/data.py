"""Loads vacancies (extracted fields, with or without a label) for the ranking model."""

import sqlite3
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
LOAD_UNLABELED_SQL = """
SELECT v.id AS vacancy_id, v.title, v.company, v.location, v.url, v.scraped_at, v.raw_text,
       e.skills, e.seniority, e.remote_policy, e.language_requirement
FROM vacancies v
JOIN vacancy_extractions e ON e.vacancy_id = v.id
LEFT JOIN labels l ON l.vacancy_id = v.id
WHERE l.label IS NULL
"""


def load_labeled_vacancies() -> pd.DataFrame:
    conn = sqlite3.connect(DB_PATH)
    try:
        return pd.read_sql_query(LOAD_LABELED_SQL, conn)
    finally:
        conn.close()


def load_unlabeled_vacancies() -> pd.DataFrame:
    conn = sqlite3.connect(DB_PATH)
    try:
        return pd.read_sql_query(LOAD_UNLABELED_SQL, conn)
    finally:
        conn.close()
