"""Tests for the ranking metrics.

precision@k and NDCG@k answer different questions, and the difference is the
reason both are reported. precision@k asks "how many of the top k were a yes",
scoring a maybe exactly like a no and treating any ordering of the same k the
same. NDCG uses the whole 0/1/2 scale and cares where in the list each posting
landed. A change that reorders the top five without changing how many yeses are
in it moves NDCG and leaves precision untouched.
"""

import numpy as np
import pandas as pd
import pytest

from ranking.baseline import ndcg_at_k, precision_at_k, time_split_evaluation


def test_precision_counts_only_yes():
    # maybe (1) is not a hit: precision@k is deliberately the stricter question.
    assert precision_at_k(np.array([2, 1, 1, 0, 0]), 5) == pytest.approx(0.2)
    assert precision_at_k(np.array([2, 2, 0, 0, 0]), 5) == pytest.approx(0.4)


def test_precision_is_blind_to_order_within_k():
    best_first = np.array([2, 0, 0, 0, 0])
    worst_first = np.array([0, 0, 0, 0, 2])
    assert precision_at_k(best_first, 5) == precision_at_k(worst_first, 5)


def test_ndcg_is_one_for_the_best_possible_ordering():
    assert ndcg_at_k(np.array([2, 2, 1, 1, 0]), 5) == pytest.approx(1.0)


def test_ndcg_punishes_burying_the_best_match():
    # Same five labels, so precision@5 is identical either way. NDCG is not.
    best_first = np.array([2, 1, 0])
    worst_first = np.array([0, 1, 2])
    assert precision_at_k(best_first, 3) == precision_at_k(worst_first, 3)
    assert ndcg_at_k(best_first, 3) == pytest.approx(1.0)
    assert ndcg_at_k(worst_first, 3) == pytest.approx(0.62, abs=0.01)


def test_ndcg_rewards_order_within_the_positives():
    # A yes above a maybe beats a maybe above a yes, which is exactly the
    # distinction the ordinal model was built to make and precision@k cannot see.
    assert ndcg_at_k(np.array([2, 1]), 2) > ndcg_at_k(np.array([1, 2]), 2)


def test_ndcg_is_zero_when_nothing_is_relevant():
    # Every ordering of all-zeros is equally useless, so scoring it a perfect 1.0
    # would flatter the model.
    assert ndcg_at_k(np.array([0, 0, 0]), 3) == 0.0


def test_ndcg_normalises_against_what_was_achievable():
    # Only one yes exists, and it is ranked first -- that is a perfect result even
    # though most of the list is nos. Without normalisation this would look bad.
    assert ndcg_at_k(np.array([2, 0, 0, 0, 0]), 5) == pytest.approx(1.0)


class TestTimeSplit:
    """The time split exists because cross-validation assumes labels are
    interchangeable. They arrive over time, so a random fold can train on an August
    label and test on a July one, using information the model would not have had."""

    @staticmethod
    def _labels(n=50):
        # Alternating labels so every class appears in both halves, with skills and
        # titles varied enough for the vectorizers to have something to fit.
        return pd.DataFrame({
            "vacancy_id": range(n),
            "title": [f"ml engineer intern {i % 7}" for i in range(n)],
            "company": ["Acme"] * n,
            "raw_text": [f"we are looking for a machine learning intern number {i}" for i in range(n)],
            "skills": ['["Python","SQL"]'] * n,
            "seniority": ["internship"] * n,
            "remote_policy": ["hybrid"] * n,
            "language_requirement": [None] * n,
            "label": [i % 3 for i in range(n)],
            # Strictly increasing, so "the first 40 labels" is unambiguous after sorting.
            "labeled_at": [
                (pd.Timestamp("2026-06-01", tz="UTC") + pd.Timedelta(days=i)).isoformat()
                for i in range(n)
            ],
        })

    def test_split_respects_label_order_not_row_order(self):
        rows = self._labels().sample(frac=1, random_state=0)  # shuffle the frame
        results = time_split_evaluation(rows, train_fraction=0.8)
        assert results["n_train"] == 40 and results["n_test"] == 10
        # The cutoff must come from labeled_at, not from wherever the rows happened
        # to sit after shuffling. Label 40 is 40 days after 2026-06-01.
        assert results["cutoff"] == "2026-07-11"

    def test_train_and_test_partition_every_label(self):
        results = time_split_evaluation(self._labels(60), train_fraction=0.5)
        assert results["n_train"] + results["n_test"] == 60

    def test_reports_how_many_positives_were_available(self):
        # precision@k is uninterpretable without knowing how many yeses could have
        # been found at all.
        results = time_split_evaluation(self._labels())
        assert results["n_positives_in_test"] == sum(
            1 for i in range(50) if i % 3 == 2 and i >= 40
        )

    def test_returns_nothing_when_the_early_labels_miss_a_class(self):
        # A model that has never seen a "yes" cannot rank for one, and reporting a
        # number from it would be misleading rather than merely noisy.
        rows = self._labels()
        rows.loc[rows.index[:40], "label"] = 0
        assert time_split_evaluation(rows) == {}

    def test_requires_the_labeled_at_column(self):
        rows = self._labels().drop(columns=["labeled_at"])
        with pytest.raises(ValueError, match="labeled_at"):
            time_split_evaluation(rows)
