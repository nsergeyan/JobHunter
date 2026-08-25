"""Daily pipeline (build-order step 6): scrape -> extract -> rank -> digest.

Glue, deliberately. Each stage already works on its own; this runs them in order
so one command takes you from "nothing scraped today" to a ranked shortlist.

The two scraping/extraction stages live in the Java project, so they are invoked
through Gradle (`run` and `extract`). Gradle recompiles automatically, so edits
on the Java side are picked up without a manual build step -- worth the few
seconds of daemon startup while that code is still changing.

Failure policy: continue and report. A partial scrape is still useful, and the
database is idempotent (vacancies are keyed on the platform's own posting id, and
extraction only processes rows missing from vacancy_extractions), so a stage that
fails halfway leaves valid data behind and is cheap to retry. Rather than
aborting the run, each stage's outcome is recorded and summarised at the end, and
the exit code reflects whether anything failed -- so wrapping this in a scheduler
later still surfaces problems.

A word on runtime: extraction is the slow stage, roughly 20 seconds per posting
through local Ollama. A few hundred fresh postings is an hour or more. Stages
stream their output live rather than buffering, so you can watch progress.

    python -m orchestrator                  # full pipeline
    python -m orchestrator --skip-scrape    # extract + rank only
    python -m orchestrator --digest-only    # just re-rank what is already stored
"""

import argparse
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

from ranking.digest import (
    DEFAULT_DAYS,
    DEFAULT_TOP_K,
    format_digest,
    save_digest,
    score_unlabeled,
)
from ranking.preferences import LOCATION_INCLUDE, SENIORITY_INCLUDE

REPO_ROOT = Path(__file__).resolve().parents[1]
JAVA_PROJECT_DIR = REPO_ROOT / "JobHunterTech"
GRADLE = JAVA_PROJECT_DIR / "gradlew"
ENV_FILE = REPO_ROOT / ".env"

SCRAPE_CMD = [str(GRADLE), "run", "--quiet", "--console=plain"]
EXTRACT_CMD = [str(GRADLE), "extract", "--quiet", "--console=plain"]

# Gradle 9.2 cannot run on a JDK newer than it knows about, and a Homebrew box
# usually has the latest JDK on PATH ("Unsupported class file major version").
# The Java project targets 21 via a toolchain, so only the daemon's own runtime
# is at issue. These are the usual places a supported JDK lives; set
# GRADLE_JAVA_HOME in .env to override.
JAVA_HOME_ENV_KEY = "GRADLE_JAVA_HOME"
JAVA_HOME_CANDIDATES = (
    "/opt/homebrew/opt/openjdk@21",
    "/usr/local/opt/openjdk@21",
    "/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home",
)


def _env_file_value(key: str) -> str | None:
    """Read one key from the repo-root .env. A deliberately tiny parser: the
    Java side owns .env via dotenv-java, and this is the only key Python needs,
    so it isn't worth a dependency.
    """
    if not ENV_FILE.exists():
        return None
    for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, _, value = line.partition("=")
        if name.strip() == key:
            return value.strip().strip('"').strip("'") or None
    return None


def _resolve_java_home() -> str | None:
    """Pick a JDK for the Gradle daemon, or None to use whatever is on PATH."""
    explicit = os.environ.get(JAVA_HOME_ENV_KEY) or _env_file_value(JAVA_HOME_ENV_KEY)
    if explicit:
        return explicit

    # An already-exported JAVA_HOME is the user's own choice; don't second-guess it.
    if os.environ.get("JAVA_HOME"):
        return None

    if shutil.which("/usr/libexec/java_home"):
        result = subprocess.run(
            ["/usr/libexec/java_home", "-v", "21"], capture_output=True, text=True, check=False
        )
        if result.returncode == 0 and result.stdout.strip():
            return result.stdout.strip()

    return next((path for path in JAVA_HOME_CANDIDATES if Path(path, "bin", "java").exists()), None)


class StageResult:
    def __init__(self, name: str, ok: bool, detail: str, seconds: float) -> None:
        self.name = name
        self.ok = ok
        self.detail = detail
        self.seconds = seconds


def _run_stage(name: str, command: list[str], java_home: str | None) -> StageResult:
    """Run one Java stage, streaming its output straight to the terminal."""
    print(f"\n=== {name} ===", flush=True)
    started = time.monotonic()

    env = dict(os.environ)
    if java_home:
        env["JAVA_HOME"] = java_home

    try:
        # No capture: the child writes directly to our stdout/stderr, so long
        # runs show progress instead of going silent for an hour.
        completed = subprocess.run(command, cwd=JAVA_PROJECT_DIR, env=env, check=False)
    except FileNotFoundError:
        elapsed = time.monotonic() - started
        return StageResult(name, False, f"{GRADLE} not found", elapsed)
    except KeyboardInterrupt:
        raise
    except OSError as exc:
        return StageResult(name, False, str(exc), time.monotonic() - started)

    elapsed = time.monotonic() - started
    if completed.returncode != 0:
        return StageResult(name, False, f"exit code {completed.returncode}", elapsed)
    return StageResult(name, True, "ok", elapsed)


def _format_duration(seconds: float) -> str:
    minutes, secs = divmod(int(seconds), 60)
    return f"{minutes}m{secs:02d}s" if minutes else f"{secs}s"


def _print_summary(results: list[StageResult], digest_path: Path | None) -> None:
    print("\n=== Pipeline summary ===")
    for result in results:
        status = "OK  " if result.ok else "FAIL"
        print(f"  [{status}] {result.name:<12} {_format_duration(result.seconds):>7}  {result.detail}")

    failed = [r for r in results if not r.ok]
    if failed:
        print(
            f"\n{len(failed)} stage(s) failed, so the digest below may be missing "
            "postings. The database is idempotent, so rerunning is safe and only "
            "redoes the missing work."
        )
    if digest_path:
        print(f"\nDigest saved to {digest_path}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Run the full scrape -> extract -> rank pipeline.")
    parser.add_argument("--skip-scrape", action="store_true", help="skip the Java scraping stage")
    parser.add_argument("--skip-extract", action="store_true", help="skip the LLM extraction stage")
    parser.add_argument("--digest-only", action="store_true", help="skip both Java stages, just rank what is stored")
    parser.add_argument("-k", "--top-k", type=int, default=DEFAULT_TOP_K, help=f"how many postings to show (default {DEFAULT_TOP_K})")
    parser.add_argument("--days", type=int, default=DEFAULT_DAYS, help=f"only rank postings scraped in the last N days, 0 for no limit (default {DEFAULT_DAYS})")
    parser.add_argument("--all-seniority", action="store_true", help="ignore the seniority filter in preferences.py")
    parser.add_argument("--all-locations", action="store_true", help="ignore the location filter in preferences.py")
    args = parser.parse_args()

    results: list[StageResult] = []
    runs_java = not args.digest_only and not (args.skip_scrape and args.skip_extract)

    java_home = _resolve_java_home() if runs_java else None
    if runs_java:
        if java_home:
            print(f"Using JAVA_HOME={java_home} for Gradle")
        elif not os.environ.get("JAVA_HOME"):
            print(
                "Warning: no JDK 21 found for the Gradle daemon. If Gradle fails with "
                f"'Unsupported class file major version', set {JAVA_HOME_ENV_KEY} in .env "
                "to a JDK 21 path."
            )

    try:
        if not (args.digest_only or args.skip_scrape):
            results.append(_run_stage("scrape", SCRAPE_CMD, java_home))
        if not (args.digest_only or args.skip_extract):
            results.append(_run_stage("extract", EXTRACT_CMD, java_home))
    except KeyboardInterrupt:
        print("\nInterrupted. Whatever was already written to the database is kept.")
        return 130

    print("\n=== rank ===", flush=True)
    since_days = args.days or None
    seniority_include = None if args.all_seniority else SENIORITY_INCLUDE
    location_include = None if args.all_locations else LOCATION_INCLUDE

    started = time.monotonic()
    ranked, pool_size = score_unlabeled(since_days, seniority_include, location_include)
    markdown = format_digest(ranked, args.top_k, pool_size, since_days, seniority_include, location_include)
    results.append(StageResult("rank", True, f"{len(ranked)} postings ranked", time.monotonic() - started))

    print()
    print(markdown)

    digest_path = save_digest(markdown)
    _print_summary(results, digest_path)

    return 1 if any(not r.ok for r in results) else 0


if __name__ == "__main__":
    sys.exit(main())
