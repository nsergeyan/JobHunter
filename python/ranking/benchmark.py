"""Benchmark (build-order step 5): compares the trained logistic regression
against an LLM-as-judge and cosine similarity to a preference profile, on the
same labeled postings. precision@k and NDCG@k (true positive = original label 2)
are the common yardsticks, so all methods are judged on equal footing.

One asymmetry is deliberate and worth understanding before reading the table.
The TRAINED rows carry a +/- because their scores depend on how the data happened
to be split into folds, and at this sample size that matters enormously: the same
model can score precision@5 anywhere in a band roughly 0.36 wide depending only
on the shuffle. So they are run under several shuffles and reported as mean +/-
std. The UNTRAINED rows have no such spread. Cosine similarity and the LLM judge
never train on anything, so each posting has one fixed score and the ranking is
what it is. Their numbers are exact, not lucky.

Reading the table, a trained row only beats an untrained one if it clears the
untrained number by more than its own +/-. Anything less is a shuffle away from
reversing.
"""

import argparse

import numpy as np

from ranking.baseline import (
    CV_SEEDS,
    ndcg_at_k,
    precision_at_k,
    repeated_cross_validate,
)
from ranking.data import load_labeled_vacancies
from ranking.embeddings import embedding_scores
from ranking.filters import drop_language_blocked
from ranking.llm_judge import judge_all

REPORT_KS = (5, 10, 20)


def fixed_scores_row(scores: np.ndarray, y_original: np.ndarray) -> dict:
    """Metrics for a method whose scores never change: one ranking, one answer."""
    ranked_true = y_original[np.argsort(-scores, kind="stable")]
    return {
        "precision": {k: (precision_at_k(ranked_true, k), None) for k in REPORT_KS if k <= len(ranked_true)},
        "ndcg": {k: (ndcg_at_k(ranked_true, k), None) for k in REPORT_KS if k <= len(ranked_true)},
    }


def repeated_scores_row(per_seed: dict) -> dict:
    """Metrics for a trained method, as mean and spread across shuffles."""
    return {
        metric: {k: (float(np.mean(v)), float(np.std(v))) for k, v in per_seed[metric].items() if v}
        for metric in ("precision", "ndcg")
    }


def print_table(rows: list[tuple[str, dict]]) -> None:
    columns = [(metric, k) for metric in ("precision", "ndcg") for k in REPORT_KS]
    header = f"{'method':<42}" + "".join(
        f"{('p@' if m == 'precision' else 'ndcg@') + str(k):>13}" for m, k in columns
    )
    print(header)
    print("-" * len(header))
    for name, row in rows:
        line = f"{name:<42}"
        for metric, k in columns:
            value = row.get(metric, {}).get(k)
            if value is None:
                line += f"{'-':>13}"
            else:
                mean, spread = value
                line += f"{mean:>8.2f}" + (f" ±{spread:.2f}" if spread is not None else "     ")
        print(line)


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
        help="skip the slow LLM-as-judge pass (one Ollama call per posting, the bulk of the runtime)",
    )
    args = parser.parse_args()

    df = load_labeled_vacancies()
    before = len(df)
    df = drop_language_blocked(df)
    y_original = df["label"].to_numpy()
    print(f"Dropped {before - len(df)} postings naming a non-English requirement "
          f"({len(df)} remain, {int(np.sum(y_original == 2))} rated 'yes')\n")

    # Embed once: the same cosine scores serve BOTH as the untrained cosine
    # baseline AND as the semantic feature fed into the trained model.
    print("Embedding postings (used for both the semantic feature and the cosine baseline)...")
    cosine_scores = embedding_scores(df)
    df["cosine_score"] = cosine_scores
    without_cosine = df.drop(columns=["cosine_score"])

    # Cross-validation is cheap next to the embedding and judging passes, so
    # repeating it across shuffles costs almost nothing and is the only way the
    # trained rows mean anything.
    print(f"Cross-validating across {len(CV_SEEDS)} shuffles, hand-crafted features...")
    lr_base = repeated_cross_validate(without_cosine, y_original)
    print(f"Cross-validating across {len(CV_SEEDS)} shuffles, + semantic feature...")
    lr_semantic = repeated_cross_validate(df, y_original)
    print(f"Cross-validating across {len(CV_SEEDS)} shuffles, + description features...")
    lr_description = repeated_cross_validate(without_cosine, y_original, use_description=True)

    llm_scores = None
    if not args.skip_judge:
        print("\nRunning LLM-as-judge (one Ollama call per posting, this will take a while)...")
        llm_scores = judge_all(df)
        report_score_spread("LLM-judge", llm_scores)

    print("\n" + "=" * 120)
    print(f"BENCHMARK  n={len(df)} labeled postings, true positive = label 2")
    print("=" * 120)

    rows = [
        ("Logistic regression (hand-crafted)", repeated_scores_row(lr_base)),
        ("Logistic regression + semantic", repeated_scores_row(lr_semantic)),
        ("Logistic regression + description", repeated_scores_row(lr_description)),
        ("Cosine similarity (untrained)", fixed_scores_row(cosine_scores, y_original)),
    ]
    if llm_scores is not None:
        rows.append(("LLM-as-judge (untrained)", fixed_scores_row(llm_scores.astype(float), y_original)))
    print_table(rows)

    print(
        f"\n±  is the std across {len(CV_SEEDS)} cross-validation shuffles, and applies only to the"
        "\n   trained rows: their scores depend on how the data was split. The untrained rows never"
        "\n   train, so their ranking is fixed and their numbers exact."
        "\n   A trained row beats an untrained one only if the gap exceeds its own ±."
    )


if __name__ == "__main__":
    main()
