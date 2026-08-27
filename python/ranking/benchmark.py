"""Benchmark (build-order step 5): compares three ways of ranking postings --
the trained logistic regression baseline, an LLM-as-judge, and cosine
similarity against a preference profile -- on the same labeled, English-only
postings. precision@k (true positive = original label 2) is the common
yardstick across all three, so they're judged on equal footing.
"""

import argparse

import numpy as np

from ranking.baseline import cross_validate, precision_at_k
from ranking.data import load_labeled_vacancies
from ranking.embeddings import embedding_scores
from ranking.filters import drop_language_blocked
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
    parser = argparse.ArgumentParser(description="Benchmark the ranking approaches.")
    parser.add_argument(
        "--skip-judge",
        action="store_true",
        help="skip the slow LLM-as-judge pass (it's unchanged by the semantic-feature experiment)",
    )
    args = parser.parse_args()

    df = load_labeled_vacancies()
    before = len(df)
    df = drop_language_blocked(df)
    print(f"Dropped {before - len(df)} postings requiring a non-English language ({len(df)} remain)\n")

    y_original = df["label"].to_numpy()

    # Embed once: the same cosine scores serve BOTH as the untrained cosine
    # baseline AND as the new semantic feature fed into the trained model.
    print("Embedding postings (used for both the semantic feature and the cosine baseline)...")
    cosine_scores = embedding_scores(df)
    df["cosine_score"] = cosine_scores

    print("Cross-validating logistic regression WITHOUT the semantic feature...")
    lr_base = cross_validate(df.drop(columns=["cosine_score"]), y_original)["oof_scores"]
    print("Cross-validating logistic regression WITH the semantic feature...")
    lr_semantic = cross_validate(df, y_original)["oof_scores"]

    llm_scores = None
    if not args.skip_judge:
        print("\nRunning LLM-as-judge (one Ollama call per posting -- this will take a while)...")
        llm_scores = judge_all(df)
        report_score_spread("LLM-judge", llm_scores)

    print("\n" + "=" * 60)
    print(f"BENCHMARK -- precision@k, true positive = original label 2 (n={len(df)})")
    print("=" * 60)
    report_precision_at_k("Logistic regression (baseline features)", lr_base, y_original)
    report_precision_at_k("Logistic regression + semantic feature", lr_semantic, y_original)
    if llm_scores is not None:
        report_precision_at_k("LLM-as-judge", llm_scores.astype(float), y_original)
    report_precision_at_k("Cosine similarity", cosine_scores, y_original)


if __name__ == "__main__":
    main()
