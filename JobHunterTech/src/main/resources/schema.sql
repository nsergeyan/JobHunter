-- Dedup strategy:
-- UNIQUE (source, url) treats each (source, url) pair as one vacancy. Re-scraping an
-- already-seen url updates last_seen (and raw_text, if the posting changed) rather than
-- inserting a duplicate row.
--
-- Problem this misses: a URL is not a stable job identity. Some platforms hand back a
-- different url for the same job over time (e.g. Greenhouse's absolute_url can switch
-- from job-boards.greenhouse.io to the company's own careers domain), so the same job
-- lands as a second row. external_id below holds the platform's OWN stable posting id
-- (Greenhouse/Ashby/Lever job id, SmartRecruiters posting id, Workday externalPath, and
-- for MagnetMe, which has none, the url itself). The scrapers populate it now. The
-- identity flip to UNIQUE (source, external_id) waits until existing rows are
-- backfilled and de-duplicated (can't build a unique index while duplicates exist).
--
-- This still does NOT dedup the same job posted on multiple sources (e.g. a listing on
-- Magnet.me that also has its own Greenhouse page) -- that requires fuzzy matching on
-- title + company + content, which is deferred to a later pass once we have enough
-- real data to see how often it actually happens.

CREATE TABLE IF NOT EXISTS vacancies (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    source TEXT NOT NULL,          -- e.g. 'magnet', 'indeed_nl', 'greenhouse', 'lever'
    external_id TEXT,              -- platform's own stable posting id, NULL on legacy rows
                                    -- until backfilled (see dedup strategy note above)
    url TEXT NOT NULL,
    title TEXT NOT NULL,
    company TEXT,
    location TEXT,
    raw_text TEXT,
    scraped_at TEXT NOT NULL,      -- ISO 8601 timestamp of the scrape that wrote this row
    first_seen TEXT NOT NULL,      -- ISO 8601 timestamp we first saw this (source, url)
    last_seen TEXT NOT NULL,       -- ISO 8601 timestamp we last saw it still listed
    closed_at TEXT,                -- ISO 8601 timestamp we first noticed it had vanished from
                                    -- its board, NULL while still listed. Set only after a
                                    -- clean full read of that company's listing, so an outage
                                    -- cannot mass-close a board
    UNIQUE (source, url)
);

CREATE INDEX IF NOT EXISTS idx_vacancies_scraped_at ON vacancies (scraped_at);
CREATE INDEX IF NOT EXISTS idx_vacancies_company ON vacancies (company);

-- The UNIQUE (source, external_id) index (real job identity) is created in
-- SchemaInitializer, not here, so it runs AFTER the external_id column migration on
-- upgraded DBs (this file executes before that migration, when the column may not exist yet).

-- Extracted structured fields per vacancy (LLM extraction, build-order step 2).
-- Kept as a separate table rather than new columns on vacancies: SQLite has no
-- "ALTER TABLE ADD COLUMN IF NOT EXISTS", but CREATE TABLE IF NOT EXISTS re-runs
-- safely on every startup, so this stays idempotent like the rest of this file.
CREATE TABLE IF NOT EXISTS vacancy_extractions (
    vacancy_id INTEGER PRIMARY KEY REFERENCES vacancies(id) ON DELETE CASCADE,
    summary TEXT,                  -- 2-3 sentence plain-English: what the company does
                                    -- + what you'd actually do day-to-day
    skills TEXT,                   -- JSON array, e.g. '["Python","SQL"]'
    seniority TEXT,                -- internship | junior | mid | senior | unknown
    salary_min INTEGER,
    salary_max INTEGER,
    salary_currency TEXT,          -- ISO code, e.g. 'USD', 'EUR'
    salary_period TEXT,            -- hourly | monthly | yearly | unknown -- don't compare salary_min/max
                                    -- across rows without checking this first
    language_requirement TEXT,
    remote_policy TEXT,            -- remote | hybrid | onsite | unknown
    extraction_model TEXT NOT NULL,
    extracted_at TEXT NOT NULL     -- ISO 8601 timestamp of the extraction call
);

-- Lets two-step scrapers (SmartRecruiters, Workday) skip re-fetching a posting's
-- full detail page on every rerun once it's already been evaluated once, accepted
-- or not. Greenhouse/Lever/Ashby return everything (list + full content) in one
-- request per company already, so there's no separate detail-fetch step for them
-- to skip -- this table only matters for the two platforms that do a second
-- request per candidate posting.
CREATE TABLE IF NOT EXISTS seen_postings (
    source TEXT NOT NULL,
    external_id TEXT NOT NULL,     -- SmartRecruiters posting id, or Workday externalPath
    accepted INTEGER NOT NULL,     -- 1 if it passed filters and was stored, 0 if rejected
    seen_at TEXT NOT NULL,         -- ISO 8601 timestamp of the first evaluation
    filter_version INTEGER,        -- which FilterVersion.CURRENT produced this verdict -- a
                                    -- posting counts as seen only when this matches the
                                    -- version running now, so loosening a scrape-time filter
                                    -- re-opens everything it previously rejected
    PRIMARY KEY (source, external_id)
);

-- One row per company per scrape. When a board changes shape or a token goes stale the
-- scraper prints one "Skipping Acme" line among hundreds and carries on, so a company can
-- quietly stop being scraped for weeks. These rows make that visible and give the digest
-- something to report. company is NULL for sources that are not scraped per company.
CREATE TABLE IF NOT EXISTS scrape_runs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    source TEXT NOT NULL,
    company TEXT,
    started_at TEXT NOT NULL,      -- ISO 8601, also the cutoff for closing stale postings
    finished_at TEXT NOT NULL,
    fetched INTEGER NOT NULL DEFAULT 0,   -- postings the board returned, before filtering
    accepted INTEGER NOT NULL DEFAULT 0,  -- passed every filter and were stored
    rejected INTEGER NOT NULL DEFAULT 0,  -- returned but filtered out
    error TEXT                     -- NULL on success, the failure message otherwise
);

CREATE INDEX IF NOT EXISTS idx_scrape_runs_finished_at ON scrape_runs (finished_at);
