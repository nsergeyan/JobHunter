"""Tests for the hard, rule-based filters applied before ranking.

The seniority filter's handling of missing/unknown values is the part worth
pinning down: extraction leaves seniority unknown on roughly a quarter of
postings, so whether those are shown is a real recall decision, not an edge case.
"""

import pandas as pd
import pytest

from ranking.filters import (
    LANGUAGE_CODES,
    NETHERLANDS_LOCATION_TERMS,
    NON_ENGLISH_MARKERS,
    detect_written_language,
    drop_language_blocked,
    is_written_in_non_english,
    matches_language_view,
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


class TestWrittenLanguage:
    """The gap this closes: a posting written entirely in German that never says so.
    The stated-requirement check cannot see it, because the requirement is so obvious
    to whoever wrote the posting that it goes unwritten.
    """

    GERMAN = (
        "Du möchtest die Zukunft der nachhaltigen Technologie aktiv mitgestalten und "
        "Projekte mit uns umsetzen? Bei uns arbeitest du in einem Team, das dich "
        "unterstützt und fördert. Wir bieten dir die Chance, dein Wissen einzubringen "
        "und dich weiterzuentwickeln. Deine Aufgaben sind vielfältig und du wirst "
        "durch erfahrene Kollegen begleitet, sodass du schnell Verantwortung "
        "übernehmen kannst."
    )
    DUTCH = (
        "Bij Royal Agrifirm Group krijg je als Junior Data Engineer de kans om mee te "
        "bouwen aan onze data-oplossingen. Je werkt samen met andere collega's binnen "
        "een team dat elke dag beter wil worden. Wat wij vragen is ervaring met data "
        "en de wil om te leren. Je krijgt veel ruimte om zelf keuzes te maken en "
        "onder begeleiding door te groeien in het vak."
    )
    ENGLISH = (
        "We are looking for a Machine Learning Engineer to join our platform team. "
        "You will build and deploy models that serve millions of users per day, "
        "working closely with product and data engineering. Experience with Python "
        "and PyTorch is required, and you will be expected to own features end to "
        "end from prototype through to production and ongoing measurement."
    )

    def test_flags_a_posting_written_in_german(self):
        assert is_written_in_non_english(self.GERMAN) is True
        assert detect_written_language(self.GERMAN)[0] == "german"

    def test_flags_a_posting_written_in_dutch(self):
        assert is_written_in_non_english(self.DUTCH) is True
        assert detect_written_language(self.DUTCH)[0] == "dutch"

    def test_keeps_an_ordinary_english_posting(self):
        assert is_written_in_non_english(self.ENGLISH) is False

    def test_english_phrases_that_look_foreign_do_not_trigger(self):
        # "per year" and "e.g." were the original false-positive source: they
        # tokenise to "per" and "e", which are Italian function words.
        text = (
            "The salary is reviewed per year and reported per quarter, e.g. per team "
            "and per region, with figures shared per department each month. "
        ) * 4
        assert is_written_in_non_english(text) is False

    def test_short_text_is_never_guessed_at(self):
        # A handful of tokens can hit any ratio by accident.
        assert is_written_in_non_english("und mit für") is False
        assert detect_written_language("und mit für") == (None, 0.0)

    @pytest.mark.parametrize("value", [None, "", 12345])
    def test_missing_text_is_handled(self, value):
        assert is_written_in_non_english(value) is False

    def test_bilingual_postings_are_kept(self):
        """A posting carrying both versions should stay, since there is an English
        version you can actually read.

        The real case this models is a Deutsche Bank listing beginning "*English
        version below*", which scores 9.0% against a 10% threshold. That is a narrow
        margin and worth stating plainly: a bilingual posting whose English section
        is much shorter than its German one WILL be dropped. Erring that way is
        deliberate, since the alternative is letting genuinely German postings
        through, but it is the known cost of a single global threshold.
        """
        mixed = self.GERMAN + " " + self.ENGLISH * 3
        assert is_written_in_non_english(mixed) is False


class TestTwoKindsOfLanguageFilter:
    """The distinction these pin down. A posting that NAMES a Dutch requirement is a
    role you cannot take, so it goes everywhere. A posting merely WRITTEN in German
    often describes a role whose working language is English, and the labels agree:
    3 such postings were rated yes and 8 maybe. So it only hides them from the
    digest, and the model still learns from every one.
    """

    def test_hard_filter_drops_only_stated_requirements(self):
        df = pd.DataFrame({
            "language_requirement": [None, "Dutch", None],
            "raw_text": [
                TestWrittenLanguage.ENGLISH,   # kept
                TestWrittenLanguage.ENGLISH,   # dropped: states a Dutch requirement
                TestWrittenLanguage.GERMAN,    # KEPT: written in German, but usable
            ],
        })
        assert len(drop_language_blocked(df)) == 2

    def test_german_postings_stay_in_the_training_set(self):
        # The whole point of the split: these carry real preference signal.
        df = pd.DataFrame({
            "language_requirement": [None],
            "raw_text": [TestWrittenLanguage.GERMAN],
        })
        assert len(drop_language_blocked(df)) == 1

    def test_view_filter_hides_foreign_language_postings(self):
        assert matches_language_view(TestWrittenLanguage.GERMAN, hide_non_english=True) is False
        assert matches_language_view(TestWrittenLanguage.ENGLISH, hide_non_english=True) is True

    def test_view_filter_shows_everything_when_disabled(self):
        assert matches_language_view(TestWrittenLanguage.GERMAN, hide_non_english=False) is True

    def test_empty_frame_is_returned_unchanged(self):
        df = pd.DataFrame(columns=["language_requirement", "raw_text"])
        assert drop_language_blocked(df).empty


class TestInflectedLanguages:
    """Polish is the case that exposed two separate bugs, so it gets its own tests.

    The marker list was too thin for a heavily inflected language, and the tokeniser
    only covered Latin-1, so Polish words split apart at their own diacritics and
    could never match. Together those halved the score and let a Polish posting
    through both the filter and, downstream, into the embedder, where its token
    density then broke the benchmark run.
    """

    POLISH = (
        "Forma zatrudnienia umowa o pracę. Lokalizacja Katowice lub Warszawa, "
        "pracujemy dwa dni w tygodniu z biura. Nasz zespół pokrywa kompetencyjnie "
        "obszar Cloud DevOps oraz odpowiada za rozwój platformy. Jeśli chcesz "
        "dołączyć do nas i masz doświadczenie, które jest nam potrzebne, to "
        "czekamy na twoje zgłoszenie. Oferujemy pracę w zespole, który się rozwija."
    )

    def test_polish_posting_is_detected(self):
        language, ratio = detect_written_language(self.POLISH)
        assert language == "polish"
        assert is_written_in_non_english(self.POLISH) is True

    def test_diacritics_do_not_split_words(self):
        # "się" must survive tokenisation as one token. If the character class drops
        # back to Latin-1 it becomes "si" + "e" and stops matching.
        assert detect_written_language(self.POLISH)[1] > 0.10

    def test_polish_has_a_language_code(self):
        # Every detectable language needs one, or the digest tag falls back to the
        # full name and the column stops lining up.
        for language in NON_ENGLISH_MARKERS:
            assert language in LANGUAGE_CODES, f"{language} has no display code"

    def test_english_is_still_not_flagged_as_polish(self):
        english = (
            "We are looking for a cloud engineer to join the platform team in "
            "Amsterdam. You will work on infrastructure as code, CI pipelines and "
            "observability, with a focus on reliability and cost control across our "
            "estate. Experience with Terraform and Kubernetes is what we care about."
        )
        assert is_written_in_non_english(english) is False
