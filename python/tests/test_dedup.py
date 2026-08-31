"""The labeling queue must offer one screen per job, not one per row.

The cases below are the real ones from the database, reduced to their essentials:
a company opening several requisitions for one job, a company re-listing a job
under a fresh id, and a company reusing a title for a genuinely different job.
"""

import pytest

from labeling import dedup
from labeling.db import VacancyToLabel
from ranking.holdout import is_holdout

BOILERPLATE = (
    "At Cisco we are revolutionizing how data and infrastructure connect and protect "
    "organizations in the AI era and beyond. We have been innovating fearlessly for 40 "
    "years to create solutions that power how humans and technology work together. "
) * 3


def make(vacancy_id: int, raw_text: str, title: str = "Software Engineer",
         company: str = "Cisco", location: str = "Bratislava") -> VacancyToLabel:
    return VacancyToLabel(
        id=vacancy_id, title=title, company=company, location=location,
        raw_text=raw_text, summary=None, skills="[]", seniority="mid",
        salary_min=None, salary_max=None, salary_currency=None, salary_period="year",
        language_requirement=None, remote_policy="hybrid",
    )


def ids(groups: list[list]) -> list[list[int]]:
    return sorted([sorted(v.id for v in group) for group in groups])


def non_holdout_ids(count: int) -> list[int]:
    """Ids that all land on the same side of the holdout split.

    Grouping is deliberately confined to one side of it, so a test about text
    similarity has to hold its ids on one side or it measures the wrong thing.
    """
    found = [i for i in range(1, 4000) if not is_holdout(i)]
    assert len(found) >= count
    return found[:count]


def test_identical_copies_collapse_to_one_screen():
    a, b, c = non_holdout_ids(3)
    text = BOILERPLATE + "You will maintain backend services in our microservices architecture."
    groups = dedup.group_duplicates([make(a, text), make(b, text), make(c, text)])
    assert ids(groups) == [[a, b, c]]


def test_one_word_difference_still_collapses():
    """4886 differed from 4882 by "About the Team" vs "Meet the Team", nothing else."""
    a, b = non_holdout_ids(2)
    body = BOILERPLATE + "You will maintain backend services in our microservices architecture."
    groups = dedup.group_duplicates([make(a, "Meet the Team " + body), make(b, "About the Team " + body)])
    assert ids(groups) == [[a, b]]


def test_different_job_under_the_same_title_stays_separate():
    """The case that rules out grouping on (company, title, location) alone.

    Cisco posted a backend role and an AI/Partner Hub role under one title in one
    city. Folding them together would mean the AI role is never shown at all.
    """
    a, b = non_holdout_ids(2)
    backend = BOILERPLATE + "You will maintain backend services in our microservices architecture."
    ai = BOILERPLATE + (
        "You will design autonomous agents in the Partner Hub, bridging AI frameworks "
        "and production systems, and mentor junior engineers on AI best practices."
    )
    groups = dedup.group_duplicates([make(a, backend), make(b, ai)])
    assert ids(groups) == [[a], [b]]


def test_shared_boilerplate_alone_does_not_merge():
    """Two unrelated roles at one company share a lot of words and few sentences."""
    a, b = non_holdout_ids(2)
    groups = dedup.group_duplicates([
        make(a, BOILERPLATE + "Design distributed backend services in Java and Kafka."),
        make(b, BOILERPLATE + "Run payroll operations and manage vendor relationships."),
    ])
    assert ids(groups) == [[a], [b]]


def test_same_role_in_two_cities_stays_separate():
    a, b = non_holdout_ids(2)
    text = BOILERPLATE + "You will maintain backend services in our microservices architecture."
    groups = dedup.group_duplicates([make(a, text), make(b, text, location="Amsterdam")])
    assert ids(groups) == [[a], [b]]


def test_groups_never_straddle_the_holdout():
    """Otherwise the uncertainty sampler writes labels into the evaluation sample.

    The holdout is only worth having because its labels are a random sample. A
    group spanning both sides would be rated from the uncertainty queue and the
    rating written to the holdout copy, which is exactly the boundary-case
    enrichment the holdout exists to avoid.
    """
    text = BOILERPLATE + "You will maintain backend services in our microservices architecture."
    held = next(i for i in range(1, 4000) if is_holdout(i))
    free = next(i for i in range(1, 4000) if not is_holdout(i))

    groups = dedup.group_duplicates([make(held, text), make(free, text)])

    assert ids(groups) == sorted([[held], [free]])


def test_representative_is_the_most_recent_copy():
    """Rows arrive ordered by id, so the last one is what is on the board now."""
    a, b, c = non_holdout_ids(3)
    text = BOILERPLATE + "You will maintain backend services in our microservices architecture."
    groups = dedup.group_duplicates([make(a, text), make(b, text), make(c, text)])
    assert groups[0][-1].id == c


def test_empty_queue():
    assert dedup.group_duplicates([]) == []


@pytest.mark.parametrize("text", ["", "   "])
def test_empty_descriptions_do_not_crash(text):
    a, b = non_holdout_ids(2)
    groups = dedup.group_duplicates([make(a, text), make(b, text)])
    assert ids(groups) == [[a, b]]
