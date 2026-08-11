"""Daily digest (build-order step 6, first slice): scan -> rank -> print.

This reuses the step-4 ranking model unchanged. It trains the same
FeatureBuilder + multinomial LogisticRegression on ALL labeled vacancies, then
predicts the expected rating (0..2, higher = closer to a "yes") on postings the
model has never trained on, and prints them best-first. The English-only filter
from ranking.filters runs first, so filtered roles never reach the shortlist.

Two modes:

  (default)   Rank UNLABELED postings -- the real daily-loop behavior. These are
              postings the scraper has added that you have not rated yet. With a
              fully-labeled DB this is empty until the next scrape; the command
              says so instead of printing nothing.

  --demo      Inspection mode. Rank the LABELED set using cross-validated
              out-of-fold scores (each row scored by a model that never saw it
              during training), so you can eyeball ranking quality today without
              any leakage. This is for looking, not for the daily loop.

Later slices of step 6 will wire the Java scrape + extract steps in front of
this, draft cover letters for the top postings, and deliver the digest
somewhere (file/email). This slice deliberately stops at "print to terminal".
"""

import argparse

import numpy as np
import pandas as pd
from sklearn.linear_model import LogisticRegression

from ranking.baseline import FeatureBuilder, cross_validate, expected_rating
from ranking.data import load_labeled_vacancies, load_unlabeled_vacancies
from ranking.filters import requires_non_english_language

DEFAULT_TOP_K = 10


def _drop_non_english(df: pd.DataFrame) -> pd.DataFrame:
    """Apply the same hard language filter used in training, before ranking."""
    keep = ~df["language_requirement"].apply(requires_non_english_language)
    return df[keep].reset_index(drop=True)


def score_unlabeled() -> pd.DataFrame:
    """Train on all labeled data, score every unlabeled posting, return the
    postings with a 'score' column sorted best-first. English-only filter is
    applied to both sets first.
    """
    labeled = _drop_non_english(load_labeled_vacancies())
    unlabeled = _drop_non_english(load_unlabeled_vacancies())

    if unlabeled.empty:
        return unlabeled.assign(score=pd.Series(dtype=float))

    builder = FeatureBuilder().fit(labeled)
    model = LogisticRegression(max_iter=1000, class_weight="balanced")
    model.fit(builder.transform(labeled), labeled["label"].to_numpy())

    scores = expected_rating(model.predict_proba(builder.transform(unlabeled)))
    return unlabeled.assign(score=scores).sort_values("score", ascending=False).reset_index(drop=True)


def score_labeled_oof() -> pd.DataFrame:
    """Demo/inspection ranking: cross-validated out-of-fold expected rating for
    the labeled set. Each row's score comes from a fold that never trained on
    it, so the ordering is honest (no leakage) even though every row is labeled.
    """
    labeled = _drop_non_english(load_labeled_vacancies())
    oof_scores = cross_validate(labeled, labeled["label"].to_numpy())["oof_scores"]
    return labeled.assign(score=oof_scores).sort_values("score", ascending=False).reset_index(drop=True)


def print_digest(ranked: pd.DataFrame, top_k: int, show_label: bool) -> None:
    if ranked.empty:
        print(
            "No unlabeled postings to rank. Every extracted posting already has "
            "a label.\nRun the scraper (Java `Main`) + extraction (`ExtractionMain`) "
            "to add fresh postings,\nor use --demo to inspect ranking on the "
            "labeled set."
        )
        return

    print(f"Top {min(top_k, len(ranked))} of {len(ranked)} postings (score = expected rating, 0..2):\n")
    for rank, (_, row) in enumerate(ranked.head(top_k).iterrows(), start=1):
        location = row.get("location") or "?"
        label_note = f"  [true label {int(row['label'])}]" if show_label and "label" in row else ""
        print(f"{rank:2d}. {row['score']:.2f}  {row['title']}  @ {row['company']} ({location}){label_note}")
        url = row.get("url")
        if isinstance(url, str) and url:
            print(f"      {url}")
    print()


def main() -> None:
    parser = argparse.ArgumentParser(description="Rank postings and print a digest.")
    parser.add_argument("--demo", action="store_true", help="rank the labeled set (out-of-fold) instead of unlabeled postings")
    parser.add_argument("-k", "--top-k", type=int, default=DEFAULT_TOP_K, help=f"how many to show (default {DEFAULT_TOP_K})")
    args = parser.parse_args()

    if args.demo:
        print("=== DEMO: out-of-fold ranking of the LABELED set (for inspection, not the daily loop) ===\n")
        print_digest(score_labeled_oof(), args.top_k, show_label=True)
    else:
        print_digest(score_unlabeled(), args.top_k, show_label=False)


if __name__ == "__main__":
    main()
