# python/

Placeholder for build-order steps 4 (ranking model), 5 (benchmark), and 7 (market
analysis notebook) — see `../PROJECT_BRIEF.md`. Not active yet: steps 2 (LLM
extraction) and 3 (labeling CLI) come first, and both are Java (`../JobHunterTech`),
not Python. No ML code should be written here until ~200-300 labels exist.

Reads/writes the same SQLite file the Java side uses: `../data/job_scout.db`.

## Setup (once there's actual code to run)

```bash
cd python
python3 -m venv .venv
source .venv/bin/activate
pip install -e ".[dev]"
```
