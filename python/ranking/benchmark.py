"""Benchmark (build-order step 5): compares three ways of ranking postings --
the trained logistic regression baseline, an LLM-as-judge, and cosine
similarity against a preference profile -- on the same labeled, English-only
postings. precision@k (true positive = original label 2) is the common
yardstick across all three, so they're judged on equal footing.
"""

import numpy as np

from ranking.baseline import cross_validate, precision_at_k
from ranking.data import load_labeled_vacancies
from ranking.embeddings import embedding_scores
from ranking.filters import requires_non_english_language
from ranking.llm_judge import judge_all


def report_precision_at_k(name: str, scores: np.ndarray, y_original: np.ndarray) -> None:
    ranked_true = y_original[np.argsort(-scores, kind="stable")]
    print(f"\n{name}:")
    for k in (5, 10, 20):
        if k <= len(ranked_true):
            print(f"  precision@{k}: {precision_at_k(ranked_true, k):.2f}")


def report_score_spread(name: str, scores: np.ndarray) -> None:
    """Sanity check that the score actually spreads out instead of clustering
    on a handful of values (which would just reintroduce the tie problem).
    """
    n_unique = len(np.unique(scores))
    print(
        f"{name} score spread: min={scores.min():.0f} max={scores.max():.0f} "
        f"mean={scores.mean():.1f}  ({n_unique}/{len(scores)} unique values)"
    )


def main() -> None:
    df = load_labeled_vacancies()
    before = len(df)
    df = df[~df["language_requirement"].apply(requires_non_english_language)].reset_index(drop=True)
    print(f"Dropped {before - len(df)} postings requiring a non-English language ({len(df)} remain)\n")

    y_original = df["label"].to_numpy()

    print("Running logistic regression cross-validation for out-of-fold scores...")
    cv_results = cross_validate(df, y_original)
    lr_scores = cv_results["oof_scores"]

    print("\nRunning LLM-as-judge (one Ollama call per posting -- this will take a while)...")
    llm_scores = judge_all(df)
    report_score_spread("LLM-judge", llm_scores)

    print("\nEmbedding preference profile and postings for cosine similarity...")
    cosine_scores = embedding_scores(df)

    print("\n" + "=" * 60)
    print(f"BENCHMARK -- precision@k, true positive = original label 2 (n={len(df)})")
    print("=" * 60)
    report_precision_at_k("Logistic regression (out-of-fold)", lr_scores, y_original)
    report_precision_at_k("LLM-as-judge", llm_scores.astype(float), y_original)
    report_precision_at_k("Cosine similarity", cosine_scores, y_original)


if __name__ == "__main__":
    main()
