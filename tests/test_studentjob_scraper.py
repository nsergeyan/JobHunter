import json
import sqlite3

import httpx

from src.db.schema import init_db
from src.scrapers.studentjob import StudentJobScraper

SITEMAP_XML = b"""<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
  <url><loc>https://www.studentjob.nl/vacatures/1-machine-learning-engineer-intern</loc></url>
  <url><loc>https://www.studentjob.nl/vacatures/2-weekend-supermarket-job</loc></url>
  <url><loc>https://www.studentjob.nl/vacatures/3-data-scientist-fulltime</loc></url>
</urlset>
"""

JOB_POSTING = {
    "@context": "http://schema.org",
    "@type": "JobPosting",
    "title": "Machine Learning Engineer Intern",
    "description": "<p>Join our <strong>ML</strong> team.</p>",
    "employmentType": ["FULL_TIME"],
    "hiringOrganization": {"name": "Acme"},
    "jobLocation": [
        {"address": {"addressLocality": "Amsterdam", "addressCountry": "NL"}}
    ],
}


def _page_html(posting: dict, language: str = "Engels", title: str | None = None) -> str:
    displayed_title = title if title is not None else posting["title"]
    return f"""
<html><head>
<meta property="languageRequirements" content="{language}" />
</head><body>
<h1 itemprop="title">{displayed_title}</h1>
<script type="application/ld+json">{json.dumps(posting)}</script>
</body></html>
"""


JOB_PAGE_HTML = _page_html(JOB_POSTING)


def _mock_client():
    def handler(request):
        url = str(request.url)
        if url.endswith("job_openings.xml"):
            return httpx.Response(200, content=SITEMAP_XML)
        if url.endswith("/1-machine-learning-engineer-intern"):
            return httpx.Response(200, text=JOB_PAGE_HTML)
        raise AssertionError(f"Unexpected request: {url}")

    return httpx.Client(transport=httpx.MockTransport(handler))


def test_fetch_candidate_urls_filters_to_relevant_internships(monkeypatch):
    monkeypatch.setenv("SCRAPER_MIN_DELAY_SECONDS", "0")
    monkeypatch.setenv("SCRAPER_MAX_DELAY_SECONDS", "0")

    scraper = StudentJobScraper(client=_mock_client())
    urls = scraper.fetch_candidate_urls()

    assert urls == ["https://www.studentjob.nl/vacatures/1-machine-learning-engineer-intern"]


def test_run_stores_matched_posting(tmp_path, monkeypatch):
    monkeypatch.setenv("SCRAPER_MIN_DELAY_SECONDS", "0")
    monkeypatch.setenv("SCRAPER_MAX_DELAY_SECONDS", "0")

    db_path = tmp_path / "test.db"
    init_db(str(db_path))
    conn = sqlite3.connect(str(db_path))

    scraper = StudentJobScraper(client=_mock_client())
    count = scraper.run(conn)

    rows = conn.execute("SELECT title, company, location, raw_text FROM vacancies").fetchall()
    conn.close()

    assert count == 1
    assert rows == [("Machine Learning Engineer Intern", "Acme", "Amsterdam, NL", "Join our \nML\n team.")]


def test_to_vacancy_maps_json_ld_fields():
    scraper = StudentJobScraper(client=_mock_client())

    vacancy = scraper.to_vacancy(
        "https://www.studentjob.nl/vacatures/1-machine-learning-engineer-intern",
        "Machine Learning Engineer Intern",
        JOB_POSTING,
    )

    assert vacancy.title == "Machine Learning Engineer Intern"
    assert vacancy.company == "Acme"
    assert vacancy.location == "Amsterdam, NL"
    assert vacancy.raw_text == "Join our \nML\n team."


def test_extract_required_language_reads_the_meta_tag():
    from src.scrapers.studentjob import _extract_required_language

    assert _extract_required_language(JOB_PAGE_HTML) == "Engels"
    assert _extract_required_language(_page_html(JOB_POSTING, "Nederlands")) == "Nederlands"


def test_run_uses_h1_title_not_json_ld_title_when_they_differ(tmp_path, monkeypatch):
    """Regression test: on real studentjob.nl postings, JSON-LD `title` is
    sometimes a generic category (e.g. "ICT / IT / Programmeur") while the
    page's own <h1 itemprop="title"> holds the real, specific role name."""
    monkeypatch.setenv("SCRAPER_MIN_DELAY_SECONDS", "0")
    monkeypatch.setenv("SCRAPER_MAX_DELAY_SECONDS", "0")

    mismatched_posting = {**JOB_POSTING, "title": "ICT / IT / Programmeur"}
    page_html = _page_html(mismatched_posting, title="Machine Learning Engineer Intern")

    def handler(request):
        url = str(request.url)
        if url.endswith("job_openings.xml"):
            return httpx.Response(200, content=SITEMAP_XML)
        if url.endswith("/1-machine-learning-engineer-intern"):
            return httpx.Response(200, text=page_html)
        raise AssertionError(f"Unexpected request: {url}")

    db_path = tmp_path / "test.db"
    init_db(str(db_path))
    conn = sqlite3.connect(str(db_path))

    scraper = StudentJobScraper(client=httpx.Client(transport=httpx.MockTransport(handler)))
    count = scraper.run(conn)

    rows = conn.execute("SELECT title FROM vacancies").fetchall()
    conn.close()

    assert count == 1
    assert rows == [("Machine Learning Engineer Intern",)]


DUTCH_SITEMAP_XML = b"""<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
  <url><loc>https://www.studentjob.nl/vacatures/1-machine-learning-engineer-intern</loc></url>
  <url><loc>https://www.studentjob.nl/vacatures/4-data-analyst-intern</loc></url>
</urlset>
"""

DUTCH_JOB_POSTING = {
    "@context": "http://schema.org",
    "@type": "JobPosting",
    "title": "Data Analyst Intern",
    "description": "<p>You analyze data.</p>",
    "employmentType": ["FULL_TIME"],
    "hiringOrganization": {"name": "Acme NL"},
    "jobLocation": [
        {"address": {"addressLocality": "Utrecht", "addressCountry": "NL"}}
    ],
}

DUTCH_JOB_PAGE_HTML = _page_html(DUTCH_JOB_POSTING, "Nederlands")


def _mock_client_with_dutch_posting():
    def handler(request):
        url = str(request.url)
        if url.endswith("job_openings.xml"):
            return httpx.Response(200, content=DUTCH_SITEMAP_XML)
        if url.endswith("/1-machine-learning-engineer-intern"):
            return httpx.Response(200, text=JOB_PAGE_HTML)
        if url.endswith("/4-data-analyst-intern"):
            return httpx.Response(200, text=DUTCH_JOB_PAGE_HTML)
        raise AssertionError(f"Unexpected request: {url}")

    return httpx.Client(transport=httpx.MockTransport(handler))


def test_run_skips_postings_that_require_dutch(tmp_path, monkeypatch):
    monkeypatch.setenv("SCRAPER_MIN_DELAY_SECONDS", "0")
    monkeypatch.setenv("SCRAPER_MAX_DELAY_SECONDS", "0")

    db_path = tmp_path / "test.db"
    init_db(str(db_path))
    conn = sqlite3.connect(str(db_path))

    scraper = StudentJobScraper(client=_mock_client_with_dutch_posting())
    count = scraper.run(conn)

    rows = conn.execute("SELECT title FROM vacancies").fetchall()
    conn.close()

    assert count == 1
    assert rows == [("Machine Learning Engineer Intern",)]


FOREIGN_SITEMAP_XML = b"""<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
  <url><loc>https://www.studentjob.nl/vacatures/1-machine-learning-engineer-intern</loc></url>
  <url><loc>https://www.studentjob.nl/vacatures/5-data-analyst-intern-berlin</loc></url>
</urlset>
"""

FOREIGN_JOB_POSTING = {
    "@context": "http://schema.org",
    "@type": "JobPosting",
    "title": "Data Analyst Intern - Berlin",
    "description": "<p>Join our data team in Berlin.</p>",
    "employmentType": ["FULL_TIME"],
    "hiringOrganization": {"name": "Acme DE"},
    "jobLocation": [
        {"address": {"addressLocality": "Berlin", "addressCountry": "DE"}}
    ],
}

FOREIGN_JOB_PAGE_HTML = _page_html(FOREIGN_JOB_POSTING, "Engels")


def _mock_client_with_foreign_posting():
    def handler(request):
        url = str(request.url)
        if url.endswith("job_openings.xml"):
            return httpx.Response(200, content=FOREIGN_SITEMAP_XML)
        if url.endswith("/1-machine-learning-engineer-intern"):
            return httpx.Response(200, text=JOB_PAGE_HTML)
        if url.endswith("/5-data-analyst-intern-berlin"):
            return httpx.Response(200, text=FOREIGN_JOB_PAGE_HTML)
        raise AssertionError(f"Unexpected request: {url}")

    return httpx.Client(transport=httpx.MockTransport(handler))


def test_run_skips_postings_outside_the_netherlands(tmp_path, monkeypatch):
    monkeypatch.setenv("SCRAPER_MIN_DELAY_SECONDS", "0")
    monkeypatch.setenv("SCRAPER_MAX_DELAY_SECONDS", "0")

    db_path = tmp_path / "test.db"
    init_db(str(db_path))
    conn = sqlite3.connect(str(db_path))

    scraper = StudentJobScraper(client=_mock_client_with_foreign_posting())
    count = scraper.run(conn)

    rows = conn.execute("SELECT title FROM vacancies").fetchall()
    conn.close()

    assert count == 1
    assert rows == [("Machine Learning Engineer Intern",)]
