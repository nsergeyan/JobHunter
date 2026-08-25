"""Tests for the hard, rule-based filters applied before ranking.

The seniority filter's handling of missing/unknown values is the part worth
pinning down: extraction leaves seniority unknown on roughly a quarter of
postings, so whether those are shown is a real recall decision, not an edge case.
"""

import pytest

from ranking.filters import (
    NETHERLANDS_LOCATION_TERMS,
    matches_location,
    matches_seniority,
    requires_non_english_language,
)


@pytest.mark.parametrize(
    "seniority, include, expected",
    [
        ("internship", {"internship"}, True),
        ("mid", {"internship"}, False),
        ("junior", {"internship", "junior"}, True),
        # Case and surrounding whitespace come from the LLM extraction step and
        # should not change the outcome.
        ("  Internship ", {"internship"}, True),
        # Missing, empty and unrecognised values all collapse to "unknown", so
        # they appear only when "unknown" is explicitly opted into.
        (None, {"internship"}, False),
        (None, {"internship", "unknown"}, True),
        ("", {"unknown"}, True),
        ("weird-value", {"unknown"}, True),
        ("weird-value", {"internship"}, False),
        # include=None means no filter at all.
        ("mid", None, True),
        (None, None, True),
    ],
)
def test_matches_seniority(seniority, include, expected):
    assert matches_seniority(seniority, include) is expected


def test_matches_seniority_empty_include_shows_nothing():
    """An empty set is a filter that matches nothing, distinct from None."""
    assert matches_seniority("internship", set()) is False


@pytest.mark.parametrize(
    "location, expected",
    [
        # The four location layouts the five ATS platforms actually produce.
        ("Amsterdam, NL", True),
        ("Veldhoven, Netherlands", True),
        ("Amsterdam, NH, Netherlands", True),
        ("Netherlands", True),
        # City with no country at all: ING office codes and bare city names.
        ("ACT (Amsterdam - Acanthus)", True),
        ("Eindhoven", True),
        ("Remote - The Netherlands", True),
        # Elsewhere in Europe.
        ("London, United Kingdom", False),
        ("Renningen, BW, Germany", False),
        ("Remote - Poland", False),
        # The substring trap: "Finland" contains the letters "nl", so matching
        # must be on tokens rather than substrings.
        ("Helsinki, Finland", False),
        ("Finland", False),
        # Missing location is excluded when a filter is active.
        (None, False),
        ("", False),
    ],
)
def test_matches_location_netherlands(location, expected):
    assert matches_location(location, NETHERLANDS_LOCATION_TERMS) is expected


def test_matches_location_no_filter_shows_everything():
    assert matches_location("Tokyo, Japan", None) is True
    assert matches_location(None, None) is True


def test_matches_location_accepts_custom_terms():
    """The filter is a token set, so any location preference works, not just NL."""
    assert matches_location("Berlin, Germany", {"berlin"}) is True
    assert matches_location("Amsterdam, NL", {"berlin"}) is False


@pytest.mark.parametrize(
    "requirement, expected",
    [
        ("Dutch", True),
        ("English, German", True),
        ("English", False),
        ("", False),
        (None, False),
    ],
)
def test_requires_non_english_language(requirement, expected):
    assert requires_non_english_language(requirement) is expected
