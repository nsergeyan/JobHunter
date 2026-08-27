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

from labeling import db
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


def format_screen(vacancy: db.VacancyToLabel, position: int, total: int) -> str:
    skills = ", ".join(json.loads(vacancy.skills)) if vacancy.skills else "(none extracted)"

    salary = "—"
    if vacancy.salary_min is not None:
        salary = f"{vacancy.salary_min}-{vacancy.salary_max} {vacancy.salary_currency or ''} ({vacancy.salary_period})"

    lines = [
        f"[{position}/{total}]  {vacancy.company or '?'} — {vacancy.title}",
        f"seniority: {vacancy.seniority} | remote: {vacancy.remote_policy} | lang: {vacancy.language_requirement or '—'}",
        f"skills: {skills}",
        f"salary: {salary}",
        "",
        vacancy.summary or "(no summary extracted)",
        "",
        "[0] no  [1] maybe  [2] yes  [r] full posting  [s] skip  [u] undo last  [q] quit",
    ]
    return "\n".join(lines)


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
    unlabeled = order_queue(db.find_unlabeled(conn), order)
    unlabeled, description = unlabeled
    total = len(unlabeled)

    last_labeled_id: int | None = None
    position = 0

    print(f"{total} vacancies to label ({description}). Starting...\n")

    while position < len(unlabeled):
        vacancy = unlabeled[position]
        print(format_screen(vacancy, position + 1, total))

        key = ""
        while key not in VALID_KEYS:
            key = read_key()

        if key in {"0", "1", "2"}:
            db.save_label(conn, vacancy.id, int(key))
            last_labeled_id = vacancy.id
            position += 1
        elif key == "r":
            print("\n" + vacancy.raw_text + "\n")
            continue  # re-show the same posting after reading full text
        elif key == "s":
            position += 1  # leave unlabeled, moves on without recording anything
        elif key == "u":
            if last_labeled_id is not None:
                db.delete_label(conn, last_labeled_id)
                position -= 1
                last_labeled_id = None
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
    args = parser.parse_args()
    run(args.order)


if __name__ == "__main__":
    main()
