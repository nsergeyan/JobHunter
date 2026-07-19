import sqlite3

from src.db.repository import VacancyRecord, upsert_vacancy
from src.db.schema import init_db


def _connect(tmp_path):
    db_path = tmp_path / "test.db"
    init_db(str(db_path))
    return sqlite3.connect(str(db_path))


def test_upsert_inserts_new_vacancy(tmp_path):
    conn = _connect(tmp_path)
    vacancy = VacancyRecord(
        source="greenhouse",
        url="https://example.com/job/1",
        title="DS Intern",
        company="Acme",
        location="Amsterdam",
        raw_text="desc",
    )

    upsert_vacancy(conn, vacancy)

    row = conn.execute(
        "SELECT source, url, title, company, location, raw_text, first_seen, last_seen "
        "FROM vacancies WHERE url = ?",
        (vacancy.url,),
    ).fetchone()
    conn.close()

    assert row[:6] == (
        "greenhouse",
        "https://example.com/job/1",
        "DS Intern",
        "Acme",
        "Amsterdam",
        "desc",
    )
    assert row[6] == row[7]  # first_seen == last_seen on first insert


def test_upsert_updates_existing_vacancy_without_duplicating(tmp_path):
    conn = _connect(tmp_path)
    vacancy = VacancyRecord(
        source="greenhouse",
        url="https://example.com/job/1",
        title="DS Intern",
        company="Acme",
        location="Amsterdam",
        raw_text="desc v1",
    )
    upsert_vacancy(conn, vacancy)
    first_seen_before = conn.execute(
        "SELECT first_seen FROM vacancies WHERE url = ?", (vacancy.url,)
    ).fetchone()[0]

    updated = VacancyRecord(
        source="greenhouse",
        url="https://example.com/job/1",
        title="DS Intern",
        company="Acme",
        location="Amsterdam",
        raw_text="desc v2 (edited)",
    )
    upsert_vacancy(conn, updated)

    rows = conn.execute(
        "SELECT raw_text, first_seen FROM vacancies WHERE url = ?", (vacancy.url,)
    ).fetchall()
    conn.close()

    assert len(rows) == 1
    assert rows[0][0] == "desc v2 (edited)"
    assert rows[0][1] == first_seen_before  # first_seen preserved across updates
