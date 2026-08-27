"""Tests for the digest's newness marking.

The distinction these pin down is the one the digest used to get wrong: the
scraper REFRESHES scraped_at on every run for any posting still on its board, so
that column tracks liveness, not arrival. first_seen is written once and never
touched again, so it is the only honest answer to "is this new to me".
"""

from datetime import date, timedelta

import pandas as pd
import pytest

from ranking.digest import (
    NEW_FALLBACK_DAYS,
    mark_new,
    new_since_boundary,
    previous_digest_date,
)

BOUNDARY = date(2026, 8, 25)


def _postings(*first_seen_values) -> pd.DataFrame:
    return pd.DataFrame(
        {
            "first_seen": list(first_seen_values),
            "title": ["role"] * len(first_seen_values),
        }
    )


@pytest.mark.parametrize(
    "first_seen, expected",
    [
        # Instant.toString() from the Java side: ISO 8601, UTC, trailing Z.
        ("2026-08-26T09:15:00Z", True),
        # Exactly on the boundary counts as new: the boundary is midnight UTC of
        # the previous digest's date, so anything that arrived during that day
        # had not been reported yet.
        ("2026-08-25T00:00:00Z", True),
        ("2026-08-24T23:59:59Z", False),
        ("2026-07-20T12:00:00Z", False),
    ],
)
def test_marks_new_relative_to_boundary(first_seen, expected):
    marked = mark_new(_postings(first_seen), BOUNDARY)
    assert bool(marked["is_new"].iloc[0]) is expected


@pytest.mark.parametrize("first_seen", [None, "", "not-a-timestamp"])
def test_unparseable_first_seen_is_not_new(first_seen):
    """Conservative on purpose: a missed NEW badge is cheaper than shouting
    about a posting from two months ago."""
    marked = mark_new(_postings(first_seen), BOUNDARY)
    assert bool(marked["is_new"].iloc[0]) is False


def test_empty_frame_still_gets_the_column():
    """format_digest checks for is_new, so an empty result must not lose it."""
    marked = mark_new(pd.DataFrame(columns=["first_seen", "title"]), BOUNDARY)
    assert "is_new" in marked.columns


def test_frame_without_first_seen_is_handled():
    """Callers that build a frame by hand should not blow up the digest."""
    marked = mark_new(pd.DataFrame({"title": ["role"]}), BOUNDARY)
    assert "is_new" in marked.columns


def test_previous_digest_date_picks_latest_before_today(tmp_path, monkeypatch):
    monkeypatch.setattr("ranking.digest.DIGEST_DIR", tmp_path)
    for name in ("2026-08-20.md", "2026-08-25.md", "2026-07-01.md"):
        (tmp_path / name).write_text("x")
    assert previous_digest_date() == date(2026, 8, 25)


def test_previous_digest_date_ignores_today(tmp_path, monkeypatch):
    """Re-running the digest twice in one day must not reset the marker to
    'nothing is new' by measuring against the file it just wrote."""
    monkeypatch.setattr("ranking.digest.DIGEST_DIR", tmp_path)
    (tmp_path / f"{date.today().isoformat()}.md").write_text("x")
    (tmp_path / "2026-08-20.md").write_text("x")
    assert previous_digest_date() == date(2026, 8, 20)


def test_previous_digest_date_ignores_non_dated_files(tmp_path, monkeypatch):
    monkeypatch.setattr("ranking.digest.DIGEST_DIR", tmp_path)
    (tmp_path / "notes.md").write_text("x")
    (tmp_path / "README.md").write_text("x")
    assert previous_digest_date() is None


def test_boundary_falls_back_when_no_previous_digest(tmp_path, monkeypatch):
    """First run: without a fallback every posting in the DB would be marked NEW."""
    monkeypatch.setattr("ranking.digest.DIGEST_DIR", tmp_path)
    assert new_since_boundary() == date.today() - timedelta(days=NEW_FALLBACK_DAYS)


def test_boundary_uses_previous_digest_when_present(tmp_path, monkeypatch):
    monkeypatch.setattr("ranking.digest.DIGEST_DIR", tmp_path)
    (tmp_path / "2026-08-25.md").write_text("x")
    assert new_since_boundary() == date(2026, 8, 25)
