"""Data access functions for the vacancies table."""

import sqlite3
from dataclasses import dataclass
from datetime import datetime, timezone


@dataclass
class VacancyRecord:
    source: str
    url: str
    title: str
    company: str | None
    location: str | None
    raw_text: str | None


def upsert_vacancy(conn: sqlite3.Connection, vacancy: VacancyRecord) -> None:
    now = datetime.now(timezone.utc).isoformat()
    conn.execute(
        """
        INSERT INTO vacancies (source, url, title, company, location, raw_text, scraped_at, first_seen, last_seen)
        VALUES (:source, :url, :title, :company, :location, :raw_text, :scraped_at, :first_seen, :last_seen)
        ON CONFLICT (source, url) DO UPDATE SET
            title = excluded.title,
            company = excluded.company,
            location = excluded.location,
            raw_text = excluded.raw_text,
            scraped_at = excluded.scraped_at,
            last_seen = excluded.last_seen
        """,
        {
            "source": vacancy.source,
            "url": vacancy.url,
            "title": vacancy.title,
            "company": vacancy.company,
            "location": vacancy.location,
            "raw_text": vacancy.raw_text,
            "scraped_at": now,
            "first_seen": now,
            "last_seen": now,
        },
    )
    conn.commit()
