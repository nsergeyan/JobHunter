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

import json

import numpy as np
import pandas as pd
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import StratifiedKFold
from sklearn.metrics import classification_report

from ranking.data import load_labeled_vacancies
from ranking.filters import requires_non_english_language

MIN_SKILL_COUNT = 3
TITLE_MAX_FEATURES = 150
N_FOLDS = 5

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

    def __init__(self) -> None:
        self.skill_vocab: list[str] = []
        self.seniority_categories: list[str] = []
        self.remote_categories: list[str] = []
        self.title_vectorizer = TfidfVectorizer(
            max_features=TITLE_MAX_FEATURES, stop_words="english", ngram_range=(1, 2)
        )

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

        return pd.concat([skill_cols, seniority_cols, remote_cols, title_cols], axis=1)


def precision_at_k(ranked_true_labels: np.ndarray, k: int) -> float:
    top_k = ranked_true_labels[:k]
    return float(np.mean(top_k == 2))


def cross_validate(df: pd.DataFrame, y_original: np.ndarray) -> dict:
    """Runs the N_FOLDS train/test rotation once and returns both the per-fold
    metrics (for an honest performance estimate) and the out-of-fold expected
    rating for every row, aligned to df's original row order -- each row's
    oof_scores entry came from a model that never saw that row during training,
    so the full array is usable as one fair, full-dataset ranking score for the
    benchmark.
    """
    skf = StratifiedKFold(n_splits=N_FOLDS, shuffle=True, random_state=42)
    oof_scores = np.zeros(len(df))
    accuracies = []
    per_class_scores = {name: {"precision": [], "recall": [], "f1-score": []} for name in LABEL_NAMES}
    precision_at_k_scores: dict[int, list[float]] = {5: [], 10: [], 20: []}

    for train_idx, test_idx in skf.split(df, y_original):
        df_train, df_test = df.iloc[train_idx], df.iloc[test_idx]
        y_train, y_test = y_original[train_idx], y_original[test_idx]

        builder = FeatureBuilder().fit(df_train)
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

        ranked_true = y_test[np.argsort(-scores)]
        for k in precision_at_k_scores:
            if k <= len(ranked_true):
                precision_at_k_scores[k].append(precision_at_k(ranked_true, k))

    return {
        "oof_scores": oof_scores,
        "accuracies": accuracies,
        "per_class_scores": per_class_scores,
        "precision_at_k_scores": precision_at_k_scores,
    }


def run_cross_validation(df: pd.DataFrame, y_original: np.ndarray) -> dict:
    results = cross_validate(df, y_original)

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

    # Out-of-fold precision@k pools every row's held-out prediction and ranks the
    # whole list once -- exactly the single ranked shortlist this feeds. This is
    # the headline number (and what ranking.benchmark reports). No +/- because
    # it's one ranking, not an average; read it alongside the per-fold spread.
    ranked_true = y_original[np.argsort(-results["oof_scores"])]
    print("\n=== Precision@k, out-of-fold over the full ranked list -- the headline ===")
    for k in results["precision_at_k_scores"]:
        if k <= len(ranked_true):
            print(f"  precision@{k}: {precision_at_k(ranked_true, k):.2f}")

    return results


def main() -> None:
    df = load_labeled_vacancies()
    before = len(df)
    df = df[~df["language_requirement"].apply(requires_non_english_language)].reset_index(drop=True)
    print(f"Dropped {before - len(df)} postings requiring a non-English language ({len(df)} remain)\n")

    y_original = df["label"].to_numpy()

    run_cross_validation(df, y_original)

    print("\n=== Coefficients from a final model trained on ALL labeled data ===")
    print("(not evaluated -- this model has seen everything, it's here to inspect what it learned)")
    builder = FeatureBuilder().fit(df)
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
