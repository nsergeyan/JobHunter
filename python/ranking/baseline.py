"""Logistic regression baseline for ranking vacancies (build-order step 4).

Target: binary, collapsing Narek's 0/1/2 fit labels into 0 = not interested,
{1, 2} = interested. This is a data-efficiency call -- there are only ~30 `2`
labels, too few to train on alone, but plenty of signal in `interested vs not`.
The original 0/1/2 label is kept alongside so we can evaluate precision@k
against the stricter "true yes" (label == 2) bar, since that's the bar that
will matter once this feeds a daily shortlist.

Company name is deliberately excluded as a feature: a third of the labeled set
is a single company (Bosch), so the model would partly learn "Bosch -> no"
instead of learning which skills/roles Narek actually likes, and that wouldn't
generalize to companies it hasn't seen. Salary is excluded too since it's
missing on ~95% of rows.
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


def cross_validate(df: pd.DataFrame, y_binary: np.ndarray, y_original: np.ndarray) -> dict:
    """Runs the N_FOLDS train/test rotation once and returns both the per-fold
    metrics (for an honest performance estimate) and the out-of-fold probability
    for every row, aligned to df's original row order -- each row's oof_probs
    entry came from a model that never saw that row during training, so the
    full array is usable as one fair, full-dataset score for the benchmark.
    """
    skf = StratifiedKFold(n_splits=N_FOLDS, shuffle=True, random_state=42)
    oof_probs = np.zeros(len(df))
    accuracies = []
    per_class_scores = {"not interested": {"precision": [], "recall": [], "f1-score": []},
                         "interested": {"precision": [], "recall": [], "f1-score": []}}
    precision_at_k_scores: dict[int, list[float]] = {5: [], 10: [], 20: []}

    for train_idx, test_idx in skf.split(df, y_binary):
        df_train, df_test = df.iloc[train_idx], df.iloc[test_idx]
        y_train, y_test = y_binary[train_idx], y_binary[test_idx]
        y_orig_test = y_original[test_idx]

        builder = FeatureBuilder().fit(df_train)
        X_train = builder.transform(df_train)
        X_test = builder.transform(df_test)

        model = LogisticRegression(max_iter=1000)
        model.fit(X_train, y_train)

        preds = model.predict(X_test)
        probs = model.predict_proba(X_test)[:, 1]
        oof_probs[test_idx] = probs

        report = classification_report(
            y_test, preds, target_names=["not interested", "interested"], output_dict=True, zero_division=0
        )
        accuracies.append(report["accuracy"])
        for class_name in per_class_scores:
            for metric in per_class_scores[class_name]:
                per_class_scores[class_name][metric].append(report[class_name][metric])

        ranked_true = y_orig_test[np.argsort(-probs)]
        for k in precision_at_k_scores:
            if k <= len(ranked_true):
                precision_at_k_scores[k].append(precision_at_k(ranked_true, k))

    return {
        "oof_probs": oof_probs,
        "accuracies": accuracies,
        "per_class_scores": per_class_scores,
        "precision_at_k_scores": precision_at_k_scores,
    }


def run_cross_validation(df: pd.DataFrame, y_binary: np.ndarray, y_original: np.ndarray) -> dict:
    results = cross_validate(df, y_binary, y_original)

    print(f"=== {N_FOLDS}-fold cross-validation (averaged across folds) ===")
    print(f"accuracy: {np.mean(results['accuracies']):.2f} (+/- {np.std(results['accuracies']):.2f})")
    for class_name, metrics in results["per_class_scores"].items():
        print(
            f"  {class_name:15s} precision {np.mean(metrics['precision']):.2f}  "
            f"recall {np.mean(metrics['recall']):.2f}  f1 {np.mean(metrics['f1-score']):.2f}"
        )

    print("\n=== Precision@k, averaged across folds (true positive = original label 2) ===")
    for k, scores in results["precision_at_k_scores"].items():
        if scores:
            print(f"  precision@{k}: {np.mean(scores):.2f} (+/- {np.std(scores):.2f})")

    return results


def main() -> None:
    df = load_labeled_vacancies()
    before = len(df)
    df = df[~df["language_requirement"].apply(requires_non_english_language)].reset_index(drop=True)
    print(f"Dropped {before - len(df)} postings requiring a non-English language ({len(df)} remain)\n")

    y_original = df["label"].to_numpy()
    y_binary = (df["label"] >= 1).astype(int).to_numpy()

    run_cross_validation(df, y_binary, y_original)

    print("\n=== Coefficients from a final model trained on ALL labeled data ===")
    print("(not evaluated -- this model has seen everything, it's here to inspect what it learned)")
    builder = FeatureBuilder().fit(df)
    X_all = builder.transform(df)
    model = LogisticRegression(max_iter=1000)
    model.fit(X_all, y_binary)

    coefs = pd.Series(model.coef_[0], index=X_all.columns).sort_values()
    print("\nStrongest negative weights (push toward 'not interested'):")
    print(coefs.head(10).to_string())
    print("\nStrongest positive weights (push toward 'interested'):")
    print(coefs.tail(10).to_string())


if __name__ == "__main__":
    main()
