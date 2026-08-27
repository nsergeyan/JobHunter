"""Benchmark (build-order step 5): compares the trained logistic regression
against an LLM-as-judge and cosine similarity to a preference profile, on the
same labeled postings. precision@k and NDCG@k (true positive = original label 2)
are the common yardsticks, so all methods are judged on equal footing.

Every number carries a 95% confidence interval, and getting there took two
corrections worth stating.

First, a single cross-validation split is a lottery at this sample size: the same
model scores precision@5 across a band roughly 0.36 wide depending only on how the
folds fell. So the trained methods are run under several shuffles.

Second, and less obvious, the untrained methods looked deceptively exact. Cosine
similarity and the LLM judge never train, so their ranking does not move between
runs, and reporting them as bare numbers implied a precision they do not have.
precision@5 is computed from five postings. "0.80" means four of five, and a
different sample of postings could easily have said three. Reproducible is not the
same as precise.

So both kinds are bootstrapped over the postings themselves, resampling with
replacement. For trained methods the resampling runs within every shuffle, so the
interval absorbs both the fold luck and the sample luck. The result is one
uncertainty measure that means the same thing in every row, which is the only way
"A beats B" can be read off the table honestly.
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
N_RESAMPLES = 2000
METRICS = {"precision": precision_at_k, "ndcg": ndcg_at_k}


def bootstrap_metric(
    score_sets: list[np.ndarray],
    y_original: np.ndarray,
    k: int,
    metric: str,
    n_resamples: int = N_RESAMPLES,
    seed: int = 0,
) -> tuple[float, float, float]:
    """Mean and 95% interval for one metric, resampling postings with replacement.

    `score_sets` holds one score vector per cross-validation shuffle for a trained
    method, or a single vector for an untrained one. Resamples are split evenly
    across them, so a trained method's interval widens to reflect fold luck on top
    of sample luck, while an untrained method's reflects sample luck alone.
    """
    metric_fn = METRICS[metric]
    rng = np.random.default_rng(seed)
    per_set = max(1, n_resamples // len(score_sets))
    values = []
    for scores in score_sets:
        for _ in range(per_set):
            picks = rng.integers(0, len(y_original), len(y_original))
            resampled_y = y_original[picks]
            ranked_true = resampled_y[np.argsort(-scores[picks], kind="stable")]
            if k <= len(ranked_true):
                values.append(metric_fn(ranked_true, k))
    low, high = np.percentile(values, [2.5, 97.5])
    return float(np.mean(values)), float(low), float(high)


def _metric_on_resample(score_sets: list[np.ndarray], y_resampled: np.ndarray,
                        picks: np.ndarray, k: int, metric_fn) -> float:
    """One method's metric on one resample, averaged over its shuffles."""
    values = []
    for scores in score_sets:
        ranked_true = y_resampled[np.argsort(-scores[picks], kind="stable")]
        if k <= len(ranked_true):
            values.append(metric_fn(ranked_true, k))
    return float(np.mean(values)) if values else float("nan")


def paired_bootstrap(
    a_sets: list[np.ndarray],
    b_sets: list[np.ndarray],
    y_original: np.ndarray,
    k: int,
    metric: str,
    n_resamples: int = N_RESAMPLES,
    seed: int = 0,
) -> tuple[float, float, float, float]:
    """Compare two methods on the SAME resampled postings, every time.

    This is the comparison that answers "does A beat B", and it is not the same as
    checking whether two separate intervals overlap. Those intervals are dominated
    by which postings the sample happened to contain, which BOTH methods face
    equally. Pairing cancels that shared luck out, so overlapping marginal intervals
    routinely hide a difference that is perfectly consistent once paired.

    Returns the mean difference (A minus B), its 95% interval, and the share of
    resamples where A came out ahead.
    """
    metric_fn = METRICS[metric]
    rng = np.random.default_rng(seed)
    differences = []
    for _ in range(n_resamples):
        picks = rng.integers(0, len(y_original), len(y_original))
        y_resampled = y_original[picks]
        a = _metric_on_resample(a_sets, y_resampled, picks, k, metric_fn)
        b = _metric_on_resample(b_sets, y_resampled, picks, k, metric_fn)
        differences.append(a - b)
    differences = np.array(differences)
    low, high = np.percentile(differences, [2.5, 97.5])
    return float(differences.mean()), float(low), float(high), float((differences > 0).mean())


def print_comparison(label_a: str, a_sets, label_b: str, b_sets,
                     y_original: np.ndarray) -> None:
    print(f"\n{label_a}  vs  {label_b}")
    for metric in ("precision", "ndcg"):
        for k in REPORT_KS:
            mean, low, high, win_rate = paired_bootstrap(a_sets, b_sets, y_original, k, metric)
            name = ("precision@" if metric == "precision" else "ndcg@") + str(k)
            # An interval excluding zero is the whole point: it means the direction
            # of the difference held across essentially every resample.
            verdict = "DIFFERENT" if low > 0 or high < 0 else "not separated"
            print(f"  {name:<13} {mean:+.3f}  [{low:+.3f} - {high:+.3f}]  "
                  f"A ahead in {win_rate:5.1%} of resamples   {verdict}")


def print_metric_block(metric: str, k: int, rows: list[tuple[str, list[np.ndarray]]],
                       y_original: np.ndarray) -> None:
    label = ("precision@" if metric == "precision" else "ndcg@") + str(k)
    print(f"\n{label}")
    scored = [(name, bootstrap_metric(sets, y_original, k, metric)) for name, sets in rows]
    for name, (mean, low, high) in sorted(scored, key=lambda r: -r[1][0]):
        bar_width = high - low
        print(f"  {name:<38} {mean:.2f}   [{low:.2f} - {high:.2f}]   width {bar_width:.2f}")


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

    rows: list[tuple[str, list[np.ndarray]]] = [
        ("Logistic regression (hand-crafted)", lr_base["oof_scores"]),
        ("Logistic regression + semantic", lr_semantic["oof_scores"]),
        ("Logistic regression + description", lr_description["oof_scores"]),
        ("Cosine similarity (untrained)", [cosine_scores]),
    ]

    if not args.skip_judge:
        print("\nRunning LLM-as-judge (one Ollama call per posting, this will take a while)...")
        llm_scores = judge_all(df).astype(float)
        report_score_spread("LLM-judge", llm_scores)
        rows.append(("LLM-as-judge (untrained)", [llm_scores]))

    print("\n" + "=" * 78)
    print(f"BENCHMARK  n={len(df)} labeled postings, true positive = label 2")
    print(f"mean and 95% interval over {N_RESAMPLES} bootstrap resamples of the postings")
    print("=" * 78)
    for metric in ("precision", "ndcg"):
        for k in REPORT_KS:
            print_metric_block(metric, k, rows, y_original)

    print(
        "\nThose intervals are wide because they are dominated by which postings the"
        "\nsample happened to contain, and every method faces that same luck. Comparing"
        "\ntwo of them by eye understates what can be resolved, so the questions worth"
        "\nasking are answered below by scoring both methods on the SAME resample."
    )

    print("\n" + "=" * 78)
    print("PAIRED COMPARISONS  (positive means the first method is ahead)")
    print("=" * 78)
    by_name = dict(rows)
    print_comparison(
        "LR + description", by_name["Logistic regression + description"],
        "LR (hand-crafted)", by_name["Logistic regression (hand-crafted)"], y_original)
    print_comparison(
        "LR + semantic", by_name["Logistic regression + semantic"],
        "LR (hand-crafted)", by_name["Logistic regression (hand-crafted)"], y_original)
    print_comparison(
        "LR + description", by_name["Logistic regression + description"],
        "Cosine (untrained)", by_name["Cosine similarity (untrained)"], y_original)
    # The project's founding question: does training on personal labels beat a
    # generic similarity heuristic at all? It needs the plain feature set, not the
    # best variant, or the answer is about the features rather than about training.
    print_comparison(
        "LR (hand-crafted)", by_name["Logistic regression (hand-crafted)"],
        "Cosine (untrained)", by_name["Cosine similarity (untrained)"], y_original)
    if "LLM-as-judge (untrained)" in by_name:
        print_comparison(
            "LR + description", by_name["Logistic regression + description"],
            "LLM-as-judge", by_name["LLM-as-judge (untrained)"], y_original)


if __name__ == "__main__":
    main()
