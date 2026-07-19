import json
import sqlite3

import httpx

from src.db.schema import init_db
from src.scrapers.magnetme import MagnetMeScraper

SITEMAP_XML = b"""<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
  <url><loc>https://magnet.me/en/opportunity/1/data-engineer-intern</loc></url>
  <url><loc>https://magnet.me/en/opportunity/2/marketing-internship</loc></url>
  <url><loc>https://magnet.me/en/opportunity/3/data-scientist--full-time</loc></url>
  <url><loc>https://magnet.me/nl-NL/vacature/70731/account-support-manager-afh</loc></url>
</urlset>
"""

JOB_POSTING = {
    "@context": "https://schema.org/",
    "@type": "JobPosting",
    "title": "Data Engineer Intern",
    "description": "<p>Join our <strong>data</strong> team.</p>",
    "hiringOrganization": {"name": "Churned"},
    "jobLocation": {
        "address": {"addressLocality": "Amsterdam", "addressCountry": "NL"}
    },
}

LANGUAGE_BOX_TEMPLATE = """
<div data-testid="languages">
  <div>Required language</div>
  <div>{entries}</div>
</div>
"""


def _language_box(*languages: str) -> str:
    entries = "".join(f"<div><span>{lang}</span><span> (Fluent)</span></div>" for lang in languages)
    return LANGUAGE_BOX_TEMPLATE.format(entries=entries)


JOB_PAGE_HTML = f"""
<html><head></head><body>
<script type="application/ld+json">{json.dumps(JOB_POSTING)}</script>
{_language_box("English")}
</body></html>
"""


def _mock_client():
    def handler(request):
        url = str(request.url)
        if url.endswith("en-opportunities.xml"):
            return httpx.Response(200, content=SITEMAP_XML)
        if url.endswith("/1/data-engineer-intern"):
            return httpx.Response(200, text=JOB_PAGE_HTML)
        raise AssertionError(f"Unexpected request: {url}")

    return httpx.Client(transport=httpx.MockTransport(handler))


def test_fetch_candidate_urls_filters_to_relevant_internships(monkeypatch):
    monkeypatch.setenv("SCRAPER_MIN_DELAY_SECONDS", "0")
    monkeypatch.setenv("SCRAPER_MAX_DELAY_SECONDS", "0")

    scraper = MagnetMeScraper(client=_mock_client())
    urls = scraper.fetch_candidate_urls()

    assert urls == ["https://magnet.me/en/opportunity/1/data-engineer-intern"]


def test_run_stores_matched_posting(tmp_path, monkeypatch):
    monkeypatch.setenv("SCRAPER_MIN_DELAY_SECONDS", "0")
    monkeypatch.setenv("SCRAPER_MAX_DELAY_SECONDS", "0")

    db_path = tmp_path / "test.db"
    init_db(str(db_path))
    conn = sqlite3.connect(str(db_path))

    scraper = MagnetMeScraper(client=_mock_client())
    count = scraper.run(conn)

    rows = conn.execute("SELECT title, company, location, raw_text FROM vacancies").fetchall()
    conn.close()

    assert count == 1
    assert rows == [("Data Engineer Intern", "Churned", "Amsterdam, NL", "Join our \ndata\n team.")]


def test_to_vacancy_maps_json_ld_fields():
    scraper = MagnetMeScraper(client=_mock_client())

    vacancy = scraper.to_vacancy("https://magnet.me/en/opportunity/1/data-engineer-intern", JOB_POSTING)

    assert vacancy.title == "Data Engineer Intern"
    assert vacancy.company == "Churned"
    assert vacancy.location == "Amsterdam, NL"
    assert vacancy.raw_text == "Join our \ndata\n team."


DUTCH_JOB_POSTING = {
    "@context": "https://schema.org/",
    "@type": "JobPosting",
    "title": "Data Analyst Intern",
    "description": "<p>You analyze data. Dutch and English are both spoken in the office.</p>",
    "hiringOrganization": {"name": "Acme NL"},
    "jobLocation": {"address": {"addressLocality": "Utrecht", "addressCountry": "NL"}},
}

# The posting's own text is in English, but its structured "Required language"
# box (the field magnet.me actually surfaces) lists Dutch -- this is the
# authoritative signal we filter on, not text-guessing.
DUTCH_JOB_PAGE_HTML = f"""
<html><head></head><body>
<script type="application/ld+json">{json.dumps(DUTCH_JOB_POSTING)}</script>
{_language_box("English", "Dutch")}
</body></html>
"""

SITEMAP_XML_WITH_DUTCH = b"""<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
  <url><loc>https://magnet.me/en/opportunity/1/data-engineer-intern</loc></url>
  <url><loc>https://magnet.me/en/opportunity/4/data-analyst-intern</loc></url>
</urlset>
"""


def _mock_client_with_dutch_posting():
    def handler(request):
        url = str(request.url)
        if url.endswith("en-opportunities.xml"):
            return httpx.Response(200, content=SITEMAP_XML_WITH_DUTCH)
        if url.endswith("/1/data-engineer-intern"):
            return httpx.Response(200, text=JOB_PAGE_HTML)
        if url.endswith("/4/data-analyst-intern"):
            return httpx.Response(200, text=DUTCH_JOB_PAGE_HTML)
        raise AssertionError(f"Unexpected request: {url}")

    return httpx.Client(transport=httpx.MockTransport(handler))


def test_extract_required_languages_reads_the_structured_box():
    from src.scrapers.magnetme import _extract_required_languages

    assert _extract_required_languages(JOB_PAGE_HTML) == ["English"]
    assert _extract_required_languages(DUTCH_JOB_PAGE_HTML) == ["English", "Dutch"]


def test_run_skips_postings_that_require_dutch(tmp_path, monkeypatch):
    monkeypatch.setenv("SCRAPER_MIN_DELAY_SECONDS", "0")
    monkeypatch.setenv("SCRAPER_MAX_DELAY_SECONDS", "0")

    db_path = tmp_path / "test.db"
    init_db(str(db_path))
    conn = sqlite3.connect(str(db_path))

    scraper = MagnetMeScraper(client=_mock_client_with_dutch_posting())
    count = scraper.run(conn)

    rows = conn.execute("SELECT title FROM vacancies").fetchall()
    conn.close()

    assert count == 1
    assert rows == [("Data Engineer Intern",)]


# Description text is in Dutch and there's no "Required language" box at all --
# per the user, only the actual language *requirement* matters, not what
# language the ad happens to be written in, so this should NOT be skipped.
DUTCH_LANGUAGE_JOB_POSTING = {
    "@context": "https://schema.org/",
    "@type": "JobPosting",
    "title": "Stage Data Analyse",
    "description": (
        "<p>Wij zoeken een gemotiveerde stagiair(e) die ons team komt versterken "
        "met data-analyse en machine learning. Je werkt samen met collega's aan "
        "analytische vraagstukken en leert veel over ons bedrijf en onze klanten.</p>"
    ),
    "hiringOrganization": {"name": "Voorbeeld BV"},
    "jobLocation": {"address": {"addressLocality": "Rotterdam", "addressCountry": "NL"}},
}

DUTCH_LANGUAGE_JOB_PAGE_HTML = f"""
<html><head></head><body>
<script type="application/ld+json">{json.dumps(DUTCH_LANGUAGE_JOB_POSTING)}</script>
</body></html>
"""

SITEMAP_XML_WITH_DUTCH_LANGUAGE = b"""<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
  <url><loc>https://magnet.me/en/opportunity/1/data-engineer-intern</loc></url>
  <url><loc>https://magnet.me/en/opportunity/5/data-analyse-intern</loc></url>
</urlset>
"""


def _mock_client_with_dutch_language_posting():
    def handler(request):
        url = str(request.url)
        if url.endswith("en-opportunities.xml"):
            return httpx.Response(200, content=SITEMAP_XML_WITH_DUTCH_LANGUAGE)
        if url.endswith("/1/data-engineer-intern"):
            return httpx.Response(200, text=JOB_PAGE_HTML)
        if url.endswith("/5/data-analyse-intern"):
            return httpx.Response(200, text=DUTCH_LANGUAGE_JOB_PAGE_HTML)
        raise AssertionError(f"Unexpected request: {url}")

    return httpx.Client(transport=httpx.MockTransport(handler))


def test_run_does_not_skip_based_on_description_language_alone(tmp_path, monkeypatch):
    monkeypatch.setenv("SCRAPER_MIN_DELAY_SECONDS", "0")
    monkeypatch.setenv("SCRAPER_MAX_DELAY_SECONDS", "0")

    db_path = tmp_path / "test.db"
    init_db(str(db_path))
    conn = sqlite3.connect(str(db_path))

    scraper = MagnetMeScraper(client=_mock_client_with_dutch_language_posting())
    count = scraper.run(conn)

    rows = conn.execute("SELECT title FROM vacancies").fetchall()
    conn.close()

    assert count == 2
    assert {row[0] for row in rows} == {"Data Engineer Intern", "Stage Data Analyse"}


FOREIGN_JOB_POSTING = {
    "@context": "https://schema.org/",
    "@type": "JobPosting",
    "title": "Data Analyst Intern - Berlin",
    "description": "<p>Join our data team in Berlin.</p>",
    "hiringOrganization": {"name": "Acme DE"},
    "jobLocation": {"address": {"addressLocality": "Berlin", "addressCountry": "DE"}},
}

FOREIGN_JOB_PAGE_HTML = f"""
<html><head></head><body>
<script type="application/ld+json">{json.dumps(FOREIGN_JOB_POSTING)}</script>
{_language_box("English")}
</body></html>
"""

SITEMAP_XML_WITH_FOREIGN = b"""<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
  <url><loc>https://magnet.me/en/opportunity/1/data-engineer-intern</loc></url>
  <url><loc>https://magnet.me/en/opportunity/6/data-analyst-intern-berlin</loc></url>
</urlset>
"""


def _mock_client_with_foreign_posting():
    def handler(request):
        url = str(request.url)
        if url.endswith("en-opportunities.xml"):
            return httpx.Response(200, content=SITEMAP_XML_WITH_FOREIGN)
        if url.endswith("/1/data-engineer-intern"):
            return httpx.Response(200, text=JOB_PAGE_HTML)
        if url.endswith("/6/data-analyst-intern-berlin"):
            return httpx.Response(200, text=FOREIGN_JOB_PAGE_HTML)
        raise AssertionError(f"Unexpected request: {url}")

    return httpx.Client(transport=httpx.MockTransport(handler))


def test_run_skips_postings_outside_the_netherlands(tmp_path, monkeypatch):
    monkeypatch.setenv("SCRAPER_MIN_DELAY_SECONDS", "0")
    monkeypatch.setenv("SCRAPER_MAX_DELAY_SECONDS", "0")

    db_path = tmp_path / "test.db"
    init_db(str(db_path))
    conn = sqlite3.connect(str(db_path))

    scraper = MagnetMeScraper(client=_mock_client_with_foreign_posting())
    count = scraper.run(conn)

    rows = conn.execute("SELECT title FROM vacancies").fetchall()
    conn.close()

    assert count == 1
    assert rows == [("Data Engineer Intern",)]
