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
    format_digest,
    format_health,
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


class TestScraperHealth:
    """The health section exists because scrapers fail softly. A stale board token
    prints one line among hundreds and the company simply stops appearing, so a
    short digest looks the same whether the market is quiet or a third of the
    boards are erroring."""

    @staticmethod
    def _failures(*rows) -> pd.DataFrame:
        return pd.DataFrame(list(rows), columns=["source", "company", "error", "finished_at"])

    def test_nothing_is_reported_before_any_scrape_has_run(self):
        # A fresh checkout has an empty scrape_runs table. Claiming "all 0 scrapes
        # succeeded" would be noise.
        assert format_health(self._failures(), 0) == []

    def test_clean_run_is_stated_positively(self):
        lines = format_health(self._failures(), 141)
        assert any("all 141 company scrapes succeeded" in line for line in lines)

    def test_failures_are_named_with_their_source(self):
        lines = format_health(
            self._failures(("greenhouse", "Acme", "failed with status 404", "2026-08-27T10:00:00Z")), 141
        )
        body = "\n".join(lines)
        assert "1 of 141 company scrapes failed" in body
        assert "`greenhouse/Acme`" in body
        assert "404" in body

    def test_source_wide_failure_has_a_readable_company_label(self):
        # Magnet.me records company as NULL, since it is not scraped per company.
        lines = format_health(self._failures(("magnetme", None, "sitemap unreachable", "2026-08-27T10:00:00Z")), 141)
        assert any("(whole source)" in line for line in lines)

    def test_long_error_bodies_are_collapsed(self):
        # An HTTP error carries the whole response body, which can be a page of HTML.
        sprawling = "line one\n   line two\t\tline three " + "x" * 500
        lines = format_health(self._failures(("workday", "Acme", sprawling, "2026-08-27T10:00:00Z")), 141)
        entry = [line for line in lines if line.startswith("- ")][0]
        assert "\n" not in entry and "\t" not in entry
        assert len(entry) < 200, "a single failure should not flood the digest"


class TestLanguageTag:
    """When the language view is off, foreign-language postings are labelled rather
    than silently mixed in, so opting to see them does not mean guessing which is
    which."""

    @staticmethod
    def _ranked(raw_text):
        return pd.DataFrame({
            "score": [1.5],
            "title": ["ML Intern"],
            "company": ["Bosch"],
            "location": ["Stuttgart"],
            "seniority": ["internship"],
            "url": ["https://example.com/1"],
            "first_seen": ["2026-08-26T10:00:00Z"],
            "raw_text": [raw_text],
        })

    GERMAN = (
        "Du entwickelst und etablierst innovative Lösungen im Bereich Machine "
        "Learning. Deine Aufgaben koordinierst du mit anderen Teammitgliedern und "
        "Abteilungen. Wir bieten dir die Chance, dich weiterzuentwickeln, und du "
        "wirst durch erfahrene Kollegen begleitet, sodass du schnell Verantwortung "
        "übernehmen kannst. Bei uns arbeitest du in einem Team, das dich fördert."
    )

    def test_foreign_posting_is_tagged_when_languages_are_shown(self):
        out = format_digest(self._ranked(self.GERMAN), 5, 1, 14, None, None,
                            hide_non_english=False)
        assert "`de`" in out

    def test_no_tag_when_the_english_only_view_is_active(self):
        # In that view every posting shown is English, so a tag would be noise.
        out = format_digest(self._ranked(self.GERMAN), 5, 1, 14, None, None,
                            hide_non_english=True)
        assert "`de`" not in out

    def test_english_posting_is_never_tagged(self):
        english = (
            "We are looking for a machine learning intern to join our team and help "
            "build models that serve millions of users, working with Python and "
            "PyTorch across the full lifecycle from prototype to production."
        )
        out = format_digest(self._ranked(english), 5, 1, 14, None, None,
                            hide_non_english=False)
        assert "`de`" not in out

    def test_scope_line_states_which_language_view_is_active(self):
        shown = format_digest(self._ranked(self.GERMAN), 5, 1, 14, None, None, hide_non_english=False)
        hidden = format_digest(self._ranked("x"), 5, 1, 14, None, None, hide_non_english=True)
        assert "any language" in shown
        assert "English-language only" in hidden
