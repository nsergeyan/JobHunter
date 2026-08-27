"""Which postings are reserved as an unbiased evaluation sample.

The problem this solves. Uncertainty sampling asks you to label whatever the
model finds most confusing, which is a good way to spend your attention and a
terrible way to build a test set: the labeled data stops being a random sample of
postings and becomes a portrait of the model's own blind spots. Measure
precision@k on that and the number stops describing "how good is my shortlist"
and starts describing "how hard were the postings I chose to look at".

So a fixed slice is fenced off. Holdout postings are never offered by the
uncertainty sampler, only in random order, which keeps their labels a genuine
random sample that later evaluation can trust.

Membership is derived from the vacancy id by hashing, not stored. That keeps it
stable across runs with no extra table, and independent of insertion order:
ids arrive grouped by source and company, so anything like `id % 5` would risk
tracking that grouping rather than being random with respect to it.
"""

import hashlib

# Large enough to measure with, small enough that most labeling effort still goes
# where it teaches the model most.
HOLDOUT_PERCENT = 20


def is_holdout(vacancy_id: int) -> bool:
    digest = hashlib.sha256(str(vacancy_id).encode("utf-8")).hexdigest()
    return int(digest[:8], 16) % 100 < HOLDOUT_PERCENT
