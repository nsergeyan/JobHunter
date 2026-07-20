-- Dedup strategy:
-- UNIQUE (source, url) treats each (source, url) pair as one vacancy. Re-scraping an
-- already-seen url updates last_seen (and raw_text, if the posting changed) rather than
-- inserting a duplicate row.
--
-- This does NOT dedup the same job posted on multiple sources (e.g. a listing on
-- Magnet.me that also has its own Greenhouse page) -- that requires fuzzy matching on
-- title + company + content, which is deferred to a later pass once we have enough
-- real data to see how often it actually happens.

CREATE TABLE IF NOT EXISTS vacancies (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    source TEXT NOT NULL,          -- e.g. 'magnet', 'indeed_nl', 'greenhouse', 'lever'
    url TEXT NOT NULL,
    title TEXT NOT NULL,
    company TEXT,
    location TEXT,
    raw_text TEXT,
    scraped_at TEXT NOT NULL,      -- ISO 8601 timestamp of the scrape that wrote this row
    first_seen TEXT NOT NULL,      -- ISO 8601 timestamp we first saw this (source, url)
    last_seen TEXT NOT NULL,       -- ISO 8601 timestamp we last saw it still listed
    UNIQUE (source, url)
);

CREATE INDEX IF NOT EXISTS idx_vacancies_scraped_at ON vacancies (scraped_at);
CREATE INDEX IF NOT EXISTS idx_vacancies_company ON vacancies (company);

-- Extracted structured fields per vacancy (LLM extraction, build-order step 2).
-- Kept as a separate table rather than new columns on vacancies: SQLite has no
-- "ALTER TABLE ADD COLUMN IF NOT EXISTS", but CREATE TABLE IF NOT EXISTS re-runs
-- safely on every startup, so this stays idempotent like the rest of this file.
CREATE TABLE IF NOT EXISTS vacancy_extractions (
    vacancy_id INTEGER PRIMARY KEY REFERENCES vacancies(id) ON DELETE CASCADE,
    skills TEXT,                   -- JSON array, e.g. '["Python","SQL"]'
    seniority TEXT,                -- internship | junior | mid | senior | unknown
    salary_min INTEGER,
    salary_max INTEGER,
    salary_currency TEXT,          -- ISO code, e.g. 'USD', 'EUR'
    salary_period TEXT,            -- hourly | yearly | unknown -- don't compare salary_min/max
                                    -- across rows without checking this first
    language_requirement TEXT,
    remote_policy TEXT,            -- remote | hybrid | onsite | unknown
    extraction_model TEXT NOT NULL,
