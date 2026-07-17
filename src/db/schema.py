"""Create the SQLite database from schema.sql. No scraper logic lives here yet."""

import os
import sqlite3
from pathlib import Path

SCHEMA_PATH = Path(__file__).parent / "schema.sql"


def init_db(db_path: str) -> None:
    Path(db_path).parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(db_path)
    try:
        conn.executescript(SCHEMA_PATH.read_text())
        conn.commit()
    finally:
        conn.close()


if __name__ == "__main__":
    from dotenv import load_dotenv

    load_dotenv()
    path = os.environ.get("DATABASE_PATH", "data/job_scout.db")
    init_db(path)
    print(f"Initialized database at {path}")
