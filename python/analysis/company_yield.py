"""What each configured company actually costs, and what it returns.

Two numbers per company, and the interesting thing is the ratio between them.

COST is requests per scrape. Single-request platforms (Greenhouse, Ashby, Lever)
spend one per company. Workday and SmartRecruiters page through their boards, so a
2000-posting board costs 100 requests every run whether or not anything on it is
relevant.

RETURN is how many of its postings survived the filters, and of those, how many
were rated yes or maybe.

Company names need care. scrape_runs records the name from the config, but
vacancies records whatever the posting itself said, and Workday prefixes every
entity: "CH01 NVIDIA Switzerland AG", "DE01 NVIDIA Germany". Matching on the
config name alone reported zero labels for NVIDIA when it actually has the highest
interest rate of any Workday company. ALIASES exists because of that.

    python -m analysis.company_yield              # everything, by yield
    python -m analysis.company_yield --by-cost    # worst cost-per-return first
    python -m analysis.company_yield --platform workday
"""

import argparse
import json
import re
import sqlite3
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DB_PATH = REPO_ROOT / "data" / "job_scout.db"
CONFIG_PATH = REPO_ROOT / "config" / "companies.json"

# Platforms that fetch a whole board in one request, so cost does not scale with
# board size. The rest page through and cost roughly board_size / PAGE_SIZE.
SINGLE_REQUEST_PLATFORMS = {"greenhouse", "ashby", "lever"}
WORKDAY_PAGE_SIZE = 20
SMARTRECRUITERS_PAGE_SIZE = 100

# Where a posting's own company name will not match the configured name. Workday
# entity codes are the main culprit, plus a few acquisitions and rebrands.
ALIASES = {
    "NVIDIA": ["nvidia", "mellanox"],
    "Deutsche Bank": ["deutsche bank", "db global"],
    "AstraZeneca": ["astrazeneca", "az farmac"],
    "Booking Holdings": ["booking", "priceline"],
    "Philips": ["philips", "drachten"],
    "Samsung": ["samsung", "böblingen", "boblingen"],
    "Mastercard Campus": ["mastercard"],
    "Unilever Early Careers": ["unilever"],
    "IMC Trading": ["imc"],
    "Scale AI": ["scale ai", "scaleai"],
}


def normalise(text: str) -> str:
    return re.sub(r"[^a-z0-9 ]", " ", (text or "").lower())


def load_config() -> list[dict]:
    data = json.loads(CONFIG_PATH.read_text())
    companies = []
    for platform, entries in data.items():
        if platform.startswith("_"):
            continue
        for entry in entries:
            companies.append({"platform": platform, "company": entry["company"]})
    return companies


def load_board_sizes(conn: sqlite3.Connection) -> dict[tuple[str, str], int]:
    """Board size from each company's most recent scrape_runs row."""
    rows = conn.execute(
        """
        SELECT source, company, fetched FROM scrape_runs r
        WHERE r.finished_at = (
            SELECT MAX(finished_at) FROM scrape_runs r2
            WHERE r2.source = r.source AND r2.company IS r.company
        )
        """
    ).fetchall()
    return {(source, company): fetched for source, company, fetched in rows if company}


def requests_per_run(platform: str, board_size: int | None) -> int | None:
    if platform in SINGLE_REQUEST_PLATFORMS:
        return 1
    if board_size is None:
        return None
    page = WORKDAY_PAGE_SIZE if platform == "workday" else SMARTRECRUITERS_PAGE_SIZE
    return max(1, -(-board_size // page))


def load_outcomes(conn: sqlite3.Connection) -> list[tuple[str, str, int | None]]:
    return conn.execute(
        """
        SELECT v.source, v.company, l.label
        FROM vacancies v LEFT JOIN labels l ON l.vacancy_id = v.id
        """
    ).fetchall()


def attribute(companies: list[dict], outcomes) -> None:
    """Attach stored/labeled/yes/maybe to each configured company.

    Longest configured name wins a tie, so "Mastercard Campus" is preferred over
    "Mastercard" when a posting could match either.
    """
    for company in companies:
        company.update(stored=0, labeled=0, yes=0, maybe=0)

    by_platform: dict[str, list[dict]] = {}
    for company in companies:
        by_platform.setdefault(company["platform"], []).append(company)
    for entries in by_platform.values():
        entries.sort(key=lambda c: -len(c["company"]))

    for source, posting_company, label in outcomes:
        haystack = normalise(posting_company)
        for candidate in by_platform.get(source, []):
            needles = ALIASES.get(candidate["company"], [candidate["company"]])
            if any(normalise(n) in haystack for n in needles):
                candidate["stored"] += 1
                if label is not None:
                    candidate["labeled"] += 1
                    if label == 2:
                        candidate["yes"] += 1
                    elif label == 1:
                        candidate["maybe"] += 1
                break


# A company only earns "drop" if the evidence is strong enough to act on. Cost is
# the deciding factor, and after Workday tenants were parallelised cost mostly means
# requests rather than wall-clock time, so single-request companies are kept almost
# unconditionally: dropping one saves nothing and risks missing the role it posts
# next month.
LABELS_BEFORE_JUDGING = 8
EXPENSIVE = 10  # requests per run


def verdict(c: dict) -> tuple[str, str]:
    """A keep/drop call per company, with the reason that produced it."""
    if c["board"] is None:
        return "NEW", "added today, no scrape data yet"
    if c["yes"] > 0:
        return "KEEP", f"produced {c['yes']} yes"
    if c["requests"] == 1:
        return "KEEP", "costs 1 request per run, dropping saves nothing"
    if c["maybe"] > 0:
        return "KEEP", f"{c['maybe']} maybe, some signal"
    if c["labeled"] >= LABELS_BEFORE_JUDGING:
        return "DROP", f"{c['labeled']} labels, none of interest"
    if c["requests"] >= EXPENSIVE and c["stored"] == 0:
        return "DROP", f"{c['requests']} requests/run, never stored a posting"
    if c["requests"] >= EXPENSIVE:
        return "WATCH", f"{c['requests']} requests/run, only {c['labeled']} labels so far"
    return "KEEP", "cheap enough to leave alone"


def filter_suspicion(c: dict) -> bool:
    """Big board, nothing ever stored. More likely a filter problem than a company
    problem, and worth checking before concluding anything about the company."""
    return (c["board"] or 0) >= 150 and c["stored"] == 0


def main() -> None:
    parser = argparse.ArgumentParser(description="Cost and yield per configured company.")
    parser.add_argument("--by-cost", action="store_true",
                        help="sort by requests per run, to find expensive companies returning nothing")
    parser.add_argument("--platform", help="limit to one platform")
    args = parser.parse_args()

    conn = sqlite3.connect(DB_PATH)
    try:
        companies = load_config()
        boards = load_board_sizes(conn)
        attribute(companies, load_outcomes(conn))
    finally:
        conn.close()

    for company in companies:
        board = boards.get((company["platform"], company["company"]))
        company["board"] = board
        company["requests"] = requests_per_run(company["platform"], board)
        company["interest"] = (
            100.0 * (company["yes"] + company["maybe"]) / company["labeled"]
            if company["labeled"] else None
        )

    if args.platform:
        companies = [c for c in companies if c["platform"] == args.platform]
    if args.by_cost:
        companies.sort(key=lambda c: (-(c["requests"] or 0), -c["yes"]))
    else:
        companies.sort(key=lambda c: (-c["yes"], -c["maybe"], -c["stored"]))

    for c in companies:
        c["verdict"], c["reason"] = verdict(c)

    header = (f"{'platform':<16}{'company':<24}{'board':>6}{'req':>5}{'store':>6}"
              f"{'lab':>5}{'yes':>4}{'maybe':>6}  {'verdict':<7} reason")
    for group in ("DROP", "WATCH", "KEEP", "NEW"):
        rows = [c for c in companies if c["verdict"] == group]
        if not rows:
            continue
        print(f"\n=== {group} ({len(rows)}) ===")
        print(header)
        print("-" * 118)
        for c in rows:
            board = c["board"] if c["board"] is not None else "-"
            reqs = c["requests"] if c["requests"] is not None else "-"
            print(f"{c['platform']:<16}{c['company'][:23]:<24}{str(board):>6}{str(reqs):>5}"
                  f"{c['stored']:>6}{c['labeled']:>5}{c['yes']:>4}{c['maybe']:>6}  "
                  f"{c['verdict']:<7} {c['reason']}")

    suspicious = [c for c in companies if filter_suspicion(c)]
    if suspicious:
        print(f"\n=== FILTER SUSPICION ({len(suspicious)}) ===")
        print("Large boards that have never stored a single posting. Before blaming the")
        print("company, check whether the scrape-time filters are too aggressive.")
        print(f"\n{'platform':<16}{'company':<24}{'board postings':>16}")
        print("-" * 56)
        for c in sorted(suspicious, key=lambda x: -(x["board"] or 0)):
            print(f"{c['platform']:<16}{c['company'][:23]:<24}{c['board']:>16}")

    total_requests = sum(c["requests"] or 0 for c in companies)
    productive = [c for c in companies if c["yes"] > 0]
    never_stored = [c for c in companies if c["stored"] == 0 and c["board"] is not None]
    print(f"\n  {len(companies)} companies, {total_requests} requests per run")
    print(f"  {len(productive)} have produced at least one 'yes'")
    print(f"  {len(never_stored)} have been scraped but never stored a posting")
    wasted = sum(c["requests"] or 0 for c in never_stored)
    if wasted:
        print(f"  {wasted} requests per run go to companies that have never stored anything")


if __name__ == "__main__":
    main()
