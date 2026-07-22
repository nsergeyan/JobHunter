"""Interactive labeling loop: shows one vacancy at a time, records a 0/1/2 fit rating
per keypress. Run with `python -m labeling.cli` from the python/ directory.
"""

import json
import random
import sys
import termios
import tty

from labeling import db

VALID_KEYS = {"0", "1", "2", "r", "s", "u", "q"}


def read_key() -> str:
    """Reads a single keypress from the terminal without waiting for Enter.

    Terminals normally buffer input line-by-line (you type, then hit Enter, then
    the program sees anything). `tty.setraw` turns that buffering off for the
    duration of one read, so `sys.stdin.read(1)` returns as soon as one key is
    pressed. `termios` is used to save/restore the terminal's original settings
    afterward -- without that, the terminal would stay in raw mode after the
    program exits, which breaks normal typing in that terminal window.
    """
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


def run() -> None:
    conn = db.connect()
    unlabeled = db.find_unlabeled(conn)
    random.shuffle(unlabeled)  # avoid autopilot from rating similar postings back-to-back
    total = len(unlabeled)

    last_labeled_id: int | None = None
    position = 0

    print(f"{total} vacancies to label. Starting...\n")

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


if __name__ == "__main__":
    run()
