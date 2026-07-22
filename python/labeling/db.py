"""SQLite access for the labeling CLI. Owns its own `labels` table -- doesn't touch
the Java side's schema.sql, since this is a separate concern from scraping/extraction.
"""

import sqlite3
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

DB_PATH = Path(__file__).resolve().parents[2] / "data" / "job_scout.db"

CREATE_LABELS_TABLE = """
CREATE TABLE IF NOT EXISTS labels (
    vacancy_id INTEGER PRIMARY KEY REFERENCES vacancies(id) ON DELETE CASCADE,
    label INTEGER NOT NULL CHECK (label IN (0, 1, 2)),
    labeled_at TEXT NOT NULL
)
"""

FIND_UNLABELED_SQL = """
SELECT v.id, v.title, v.company, v.location, v.raw_text,
       e.summary, e.skills, e.seniority, e.salary_min, e.salary_max,
       e.salary_currency, e.salary_period, e.language_requirement, e.remote_policy
FROM vacancies v
JOIN vacancy_extractions e ON e.vacancy_id = v.id
WHERE v.id NOT IN (SELECT vacancy_id FROM labels)
"""


@dataclass
class VacancyToLabel:
    id: int
    title: str
    company: str | None
    location: str | None
    raw_text: str
    summary: str | None
    skills: str  # raw JSON string, e.g. '["Python","SQL"]'
    seniority: str
    salary_min: int | None
    salary_max: int | None
    salary_currency: str | None
    salary_period: str
    language_requirement: str | None
    remote_policy: str


def connect() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute(CREATE_LABELS_TABLE)
    return conn


def find_unlabeled(conn: sqlite3.Connection) -> list[VacancyToLabel]:
    rows = conn.execute(FIND_UNLABELED_SQL).fetchall()
    return [VacancyToLabel(**dict(row)) for row in rows]


def save_label(conn: sqlite3.Connection, vacancy_id: int, label: int) -> None:
    conn.execute(
        "INSERT INTO labels (vacancy_id, label, labeled_at) VALUES (?, ?, ?)",
        (vacancy_id, label, datetime.now(timezone.utc).isoformat()),
    )
    conn.commit()


def delete_label(conn: sqlite3.Connection, vacancy_id: int) -> None:
    conn.execute("DELETE FROM labels WHERE vacancy_id = ?", (vacancy_id,))
    conn.commit()


def label_counts(conn: sqlite3.Connection) -> dict[int, int]:
    rows = conn.execute("SELECT label, COUNT(*) AS n FROM labels GROUP BY label").fetchall()
    return {row["label"]: row["n"] for row in rows}
