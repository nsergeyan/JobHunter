import sqlite3

from src.db.schema import init_db


def test_init_db_creates_vacancies_table(tmp_path):
    db_path = tmp_path / "test.db"
    init_db(str(db_path))

    conn = sqlite3.connect(db_path)
    tables = conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='vacancies'"
    ).fetchall()
    conn.close()

    assert tables == [("vacancies",)]
