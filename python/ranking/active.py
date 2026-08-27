"""Ordering the labeling queue by what the model would learn most from.

Labeling is the scarce resource in this project: the model's ceiling is set by
how many postings have been rated, and rating one takes real attention. Offering
them in random order spends that attention uniformly, including on postings the
model already scores confidently, where a label confirms what it knew and teaches
it nothing.

Uncertainty sampling spends it where the model is least sure instead. The measure
is the Shannon entropy of its predicted no/maybe/yes distribution, which peaks
when the probability is spread evenly across all three, and falls to zero when
the model is certain. That is the standard choice and it suits the ordinal setup
here, where "unsure" can mean torn between maybe and yes rather than simply
between yes and no.

The cost is real and is why ranking.holdout exists: labels chosen this way are no
longer a random sample of postings, so they cannot also serve as an honest test
set. Holdout postings are excluded from this ordering entirely.
"""

import numpy as np
import pandas as pd
from sklearn.linear_model import LogisticRegression

from ranking.baseline import FeatureBuilder
from ranking.data import load_labeled_vacancies, load_unlabeled_vacancies
from ranking.filters import requires_non_english_language

# Below this, the model is too poorly informed for its confusion to mean anything,
# so its ordering would be little better than noise dressed up as strategy.
MIN_LABELS_TO_RANK = 40


def prediction_entropy(proba: np.ndarray) -> np.ndarray:
    """Shannon entropy per row, in bits.

    Maximal (log2(3), about 1.58) when the model splits its probability evenly
    across no/maybe/yes and genuinely has no idea. Zero when it is certain.
    """
    safe = np.clip(proba, 1e-12, 1.0)
    return -np.sum(safe * np.log2(safe), axis=1)


def uncertainty_by_vacancy_id() -> dict[int, float]:
    """Map each unlabeled posting's id to how unsure the model is about it.

    Returns an empty mapping when there is not enough labeled data to train on,
    which callers should read as "fall back to random order".
    """
    labeled = load_labeled_vacancies()
    labeled = labeled[~labeled["language_requirement"].apply(requires_non_english_language)]
    if len(labeled) < MIN_LABELS_TO_RANK or labeled["label"].nunique() < 3:
        return {}

    unlabeled = load_unlabeled_vacancies()
    if unlabeled.empty:
        return {}

    builder = FeatureBuilder().fit(labeled)
    model = LogisticRegression(max_iter=1000, class_weight="balanced")
    model.fit(builder.transform(labeled), labeled["label"].to_numpy())

    entropy = prediction_entropy(model.predict_proba(builder.transform(unlabeled)))
    return dict(zip(unlabeled["vacancy_id"].astype(int), entropy.astype(float)))
