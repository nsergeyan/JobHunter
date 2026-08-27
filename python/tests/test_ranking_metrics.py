"""Tests for the ranking metrics.

precision@k and NDCG@k answer different questions, and the difference is the
reason both are reported. precision@k asks "how many of the top k were a yes",
scoring a maybe exactly like a no and treating any ordering of the same k the
same. NDCG uses the whole 0/1/2 scale and cares where in the list each posting
landed. A change that reorders the top five without changing how many yeses are
in it moves NDCG and leaves precision untouched.
"""

import numpy as np
import pytest

from ranking.baseline import ndcg_at_k, precision_at_k


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
