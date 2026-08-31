"""Interactive labeling loop: shows one vacancy at a time, records a 0/1/2 fit rating
per keypress. Run with `python -m labeling.cli` from the python/ directory.

Queue order matters more than it looks. Labeling is the scarce resource here, so
by default postings are offered most-uncertain-first: the model is trained on
what you have rated so far and asked which remaining posting it is least sure
about. A label on a posting it already scores confidently mostly confirms what it
knew, while one near its decision boundary genuinely moves it.

That biases the labeled set toward the model's blind spots, so a fifth of all
postings are fenced off as a holdout and only ever offered in random order. Their
labels stay a genuine random sample, which is what evaluation needs. See
ranking.holdout and ranking.active.

    python -m labeling.cli                  # most uncertain first, holdout excluded
    python -m labeling.cli --order random   # uniformly random over everything
    python -m labeling.cli --order holdout  # top up the evaluation sample
"""

import argparse
import json
import random
import sys
import termios
import tty

from labeling import db, dedup
from ranking.active import uncertainty_by_vacancy_id
from ranking.holdout import HOLDOUT_PERCENT, is_holdout

VALID_KEYS = {"0", "1", "2", "r", "s", "u", "q"}


def read_key() -> str:
    """Reads a single keypress from the terminal without waiting for Enter.

    Terminals normally buffer input line-by-line (you type, then hit Enter, then
    the program sees anything). `tty.setraw` turns that buffering off for the
    duration of one read, so `sys.stdin.read(1)` returns as soon as one key is
    pressed. `termios` is used to save/restore the terminal's original settings
    afterward -- without that, the terminal would stay in raw mode after the
    program exits, which breaks normal typing in that terminal window.

    When stdin is not a real terminal (e.g. PyCharm's Run window, where stdin is
    a pipe), `termios` cannot configure the device, so we fall back to line input:
    type the key, then press Enter.
    """
    if not sys.stdin.isatty():
        line = sys.stdin.readline()
        if line == "":  # true EOF (stdin closed) returns "" with no newline
            return "q"  # quit cleanly instead of looping forever on a dead stream
        return line.strip()[:1]  # first char of the typed line, "" if just Enter

    fd = sys.stdin.fileno()
    old_settings = termios.tcgetattr(fd)
    try:
        tty.setraw(fd)
        return sys.stdin.read(1)
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, old_settings)


def format_screen(vacancy: db.VacancyToLabel, position: int, total: int, copies: int = 1) -> str:
    skills = ", ".join(json.loads(vacancy.skills)) if vacancy.skills else "(none extracted)"

    salary = "—"
    if vacancy.salary_min is not None:
        salary = f"{vacancy.salary_min}-{vacancy.salary_max} {vacancy.salary_currency or ''} ({vacancy.salary_period})"

    duplicates = f"  ({copies} identical copies, one rating covers all)" if copies > 1 else ""

    lines = [
        f"[{position}/{total}]  {vacancy.company or '?'} — {vacancy.title}{duplicates}",
        f"location: {vacancy.location or '—'}",
        f"seniority: {vacancy.seniority} | remote: {vacancy.remote_policy} | lang: {vacancy.language_requirement or '—'}",
        f"skills: {skills}",
        f"salary: {salary}",
        "",
        vacancy.summary or "(no summary extracted)",
        "",
        "[0] no  [1] maybe  [2] yes  [r] full posting  [s] skip  [u] undo last  [q] quit",
    ]
    return "\n".join(lines)


LABEL_NAMES = {0: "no", 1: "maybe", 2: "yes"}


def run_conflicts() -> None:
    """Re-rate jobs that got duplicated and rated inconsistently.

    Companies re-list expired jobs under a fresh posting id, and sometimes submit
    the same job twice at once, so the same role reaches you more than once, often
    weeks apart. Rating it differently each time is an easy thing to do and leaves
    the model with contradictory examples of the same posting.

    One screen per job, not per row. Whatever you press is written to every copy,
    so the contradiction cannot come back. Only exact duplicates appear here, same
    title, company AND location: the same role in two cities is two jobs, and
    rating them differently is a preference rather than a mistake.
    """
    conn = db.connect()
    groups = db.find_conflicting_duplicates(conn)
    if not groups:
        print("No conflicting duplicates. Nothing to fix.")
        return

    copies = sum(len(g.vacancy_ids) for g in groups)
    print(f"{len(groups)} jobs were rated inconsistently across {copies} duplicate rows.")
    print("Rating one here overwrites every copy of that job.\n")

    for position, group in enumerate(groups, start=1):
        history = "  ".join(
            f"{LABEL_NAMES[label]} on {when}" for label, when in group.previous
        )
        print(format_screen(group.posting, position, len(groups)))
        print(f"\n  previously rated: {history}   ({len(group.vacancy_ids)} copies)")

        key = ""
        while key not in VALID_KEYS:
            key = read_key()

        if key in {"0", "1", "2"}:
            db.save_label_for_group(conn, group.vacancy_ids, int(key))
            print(f"\nSet all {len(group.vacancy_ids)} copies to {LABEL_NAMES[int(key)]}.\n")
        elif key == "r":
            print("\n" + group.posting.raw_text + "\n")
            key = ""
            while key not in {"0", "1", "2", "s", "q"}:
                key = read_key()
            if key in {"0", "1", "2"}:
                db.save_label_for_group(conn, group.vacancy_ids, int(key))
                print(f"\nSet all {len(group.vacancy_ids)} copies to {LABEL_NAMES[int(key)]}.\n")
            elif key == "q":
                break
        elif key == "q":
            break
        # "s" and "u" fall through: leave this job as it is and move on.

    remaining = len(db.find_conflicting_duplicates(conn))
    print(f"Done. {remaining} conflicting job(s) left.")


def order_queue(unlabeled: list[db.VacancyToLabel], order: str) -> tuple[list[db.VacancyToLabel], str]:
    """The queue to work through, plus a one-line description of why it is in that
    order. Returned together so the CLI can always tell you what you are labeling.
    """
    if order == "random":
        # Uniformly random over everything, holdout included. This is the honest
        # sampler: labels collected this way are usable for evaluation.
        shuffled = list(unlabeled)
        random.shuffle(shuffled)
        return shuffled, "random order, holdout included"

    if order == "holdout":
        reserved = [v for v in unlabeled if is_holdout(v.id)]
        random.shuffle(reserved)
        return reserved, f"holdout only ({HOLDOUT_PERCENT}% of postings), random order"

    scores = uncertainty_by_vacancy_id()
    candidates = [v for v in unlabeled if not is_holdout(v.id)]
    if not scores:
        # Too few labels for the model's confusion to mean anything yet, so its
        # ordering would be noise dressed up as strategy.
        random.shuffle(candidates)
        return candidates, "random order (not enough labels yet to rank by uncertainty)"

    # Unscored postings sort last: a missing score means the model never saw the
    # row, not that it is certain about it.
    candidates.sort(key=lambda v: -scores.get(v.id, -1.0))
    return candidates, "most uncertain first, holdout excluded"


def run(order: str = "uncertain") -> None:
    conn = db.connect()

    # Collapse before ordering, not after. The queue is a queue of jobs, not of
    # rows: one screen per job, and the rating is written to every copy so no
    # copy of it is ever offered again. Ordering the rows first and deduplicating
    # afterwards would let the uncertainty sampler spend its budget ranking three
    # copies of the same description against each other.
    rows = db.find_unlabeled(conn)
    groups = dedup.group_duplicates(rows)
    copies_by_id = {group[-1].id: [v.id for v in group] for group in groups}
    representatives = [group[-1] for group in groups]

    unlabeled, description = order_queue(representatives, order)
    total = len(unlabeled)

    last_labeled_ids: list[int] | None = None
    position = 0

    collapsed = sum(len(ids) - 1 for ids in copies_by_id.values())
    duplicates = f", {collapsed} duplicate copies folded in" if collapsed else ""
    print(f"{total} vacancies to label ({description}{duplicates}). Starting...\n")

    while position < len(unlabeled):
        vacancy = unlabeled[position]
        group = copies_by_id[vacancy.id]
        print(format_screen(vacancy, position + 1, total, copies=len(group)))

        key = ""
        while key not in VALID_KEYS:
            key = read_key()

        if key in {"0", "1", "2"}:
            db.save_label_for_group(conn, group, int(key))
            last_labeled_ids = group
            position += 1
        elif key == "r":
            print("\n" + vacancy.raw_text + "\n")
            continue  # re-show the same posting after reading full text
        elif key == "s":
            position += 1  # leave unlabeled, moves on without recording anything
        elif key == "u":
            if last_labeled_ids is not None:
                db.delete_label_for_group(conn, last_labeled_ids)
                position -= 1
                last_labeled_ids = None
                print("\nUndone.\n")
            else:
                print("\nNothing to undo.\n")
            continue
        elif key == "q":
            break

        print()  # blank line between postings

    counts = db.label_counts(conn)
    print(f"Session done. Labels so far -> 0: {counts.get(0, 0)}, 1: {counts.get(1, 0)}, 2: {counts.get(2, 0)}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Rate unlabeled vacancies 0/1/2.")
    parser.add_argument(
        "--order",
        choices=["uncertain", "random", "holdout"],
        default="uncertain",
        help="uncertain: what the model would learn most from, holdout excluded (default). "
             "random: uniformly random, safe for evaluation. "
             "holdout: top up the reserved evaluation sample.",
    )
    parser.add_argument(
        "--fix-conflicts",
        action="store_true",
        help="re-rate jobs that got duplicated and rated inconsistently, applying one "
             "rating to every copy",
    )
    args = parser.parse_args()
    if args.fix_conflicts:
        run_conflicts()
    else:
        run(args.order)


if __name__ == "__main__":
    main()
