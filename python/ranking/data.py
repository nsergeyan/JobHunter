"""Loads labeled vacancies (label + extracted fields) for training the ranking model."""

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


def load_labeled_vacancies() -> pd.DataFrame:
    conn = sqlite3.connect(DB_PATH)
    try:
        return pd.read_sql_query(LOAD_LABELED_SQL, conn)
    finally:
        conn.close()
