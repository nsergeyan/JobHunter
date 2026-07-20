# Job-Scout

A personal job-search agent for internship/junior Data Science/AI/ML/software
engineering roles across Europe, and a portfolio project demonstrating the
full DS lifecycle — not just LLM orchestration.

Most "AI job agent" projects use an LLM to judge fit on every run. Job-Scout instead
trains a real ranking model on self-labeled fit data and benchmarks it against
LLM-as-judge and embedding-similarity baselines. See [PROJECT_BRIEF.md](PROJECT_BRIEF.md)
for the full scope, rationale, and build order.

## Status

Scraper + database layer built and tested: ~80 companies across 5 ATS platforms
(Workday, Greenhouse, Lever, Ashby, SmartRecruiters), each verified against the real
posting API rather than trusted from a guessed slug or the HTML page. Magnet.me/
StudentJob.nl (NL-only student job boards) were tried first and retired once the scope
went global — see git history around 2026-07-19. Region scope narrowed from Europe+US
back to Europe-only around 2026-07-20 (see PROJECT_BRIEF.md for why).

LLM extraction into structured fields is also built: two interchangeable providers
(Ollama, local/free, default; Gemini API, rate-limited free tier) sharing one prompt so
results stay comparable model-vs-model rather than prompt-vs-prompt. Labeling and
ranking are not built yet.

## Language split

The scraping/database/LLM-extraction/labeling/agent-loop layers are written in **Java**
(learning goal). The ranking-model work (steps 4-5 of the build order) will be
**Python**, since scikit-learn/LightGBM have no comparable Java equivalent. Both share
the same SQLite file.

## Project structure

```
JobHunterTech/        # Java: scraper, database, LLM extraction, (later) labeling CLI + agent loop
├── build.gradle
├── src/main/java/com/jobscout/
│   ├── scraper/       # shared infra (HttpFetcher, TargetRegion, relevance patterns) +
│   │   ├── workday/ greenhouse/ lever/ ashby/ smartrecruiters/  # one scraper class per ATS platform
│   ├── extraction/    # Extractor interface + Gemini/Ollama implementations, shared prompt
│   ├── db/            # SQLite schema init + upsert
│   ├── Main.java             # runs all scrapers
│   └── ExtractionMain.java   # runs LLM extraction over unextracted vacancies
└── src/test/java/com/jobscout/
python/                # Python: ranking model + benchmark (added when that work starts)
data/                  # gitignored, local SQLite file lives here
```

## Setup

```bash
cd JobHunterTech
cp ../.env.example ../.env  # fill in your own values -- shared at the repo root
./gradlew test
./gradlew run           # runs all configured scrapers, writes into ../data/job_scout.db
```

Gradle's own daemon needs a JDK it supports as its runtime (JDK 26 was too new for
Gradle 9.2 at the time this was set up) — if `./gradlew` fails with "Unsupported
class file major version", point `JAVA_HOME` at an older JDK (21 works) before
running it.

To run LLM extraction, the default provider is a local Ollama model (free, no rate
limits):

```bash
brew install ollama && brew services start ollama && ollama pull qwen3:8b
```

`./gradlew run` always runs `Main` (the scrapers) — run `ExtractionMain`'s `main()`
directly from your IDE instead. To use the Gemini API instead of Ollama, set
`EXTRACTION_PROVIDER=gemini` in `.env` and fill in `GEMINI_API_KEYS`.

## Build order

1. Scraper + database — done
2. LLM extraction into structured fields — done
3. Labeling CLI
4. Ranking model
5. Benchmark (trained model vs. LLM-as-judge vs. embedding similarity)
6. Daily agent loop
7. Market analysis notebook

Each step builds on the previous one's data — see PROJECT_BRIEF.md before skipping ahead.
