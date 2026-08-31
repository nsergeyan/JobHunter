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
ORDER BY v.id
"""


# Exact duplicates only: same title, company AND location, so genuinely the same
# job in the same place. Copies that differ by city are left alone, because rating
# a role differently in Paris and London is a preference, not an inconsistency.
#
# Postings get duplicated for two reasons, neither of which external_id can catch:
# a company re-lists an expired job and the platform issues a fresh posting id, or
# it submits the same job twice at once under consecutive ids.
FIND_CONFLICTING_DUPLICATES_SQL = """
SELECT v.id, v.title, v.company, v.location, v.raw_text,
       e.summary, e.skills, e.seniority, e.salary_min, e.salary_max,
       e.salary_currency, e.salary_period, e.language_requirement, e.remote_policy,
       l.label, substr(l.labeled_at, 1, 10) AS labeled_on
FROM vacancies v
JOIN vacancy_extractions e ON e.vacancy_id = v.id
JOIN labels l ON l.vacancy_id = v.id
WHERE (v.title, v.company, v.location) IN (
    SELECT v2.title, v2.company, v2.location
    FROM vacancies v2 JOIN labels l2 ON l2.vacancy_id = v2.id
    GROUP BY v2.title, v2.company, v2.location
    HAVING COUNT(*) > 1 AND COUNT(DISTINCT l2.label) > 1
)
ORDER BY v.company, v.title, v.location, l.labeled_at
"""


@dataclass
class ConflictingGroup:
    """One job you rated more than once, inconsistently."""

    vacancy_ids: list[int]
    previous: list[tuple[int, str]]  # (label, date rated), oldest first
    posting: "VacancyToLabel"        # the most recently rated copy, to display


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


def find_conflicting_duplicates(conn: sqlite3.Connection) -> list[ConflictingGroup]:
    """Jobs that appear more than once and were rated differently each time.

    One group per job, not one per row: you rate it once and the answer applies to
    every copy.
    """
    rows = [dict(row) for row in conn.execute(FIND_CONFLICTING_DUPLICATES_SQL).fetchall()]

    grouped: dict[tuple, list[dict]] = {}
    for row in rows:
        grouped.setdefault((row["company"], row["title"], row["location"]), []).append(row)

    groups = []
    for copies in grouped.values():
        # The newest copy is the one worth reading: its description is the version
        # currently on the board.
        newest = copies[-1]
        posting = VacancyToLabel(**{
            field: newest[field] for field in VacancyToLabel.__dataclass_fields__
        })
        groups.append(ConflictingGroup(
            vacancy_ids=[c["id"] for c in copies],
            previous=[(c["label"], c["labeled_on"]) for c in copies],
            posting=posting,
        ))
    return groups


def save_label(conn: sqlite3.Connection, vacancy_id: int, label: int) -> None:
    # Upsert rather than plain insert: the normal loop only ever shows unlabeled
    # postings, but the conflict-fixing mode deliberately re-rates ones already
    # labeled, and a bare INSERT would fail on the primary key.
    conn.execute(
        """
        INSERT INTO labels (vacancy_id, label, labeled_at) VALUES (?, ?, ?)
        ON CONFLICT (vacancy_id) DO UPDATE SET label = excluded.label, labeled_at = excluded.labeled_at
        """,
        (vacancy_id, label, datetime.now(timezone.utc).isoformat()),
    )
    conn.commit()


def save_label_for_group(conn: sqlite3.Connection, vacancy_ids: list[int], label: int) -> None:
    """Apply one rating to every copy of the same job.

    The point of the conflict-fixing mode: you rate the job once, and all of its
    duplicate rows agree afterwards, so the inconsistency cannot come back.
    """
    now = datetime.now(timezone.utc).isoformat()
    conn.executemany(
        """
        INSERT INTO labels (vacancy_id, label, labeled_at) VALUES (?, ?, ?)
        ON CONFLICT (vacancy_id) DO UPDATE SET label = excluded.label, labeled_at = excluded.labeled_at
        """,
        [(vacancy_id, label, now) for vacancy_id in vacancy_ids],
    )
    conn.commit()


def delete_label(conn: sqlite3.Connection, vacancy_id: int) -> None:
    conn.execute("DELETE FROM labels WHERE vacancy_id = ?", (vacancy_id,))
    conn.commit()


def delete_label_for_group(conn: sqlite3.Connection, vacancy_ids: list[int]) -> None:
    """Undo a rating that was written to every copy of a job.

    Mirrors save_label_for_group: one keypress wrote several rows, so undo has to
    remove all of them or the copies are left disagreeing.
    """
    conn.executemany(
        "DELETE FROM labels WHERE vacancy_id = ?",
        [(vacancy_id,) for vacancy_id in vacancy_ids],
    )
    conn.commit()


def label_counts(conn: sqlite3.Connection) -> dict[int, int]:
    rows = conn.execute("SELECT label, COUNT(*) AS n FROM labels GROUP BY label").fetchall()
    return {row["label"]: row["n"] for row in rows}
