"""Collapsing near-identical copies of the same job into one labeling screen.

The same job reaches the queue several times, for two reasons that the scrapers
cannot fix because neither is a scraping error:

  - A company opens one requisition per headcount slot. Cisco listed the same
    Bratislava backend role under 2021446-1, 2021447 and 2021438 at once. Three
    req ids, three URLs, three genuinely distinct postings as far as the board
    is concerned.
  - A company re-lists an expiring job under a fresh req id. Bosch reposted the
    same PreMaster programme on Aug 27, Aug 28 and Aug 31. SmartRecruiters and
    Workday never set closed_at (absence from a filtered listing is not evidence
    of closure), so the old copies stay open and pile up.

Either way the upsert key `(source, external_id)` sees new rows, correctly, and
you get asked to rate the same description three times.

`ranking.data.collapse_exact_duplicates` already handles this for the model, on
`(company, title, location)`. That key is too blunt for the labeling queue:
Cisco also posts a genuinely different AI role under the identical title, in the
same city, and folding it into the others would mean you never see it at all.
So the key is used as a cheap pre-filter and the decision is made on the text.
"""

from ranking.holdout import is_holdout

# 5-word shingles rather than bare words: word overlap is dominated by the "Why
# Cisco?" style boilerplate every posting on a board shares, so two unrelated
# roles at one company score far higher than they should. Comparing short word
# runs measures whether the same sentences are present, which is the actual
# question.
SHINGLE_SIZE = 5

# Measured on all 50 duplicate clusters currently in the database. The two
# populations are cleanly separated with a gap in between: re-lists and parallel
# requisitions of one job score 0.933-1.000, while different jobs sharing a title
# score at most 0.524 (the same Cisco title, AI role vs backend role, scores
# 0.263). 0.92 sits in that gap.
#
# Deliberately at the top of the gap rather than the middle, because the two
# mistakes do not cost the same. Too low and a real job is silently hidden from
# you forever. Too high and you press a key one extra time.
NEAR_DUPLICATE_THRESHOLD = 0.92


def _shingles(text: str) -> set[tuple[str, ...]]:
    words = "".join(c if c.isalnum() else " " for c in (text or "").lower()).split()
    return {
        tuple(words[i:i + SHINGLE_SIZE])
        for i in range(max(0, len(words) - SHINGLE_SIZE + 1))
    }


def _similarity(a: set, b: set) -> float:
    """Jaccard overlap: shared shingles over total distinct shingles."""
    if not a and not b:
        return 1.0
    union = len(a | b)
    return len(a & b) / union if union else 0.0


def group_duplicates(vacancies: list) -> list[list]:
    """Copies of the same job, grouped. One list per job, in queue order.

    Groups never mix holdout and non-holdout postings, even when the text is
    identical. A group is rated once and the rating is written to every member,
    so a mixed group would let the uncertainty sampler put labels into the
    holdout -- and the holdout is only worth having because its labels are a
    random sample. Three of the five duplicate groups currently in the queue
    straddle that line, and keeping them apart costs three extra screens.

    Within a group the LAST member is the one worth showing: rows arrive ordered
    by id, so that is the most recent copy, whose description matches what is on
    the board now. Same rule as ranking.data.collapse_exact_duplicates.
    """
    by_key: dict[tuple, list] = {}
    for vacancy in vacancies:
        key = (vacancy.company, vacancy.title, vacancy.location, is_holdout(vacancy.id))
        by_key.setdefault(key, []).append(vacancy)

    groups: list[list] = []
    for candidates in by_key.values():
        if len(candidates) == 1:
            groups.append(candidates)
            continue

        shingles = {v.id: _shingles(v.raw_text) for v in candidates}
        buckets: list[list] = []
        for vacancy in candidates:
            for bucket in buckets:
                # Compared against the bucket's first member only. The two
                # populations are far enough apart that a transitivity failure
                # would need a posting sitting inside the gap.
                if _similarity(shingles[vacancy.id], shingles[bucket[0].id]) >= NEAR_DUPLICATE_THRESHOLD:
                    bucket.append(vacancy)
                    break
            else:
                buckets.append([vacancy])
        groups.extend(buckets)

    return groups
