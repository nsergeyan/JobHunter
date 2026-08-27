"""Tests for re-rating duplicated jobs that were labeled inconsistently.

This mode writes over labels that already exist, which nothing else in the CLI
does, so the guarantees worth pinning down are that it finds exactly the right
jobs and that one keypress reaches every copy.
"""

import sqlite3

import pytest

from labeling import db


@pytest.fixture
def conn(tmp_path, monkeypatch):
    """A database with the tables the labeling CLI touches, and nothing else."""
    path = tmp_path / "test.db"
    monkeypatch.setattr(db, "DB_PATH", path)
    connection = sqlite3.connect(path)
    connection.row_factory = sqlite3.Row
    connection.executescript(
        """
        CREATE TABLE vacancies (
            id INTEGER PRIMARY KEY, source TEXT, external_id TEXT, url TEXT,
            title TEXT, company TEXT, location TEXT, raw_text TEXT,
            scraped_at TEXT, first_seen TEXT, last_seen TEXT, closed_at TEXT
        );
        CREATE TABLE vacancy_extractions (
            vacancy_id INTEGER PRIMARY KEY, summary TEXT, skills TEXT, seniority TEXT,
            salary_min INTEGER, salary_max INTEGER, salary_currency TEXT,
            salary_period TEXT, language_requirement TEXT, remote_policy TEXT
        );
        CREATE TABLE labels (
            vacancy_id INTEGER PRIMARY KEY, label INTEGER NOT NULL, labeled_at TEXT NOT NULL
        );
        """
    )
    yield connection
    connection.close()


def add(connection, vacancy_id, title, company, location, label=None, labeled_at="2026-08-01T10:00:00Z"):
    connection.execute(
        "INSERT INTO vacancies (id, title, company, location, raw_text) VALUES (?, ?, ?, ?, ?)",
        (vacancy_id, title, company, location, "description text"),
    )
    connection.execute(
        "INSERT INTO vacancy_extractions (vacancy_id, skills, seniority, salary_period, remote_policy) "
        "VALUES (?, '[]', 'internship', 'unknown', 'hybrid')",
        (vacancy_id,),
    )
    if label is not None:
        connection.execute(
            "INSERT INTO labels (vacancy_id, label, labeled_at) VALUES (?, ?, ?)",
            (vacancy_id, label, labeled_at),
        )
    connection.commit()


class TestFindingConflicts:
    def test_finds_a_job_rated_two_different_ways(self, conn):
        add(conn, 1, "ML Intern", "Acme", "Amsterdam", label=0, labeled_at="2026-07-01T10:00:00Z")
        add(conn, 2, "ML Intern", "Acme", "Amsterdam", label=2, labeled_at="2026-08-01T10:00:00Z")

        groups = db.find_conflicting_duplicates(conn)

        assert len(groups) == 1
        assert sorted(groups[0].vacancy_ids) == [1, 2]
        assert groups[0].previous == [(0, "2026-07-01"), (2, "2026-08-01")]

    def test_shows_the_most_recently_rated_copy(self, conn):
        # Its description is the version currently on the board.
        add(conn, 1, "ML Intern", "Acme", "Amsterdam", label=0, labeled_at="2026-07-01T10:00:00Z")
        add(conn, 2, "ML Intern", "Acme", "Amsterdam", label=2, labeled_at="2026-08-01T10:00:00Z")

        assert db.find_conflicting_duplicates(conn)[0].posting.id == 2

    def test_consistent_duplicates_are_left_alone(self, conn):
        # Duplicated but rated the same way twice. Nothing to resolve.
        add(conn, 1, "ML Intern", "Acme", "Amsterdam", label=2)
        add(conn, 2, "ML Intern", "Acme", "Amsterdam", label=2)

        assert db.find_conflicting_duplicates(conn) == []

    def test_same_role_in_two_cities_is_not_a_conflict(self, conn):
        # Rating a job differently in Paris and London is a preference about
        # location, not an inconsistency, so it must not be surfaced for "fixing".
        add(conn, 1, "AI Engineer", "OpenAI", "Paris", label=2)
        add(conn, 2, "AI Engineer", "OpenAI", "London", label=1)

        assert db.find_conflicting_duplicates(conn) == []

    def test_unlabeled_copies_do_not_create_a_conflict(self, conn):
        add(conn, 1, "ML Intern", "Acme", "Amsterdam", label=2)
        add(conn, 2, "ML Intern", "Acme", "Amsterdam", label=None)

        assert db.find_conflicting_duplicates(conn) == []


class TestApplyingTheFix:
    def test_one_rating_reaches_every_copy(self, conn):
        add(conn, 1, "ML Intern", "Acme", "Amsterdam", label=0, labeled_at="2026-07-01T10:00:00Z")
        add(conn, 2, "ML Intern", "Acme", "Amsterdam", label=2, labeled_at="2026-08-01T10:00:00Z")
        add(conn, 3, "ML Intern", "Acme", "Amsterdam", label=1, labeled_at="2026-08-02T10:00:00Z")

        db.save_label_for_group(conn, [1, 2, 3], 2)

        labels = [r["label"] for r in conn.execute("SELECT label FROM labels ORDER BY vacancy_id")]
        assert labels == [2, 2, 2]

    def test_the_conflict_is_gone_afterwards(self, conn):
        add(conn, 1, "ML Intern", "Acme", "Amsterdam", label=0)
        add(conn, 2, "ML Intern", "Acme", "Amsterdam", label=2)

        group = db.find_conflicting_duplicates(conn)[0]
        db.save_label_for_group(conn, group.vacancy_ids, 1)

        assert db.find_conflicting_duplicates(conn) == []

    def test_saving_over_an_existing_label_replaces_it(self, conn):
        # The normal loop only shows unlabeled postings, so a plain INSERT was
        # enough there. Re-rating needs an upsert or it fails on the primary key.
        add(conn, 1, "ML Intern", "Acme", "Amsterdam", label=0)

        db.save_label(conn, 1, 2)

        assert conn.execute("SELECT label FROM labels WHERE vacancy_id = 1").fetchone()["label"] == 2
        assert conn.execute("SELECT COUNT(*) c FROM labels").fetchone()["c"] == 1

    def test_other_jobs_are_untouched(self, conn):
        add(conn, 1, "ML Intern", "Acme", "Amsterdam", label=0)
        add(conn, 2, "ML Intern", "Acme", "Amsterdam", label=2)
        add(conn, 9, "Backend Dev", "Other", "Berlin", label=1)

        db.save_label_for_group(conn, [1, 2], 2)

        assert conn.execute("SELECT label FROM labels WHERE vacancy_id = 9").fetchone()["label"] == 1
