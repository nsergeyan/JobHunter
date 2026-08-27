"""Ordinal ranking baseline for vacancies (build-order step 4).

Jobs are rated 0/1/2 (no / maybe / yes) and the shortlist should be sorted the
same way: yes on top, maybe in the middle, no at the bottom. The model therefore
keeps all three labels as separate, *ordered* classes instead of collapsing them.

How the score works. A multinomial logistic regression predicts three
probabilities per posting -- P(no), P(maybe), P(yes) -- and we turn them into a
single number, the EXPECTED RATING:

    score = 0*P(no) + 1*P(maybe) + 2*P(yes)

A confident yes lands near 2, a maybe near 1, a no near 0. Sorting by this score
produces the yes > maybe > no ordering the shortlist needs. An earlier binary
framing (interested vs not, with a sample-weight bump for 2s) could not: it put
yes and maybe in the same "interested" bucket, leaving no way to float true
yeses above maybes at the very top -- precisely the bar precision@k measures. On
the current labeled set the ordinal score clearly out-ranks the binary one at the
top (out-of-fold precision@5/@10 of 0.80/0.70 vs 0.60/0.50).

Class imbalance (~55 yeses in ~405 rows) is handled with class_weight="balanced"
so the rare yes/maybe classes are not drowned out by nos, rather than the old
hand-tuned STRONG_FIT_WEIGHT.

Company name is deliberately excluded as a feature: a third of the labeled set
is a single company (Bosch), so the model would partly learn "Bosch -> no"
instead of transferable skill/role signal, which would not generalize to unseen
companies. Salary is excluded too since it is missing on ~95% of rows.
"""

import argparse
import json

import numpy as np
import pandas as pd
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import StratifiedKFold
from sklearn.metrics import classification_report

from ranking.data import load_labeled_vacancies
from ranking.embeddings import embedding_scores
from ranking.filters import drop_non_english

MIN_SKILL_COUNT = 3
TITLE_MAX_FEATURES = 150

# Description TF-IDF. The title carries the role, but the body is where a posting
# says whether the ML is real or decorative, and it was going unused as features.
# max_df drops terms that show up in most postings, which is what kills the
# boilerplate (equal-opportunity statements, benefits lists, "fast-paced
# environment") that would otherwise crowd out the vocabulary budget.
DESCRIPTION_MAX_FEATURES = 300
DESCRIPTION_MIN_DF = 3
DESCRIPTION_MAX_DF = 0.8
N_FOLDS = 5

# One fold split is a lottery at this sample size: with ~66 positives, a single
# precision@5 moves by 0.20 if one posting changes place. Repeating the whole
# cross-validation under several shuffles and reporting the spread is what makes
# "A beats B" mean something rather than "A got a luckier split than B".
CV_SEEDS = (42, 43, 44, 45, 46)

# The ordinal scale the model ranks on: no=0, maybe=1, yes=2. The expected
# rating is the probability-weighted average of these, so it always falls
# between 0 and 2 and sorts postings yes > maybe > no.
RATING_SCALE = np.array([0, 1, 2])

LABEL_NAMES = ["no", "maybe", "yes"]


def expected_rating(proba: np.ndarray) -> np.ndarray:
    """Probability-weighted rating: proba is (n_rows, 3) for classes 0/1/2, the
    result is (n_rows,) with each value in [0, 2]. Higher = closer to a yes.
    """
    return proba @ RATING_SCALE


class FeatureBuilder:
    """Fits skill/category vocab and the title vectorizer on the training set
    only, then applies the same vocab to any other set -- avoids leaking
    test-set vocabulary into training.
    """

    def __init__(self, use_description: bool = False) -> None:
        self.use_description = use_description
        self.skill_vocab: list[str] = []
        self.seniority_categories: list[str] = []
        self.remote_categories: list[str] = []
        self.title_vectorizer = TfidfVectorizer(
            max_features=TITLE_MAX_FEATURES, stop_words="english", ngram_range=(1, 2)
        )
        # Fitted on the TRAIN fold only, like everything else here, so test-set
        # wording never leaks into the vocabulary. None when the feature is off.
        self.description_vectorizer = (
            TfidfVectorizer(
                max_features=DESCRIPTION_MAX_FEATURES,
                min_df=DESCRIPTION_MIN_DF,
                max_df=DESCRIPTION_MAX_DF,
                stop_words="english",
                ngram_range=(1, 2),
                sublinear_tf=True,
            )
            if use_description
            else None
        )

        # Min/max for the optional semantic feature (cosine similarity to the
        # preference profile), learned on the TRAIN fold only. Stay None unless a
        # df carrying a "cosine_score" column is seen in fit().
        self.cosine_min: float | None = None
        self.cosine_max: float | None = None

    @staticmethod
    def _parse_skills(raw: str | None) -> list[str]:
        if not raw:
            return []
        try:
            return json.loads(raw)
        except (json.JSONDecodeError, TypeError):
            return []

    def fit(self, df: pd.DataFrame) -> "FeatureBuilder":
        skill_counts: dict[str, int] = {}
        for raw in df["skills"]:
            for skill in self._parse_skills(raw):
                skill_counts[skill] = skill_counts.get(skill, 0) + 1
        self.skill_vocab = sorted(s for s, n in skill_counts.items() if n >= MIN_SKILL_COUNT)

        self.seniority_categories = sorted(df["seniority"].fillna("unknown").unique())
        self.remote_categories = sorted(df["remote_policy"].fillna("unknown").unique())

        self.title_vectorizer.fit(df["title"].fillna(""))

        if self.description_vectorizer is not None:
            self.description_vectorizer.fit(df["raw_text"].fillna(""))

        # Optional semantic feature. Learn the [0, 1] scaling on the train fold
        # only so it sits on the same range as the 0/1 and TF-IDF features and
        # isn't unfairly shrunk (or inflated) by L2 regularization.
        if "cosine_score" in df.columns:
            self.cosine_min = float(df["cosine_score"].min())
            self.cosine_max = float(df["cosine_score"].max())
        return self

    def transform(self, df: pd.DataFrame) -> pd.DataFrame:
        skill_cols = pd.DataFrame(
            [[1 if skill in self._parse_skills(raw) else 0 for skill in self.skill_vocab] for raw in df["skills"]],
            columns=[f"skill:{s}" for s in self.skill_vocab],
            index=df.index,
        )

        seniority_cols = pd.get_dummies(
            pd.Categorical(df["seniority"].fillna("unknown"), categories=self.seniority_categories),
            prefix="seniority",
        ).set_axis(df.index)

        remote_cols = pd.get_dummies(
            pd.Categorical(df["remote_policy"].fillna("unknown"), categories=self.remote_categories),
            prefix="remote",
        ).set_axis(df.index)

        title_matrix = self.title_vectorizer.transform(df["title"].fillna("")).toarray()
        title_cols = pd.DataFrame(
            title_matrix,
            columns=[f"title:{t}" for t in self.title_vectorizer.get_feature_names_out()],
            index=df.index,
        )

        feature_frames = [skill_cols, seniority_cols, remote_cols, title_cols]

        if self.description_vectorizer is not None:
            description_matrix = self.description_vectorizer.transform(df["raw_text"].fillna("")).toarray()
            feature_frames.append(
                pd.DataFrame(
                    description_matrix,
                    columns=[f"desc:{t}" for t in self.description_vectorizer.get_feature_names_out()],
                    index=df.index,
                )
            )

        # Semantic feature, only when the caller attached a cosine_score column
        # (so digest.py and other callers that don't embed keep working). Scale
        # with the train-fold min/max; clip because a test row can fall outside it.
        if "cosine_score" in df.columns and self.cosine_min is not None:
            span = self.cosine_max - self.cosine_min
            if span > 0:
                scaled = ((df["cosine_score"] - self.cosine_min) / span).clip(0.0, 1.0)
            else:
                scaled = pd.Series(0.5, index=df.index)
            feature_frames.append(
                pd.DataFrame({"semantic:cosine_sim": scaled.to_numpy()}, index=df.index)
            )

        return pd.concat(feature_frames, axis=1)


def precision_at_k(ranked_true_labels: np.ndarray, k: int) -> float:
    top_k = ranked_true_labels[:k]
    return float(np.mean(top_k == 2))


def _dcg(gains: np.ndarray) -> float:
    """Discounted cumulative gain: each posting's value divided by a log of how
    far down the list it sits, so a good match at rank 1 is worth more than the
    same match at rank 10."""
    positions = np.arange(1, len(gains) + 1)
    return float(np.sum(gains / np.log2(positions + 1)))


def ndcg_at_k(ranked_true_labels: np.ndarray, k: int) -> float:
    """Normalised DCG over the top k, using the 0/1/2 label directly as the gain.

    Why this sits next to precision@k rather than replacing it. precision@k asks
    one question: of the top k, how many were a "yes"? It scores a maybe exactly
    like a no, and it cannot tell a list of five yeses in the right order from
    the same five shuffled. NDCG uses the whole ordinal scale the model was built
    to predict, and it rewards putting the best match first, which is what the
    digest actually needs.

    Gains are linear (0, 1, 2) rather than the exponential 2^rel - 1 convention.
    On a three-point scale the exponential form mostly just declares a yes worth
    three maybes, which is a claim about preference nobody here has made.

    Normalised against the best achievable ordering of the same labels, so 1.0
    means "could not have ranked these better" and the number stays comparable
    across folds with different numbers of positives.
    """
    top_k = ranked_true_labels[:k]
    ideal = np.sort(ranked_true_labels)[::-1][:k]
    ideal_dcg = _dcg(ideal)
    if ideal_dcg == 0:
        # No positives at all in this slice: every ordering is equally good, so
        # calling it a perfect 1.0 would flatter the model. Score it 0.
        return 0.0
    return _dcg(top_k) / ideal_dcg


def cross_validate(
    df: pd.DataFrame,
    y_original: np.ndarray,
    use_description: bool = False,
    random_state: int = 42,
) -> dict:
    """Runs the N_FOLDS train/test rotation once and returns both the per-fold
    metrics (for an honest performance estimate) and the out-of-fold expected
    rating for every row, aligned to df's original row order -- each row's
    oof_scores entry came from a model that never saw that row during training,
    so the full array is usable as one fair, full-dataset ranking score for the
    benchmark.

    random_state picks the fold shuffle. Vary it (see repeated_cross_validate) to
    separate a real improvement from a lucky split.
    """
    skf = StratifiedKFold(n_splits=N_FOLDS, shuffle=True, random_state=random_state)
    oof_scores = np.zeros(len(df))
    accuracies = []
    per_class_scores = {name: {"precision": [], "recall": [], "f1-score": []} for name in LABEL_NAMES}
    precision_at_k_scores: dict[int, list[float]] = {5: [], 10: [], 20: []}
    ndcg_at_k_scores: dict[int, list[float]] = {5: [], 10: [], 20: []}

    for train_idx, test_idx in skf.split(df, y_original):
        df_train, df_test = df.iloc[train_idx], df.iloc[test_idx]
        y_train, y_test = y_original[train_idx], y_original[test_idx]

        builder = FeatureBuilder(use_description=use_description).fit(df_train)
        X_train = builder.transform(df_train)
        X_test = builder.transform(df_test)

        model = LogisticRegression(max_iter=1000, class_weight="balanced")
        model.fit(X_train, y_train)

        preds = model.predict(X_test)
        scores = expected_rating(model.predict_proba(X_test))
        oof_scores[test_idx] = scores

        report = classification_report(
            y_test, preds, labels=[0, 1, 2], target_names=LABEL_NAMES, output_dict=True, zero_division=0
        )
        accuracies.append(report["accuracy"])
        for class_name in per_class_scores:
            for metric in per_class_scores[class_name]:
                per_class_scores[class_name][metric].append(report[class_name][metric])

        ranked_true = y_test[np.argsort(-scores, kind="stable")]
        for k in precision_at_k_scores:
            if k <= len(ranked_true):
                precision_at_k_scores[k].append(precision_at_k(ranked_true, k))
                ndcg_at_k_scores[k].append(ndcg_at_k(ranked_true, k))

    return {
        "oof_scores": oof_scores,
        "accuracies": accuracies,
        "per_class_scores": per_class_scores,
        "precision_at_k_scores": precision_at_k_scores,
        "ndcg_at_k_scores": ndcg_at_k_scores,
    }


TIME_SPLIT_TRAIN_FRACTION = 0.8


def time_split_evaluation(
    df: pd.DataFrame,
    use_description: bool = False,
    train_fraction: float = TIME_SPLIT_TRAIN_FRACTION,
) -> dict:
    """Train on the labels made first, test on the ones made later.

    Cross-validation shuffles labels together and assumes they are interchangeable.
    They are not, quite: they arrive over time, the postings on offer shift with the
    hiring season, and taste drifts with them. A random fold can train on a label
    made in August and test on one from July, which is information the model would
    not have had.

    Splitting on labeled_at removes that. It is the closest thing here to the
    question the digest actually asks -- given everything rated so far, how well
    does the ranking hold up on postings not yet rated -- and it is the number to
    watch if precision@k ever looks strong in cross-validation but disappointing in
    daily use.

    One honest caveat. labeled_at is when the posting was RATED, not when it
    appeared, and labels have so far been collected in random order. That makes this
    split close to a random one today, so it mostly serves as a baseline to compare
    against later. It gets sharper once uncertainty sampling changes what gets rated
    when (see ranking.active).
    """
    if "labeled_at" not in df.columns:
        raise ValueError("time_split_evaluation needs a labeled_at column -- load via load_labeled_vacancies")

    ordered = df.sort_values("labeled_at", kind="stable").reset_index(drop=True)
    cut = int(len(ordered) * train_fraction)
    train, test = ordered.iloc[:cut], ordered.iloc[cut:]

    y_train = train["label"].to_numpy()
    y_test = test["label"].to_numpy()
    if len(test) == 0 or len(np.unique(y_train)) < len(LABEL_NAMES):
        return {}

    builder = FeatureBuilder(use_description=use_description).fit(train)
    model = LogisticRegression(max_iter=1000, class_weight="balanced")
    model.fit(builder.transform(train), y_train)

    scores = expected_rating(model.predict_proba(builder.transform(test)))
    ranked_true = y_test[np.argsort(-scores, kind="stable")]

    return {
        "n_train": len(train),
        "n_test": len(test),
        "n_positives_in_test": int(np.sum(y_test == 2)),
        "cutoff": str(ordered.loc[cut, "labeled_at"])[:10],
        "precision": {k: precision_at_k(ranked_true, k) for k in (5, 10, 20) if k <= len(ranked_true)},
        "ndcg": {k: ndcg_at_k(ranked_true, k) for k in (5, 10, 20) if k <= len(ranked_true)},
    }


def report_time_split(results: dict) -> None:
    if not results:
        print("\nToo few labels, or too few classes among the earliest ones, for a time split.")
        return
    print(f"\n=== Time-based split: trained on the first {results['n_train']} labels, "
          f"tested on the {results['n_test']} rated after {results['cutoff']} ===")
    print(f"({results['n_positives_in_test']} of the held-out postings were a 'yes')")
    for k, score in results["precision"].items():
        print(f"  precision@{k}: {score:.2f}")
    for k, score in results["ndcg"].items():
        print(f"  ndcg@{k}:      {score:.2f}")


def repeated_cross_validate(
    df: pd.DataFrame,
    y_original: np.ndarray,
    use_description: bool = False,
    seeds: tuple[int, ...] = CV_SEEDS,
) -> dict:
    """Run the whole cross-validation once per seed and collect the out-of-fold
    ranking metrics from each.

    Each seed produces one complete out-of-fold ranking of every labeled posting,
    scored by models that never saw the row they are scoring. Comparing the mean
    and spread across seeds is the difference between "this feature helps" and
    "this feature got a friendlier shuffle".
    """
    per_seed: dict[str, dict[int, list[float]]] = {
        "precision": {5: [], 10: [], 20: []},
        "ndcg": {5: [], 10: [], 20: []},
    }
    for seed in seeds:
        results = cross_validate(df, y_original, use_description, random_state=seed)
        ranked_true = y_original[np.argsort(-results["oof_scores"], kind="stable")]
        for k in per_seed["precision"]:
            if k <= len(ranked_true):
                per_seed["precision"][k].append(precision_at_k(ranked_true, k))
                per_seed["ndcg"][k].append(ndcg_at_k(ranked_true, k))
    return per_seed


def report_repeated(label: str, per_seed: dict, seeds: tuple[int, ...] = CV_SEEDS) -> None:
    print(f"\n=== {label}: out-of-fold across {len(seeds)} seeds (mean +/- std) ===")
    for metric in ("precision", "ndcg"):
        for k, scores in per_seed[metric].items():
            if scores:
                print(f"  {metric}@{k:<3}{np.mean(scores):.2f} (+/- {np.std(scores):.2f})")


def run_cross_validation(df: pd.DataFrame, y_original: np.ndarray, use_description: bool = False) -> dict:
    results = cross_validate(df, y_original, use_description)

    print(f"=== {N_FOLDS}-fold cross-validation (averaged across folds) ===")
    print(f"accuracy: {np.mean(results['accuracies']):.2f} (+/- {np.std(results['accuracies']):.2f})")
    for class_name, metrics in results["per_class_scores"].items():
        print(
            f"  {class_name:6s} precision {np.mean(metrics['precision']):.2f}  "
            f"recall {np.mean(metrics['recall']):.2f}  f1 {np.mean(metrics['f1-score']):.2f}"
        )

    # Per-fold precision@k ranks each fold's small test slice on its own and
    # averages. The +/- std is the useful part: it shows how much the score
    # wobbles given how little data there is. At small k it understates real
    # performance, since ranking the top 5 of ~80 rows is a harder, noisier task
    # than ranking the top 5 of the full list.
    print("\n=== Precision@k, per-fold average -- reliability/spread (true positive = original label 2) ===")
    for k, scores in results["precision_at_k_scores"].items():
        if scores:
            print(f"  precision@{k}: {np.mean(scores):.2f} (+/- {np.std(scores):.2f})")
    for k, scores in results["ndcg_at_k_scores"].items():
        if scores:
            print(f"  ndcg@{k}:      {np.mean(scores):.2f} (+/- {np.std(scores):.2f})")

    # Out-of-fold precision@k pools every row's held-out prediction and ranks the
    # whole list once -- exactly the single ranked shortlist this feeds. This is
    # the headline number (and what ranking.benchmark reports). No +/- because
    # it's one ranking, not an average; read it alongside the per-fold spread.
    ranked_true = y_original[np.argsort(-results["oof_scores"], kind="stable")]
    print("\n=== Precision@k, out-of-fold over the full ranked list -- the headline ===")
    for k in results["precision_at_k_scores"]:
        if k <= len(ranked_true):
            print(f"  precision@{k}: {precision_at_k(ranked_true, k):.2f}")
    print("\n=== NDCG@k, out-of-fold -- same ranking, scored on the full 0/1/2 scale ===")
    for k in results["ndcg_at_k_scores"]:
        if k <= len(ranked_true):
            print(f"  ndcg@{k}: {ndcg_at_k(ranked_true, k):.2f}")

    return results


def main() -> None:
    parser = argparse.ArgumentParser(description="Train + cross-validate the ranking baseline.")
    parser.add_argument(
        "--semantic",
        action="store_true",
        help="add the cosine-similarity-to-preference feature (needs Ollama embeddings; slower)",
    )
    parser.add_argument(
        "--description",
        action="store_true",
        help="add TF-IDF features over the posting description (raw_text), not just the title",
    )
    parser.add_argument(
        "--time-split",
        action="store_true",
        help="also evaluate by training on the earliest labels and testing on the most recent",
    )
    parser.add_argument(
        "--compare",
        action="store_true",
        help="repeat CV across seeds with and without --description, to see whether it actually helps",
    )
    args = parser.parse_args()

    df = load_labeled_vacancies()
    before = len(df)
    df = drop_non_english(df)
    print(f"Dropped {before - len(df)} postings requiring a non-English language ({len(df)} remain)\n")

    if args.semantic:
        print("Embedding postings for the cosine-similarity feature (one Ollama call each)...")
        df["cosine_score"] = embedding_scores(df)

    y_original = df["label"].to_numpy()

    run_cross_validation(df, y_original, use_description=args.description)

    # One split is a lottery at this sample size, so the verdict on any feature
    # comes from the across-seed spread, never from a single run.
    if args.compare:
        print("\nRepeating cross-validation across seeds, with and without the description features...")
        report_repeated("baseline features", repeated_cross_validate(df, y_original, use_description=False))
        report_repeated("+ description TF-IDF", repeated_cross_validate(df, y_original, use_description=True))
    else:
        label = "+ description TF-IDF" if args.description else "baseline features"
        report_repeated(label, repeated_cross_validate(df, y_original, use_description=args.description))

    if args.time_split:
        report_time_split(time_split_evaluation(df, use_description=args.description))

    print("\n=== Coefficients from a final model trained on ALL labeled data ===")
    print("(not evaluated -- this model has seen everything, it's here to inspect what it learned)")
    builder = FeatureBuilder(use_description=args.description).fit(df)
    X_all = builder.transform(df)
    model = LogisticRegression(max_iter=1000, class_weight="balanced")
    model.fit(X_all, y_original)

    # coef_ has one row per class (0/1/2). "yes minus no" is a single readable
    # axis: how much each feature pushes a posting toward yes rather than no.
    yes_vs_no = model.coef_[2] - model.coef_[0]
    coefs = pd.Series(yes_vs_no, index=X_all.columns).sort_values()
    print("\nStrongest signals toward 'no':")
    print(coefs.head(10).to_string())
    print("\nStrongest signals toward 'yes':")
    print(coefs.tail(10).to_string())


if __name__ == "__main__":
    main()
