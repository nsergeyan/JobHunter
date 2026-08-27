"""Tests for the labeling queue order and the evaluation holdout.

The property worth protecting is the separation: postings chosen because the
model finds them confusing must never leak into the sample used to judge the
model, or precision@k stops measuring shortlist quality and starts measuring how
hard the postings were that got picked.
"""

import numpy as np
import pytest

from labeling import cli
from ranking.active import prediction_entropy
from ranking.holdout import HOLDOUT_PERCENT, is_holdout


class _Vacancy:
    """Stands in for db.VacancyToLabel: order_queue only ever reads .id."""

    def __init__(self, vacancy_id: int) -> None:
        self.id = vacancy_id


class TestHoldout:
    def test_membership_is_stable_across_calls(self):
        # Derived from the id rather than stored, so it has to be deterministic or
        # the evaluation sample would reshuffle on every run.
        assert [is_holdout(i) for i in range(50)] == [is_holdout(i) for i in range(50)]

    def test_roughly_the_configured_share_is_reserved(self):
        reserved = sum(is_holdout(i) for i in range(5000))
        share = 100 * reserved / 5000
        assert abs(share - HOLDOUT_PERCENT) < 3, f"reserved {share:.1f}%, expected ~{HOLDOUT_PERCENT}%"

    def test_membership_does_not_track_consecutive_ids(self):
        # Ids arrive grouped by source and company. If membership followed a simple
        # cycle it could line up with that grouping instead of being independent.
        flags = [is_holdout(i) for i in range(1000)]
        for period in (2, 3, 4, 5, 10):
            cycle = flags[:period]
            assert flags != cycle * (1000 // period), f"membership repeats with period {period}"


class TestPredictionEntropy:
    def test_peaks_when_the_model_has_no_idea(self):
        even = np.array([[1 / 3, 1 / 3, 1 / 3]])
        assert prediction_entropy(even)[0] == pytest.approx(np.log2(3), abs=1e-6)

    def test_is_zero_when_the_model_is_certain(self):
        certain = np.array([[1.0, 0.0, 0.0]])
        assert prediction_entropy(certain)[0] == pytest.approx(0.0, abs=1e-6)

    def test_torn_between_maybe_and_yes_counts_as_uncertain(self):
        # The ordinal setup means "unsure" is often a maybe/yes split, not a yes/no
        # one, and that has to register as worth labeling.
        torn = np.array([[0.0, 0.5, 0.5]])
        confident = np.array([[0.1, 0.85, 0.05]])
        assert prediction_entropy(torn)[0] > prediction_entropy(confident)[0]


class TestQueueOrder:
    @staticmethod
    def _split_ids(limit=400):
        held = [i for i in range(limit) if is_holdout(i)]
        free = [i for i in range(limit) if not is_holdout(i)]
        return held, free

    def test_uncertain_order_never_offers_holdout_postings(self, monkeypatch):
        held, free = self._split_ids()
        rows = [_Vacancy(i) for i in held + free]
        monkeypatch.setattr(cli, "uncertainty_by_vacancy_id", lambda: {i: 1.0 for i in held + free})

        queue, description = cli.order_queue(rows, "uncertain")

        assert {v.id for v in queue}.isdisjoint(held), "holdout leaked into the uncertainty queue"
        assert "holdout excluded" in description

    def test_uncertain_order_puts_the_least_confident_first(self, monkeypatch):
        _, free = self._split_ids()
        chosen = free[:4]
        rows = [_Vacancy(i) for i in chosen]
        scores = dict(zip(chosen, [0.2, 1.5, 0.9, 1.2]))
        monkeypatch.setattr(cli, "uncertainty_by_vacancy_id", lambda: scores)

        queue, _ = cli.order_queue(rows, "uncertain")

        assert [v.id for v in queue] == sorted(chosen, key=lambda i: -scores[i])

    def test_unscored_postings_sort_last(self, monkeypatch):
        # A missing score means the model never saw the row, which is not the same
        # as being certain about it, so it must not jump the queue.
        _, free = self._split_ids()
        scored, unscored = free[0], free[1]
        rows = [_Vacancy(unscored), _Vacancy(scored)]
        monkeypatch.setattr(cli, "uncertainty_by_vacancy_id", lambda: {scored: 0.01})

        queue, _ = cli.order_queue(rows, "uncertain")

        assert [v.id for v in queue] == [scored, unscored]

    def test_falls_back_to_random_when_the_model_cannot_rank_yet(self, monkeypatch):
        _, free = self._split_ids()
        rows = [_Vacancy(i) for i in free[:10]]
        monkeypatch.setattr(cli, "uncertainty_by_vacancy_id", dict)

        queue, description = cli.order_queue(rows, "uncertain")

        assert len(queue) == 10
        assert "not enough labels" in description

    def test_random_order_covers_everything_including_holdout(self):
        held, free = self._split_ids()
        rows = [_Vacancy(i) for i in held + free]

        queue, description = cli.order_queue(rows, "random")

        assert {v.id for v in queue} == set(held + free)
        assert "holdout included" in description

    def test_holdout_mode_offers_only_reserved_postings(self):
        held, free = self._split_ids()
        rows = [_Vacancy(i) for i in held + free]

        queue, _ = cli.order_queue(rows, "holdout")

        assert {v.id for v in queue} == set(held)
