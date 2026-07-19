"""StudentJob.nl scraper, focused on Data Science / AI / software-engineering internships.

studentjob.nl's robots.txt disallows crawling the English-locale job path
(`/vacancies*`) but does not disallow the Dutch-locale path (`/vacatures/`),
which is where actual listings live -- so that's the path we visit here.
Same approach as Magnet.me: discover candidate URLs from their public
sitemap and filter by title before fetching, rather than using their
(disallowed) search pages.

Each posting embeds a schema.org JobPosting as JSON-LD, plus a separate
`<meta property="languageRequirements">` tag in the page head -- a simpler,
plainer field to read than Magnet.me's rendered "Required language" box.

Note: this site's JSON-LD `employmentType` field is unreliable (real
internships are frequently tagged "FULL_TIME"), so -- same as Magnet.me --
internship detection relies on keyword-matching the title/URL, not that
field. The JSON-LD `title` field is unreliable too -- on some postings it
holds a generic occupational category (e.g. "ICT / IT / Programmeur")
instead of the actual role name -- so the title is read from the page's
`<h1 itemprop="title">` instead, which was accurate on every sample checked.
"""

import html
import json
import re
import sqlite3
import xml.etree.ElementTree as ET

from bs4 import BeautifulSoup

from src.db.repository import VacancyRecord, upsert_vacancy
from src.scrapers.base import BaseScraper, ScraperError

SITEMAP_URL = "https://www.studentjob.nl/sitemap/job_openings.xml"
SITEMAP_NS = {"sm": "http://www.sitemaps.org/schemas/sitemap/0.9"}

INTERNSHIP_PATTERN = re.compile(r"\b(stage|intern|internship)\b", re.I)
RELEVANCE_PATTERN = re.compile(
    r"data-scien|machine-learning|\bai\b|artificial-intelligence|data-analy|\bml\b|data-engineer"
    r"|software-engineer|software-develop|\bdeveloper\b|backend|back-end|frontend|front-end"
    r"|full-stack|fullstack|\bprogrammer\b|devops",
    re.I,
)

LD_JSON_PATTERN = re.compile(
    r'<script[^>]*type="application/ld\+json"[^>]*>(.*?)</script>', re.S
)


def _clean_description(posting: dict) -> str:
    raw_html = html.unescape(posting.get("description") or "")
    return BeautifulSoup(raw_html, "html.parser").get_text(separator="\n").strip()


def _extract_job_posting(page_html: str) -> dict | None:
    for match in LD_JSON_PATTERN.finditer(page_html):
        try:
            data = json.loads(match.group(1))
        except json.JSONDecodeError:
            continue
        if data.get("@type") == "JobPosting":
            return data
    return None


def _extract_required_language(page_html: str) -> str | None:
    """Read the page's own <meta property="languageRequirements"> tag."""
    soup = BeautifulSoup(page_html, "html.parser")
    tag = soup.find("meta", attrs={"property": "languageRequirements"})
    return tag.get("content") if tag else None


def _extract_title(page_html: str) -> str | None:
    """Read the page's <h1 itemprop="title"> -- more reliable than JSON-LD's title."""
    soup = BeautifulSoup(page_html, "html.parser")
    tag = soup.find(attrs={"itemprop": "title"})
    return tag.get_text(strip=True) if tag else None


def _job_location(posting: dict) -> dict:
    locations = posting.get("jobLocation") or []
    if isinstance(locations, dict):
        locations = [locations]
    return (locations[0] or {}) if locations else {}


def _country(posting: dict) -> str | None:
    address = _job_location(posting).get("address") or {}
    return address.get("addressCountry")


class StudentJobScraper(BaseScraper):
    source_name = "studentjob"

    def fetch_candidate_urls(self) -> list[str]:
        response = self.get(SITEMAP_URL)
        root = ET.fromstring(response.content)
        urls = [
            loc.text for loc in root.iterfind(".//sm:url/sm:loc", SITEMAP_NS) if loc.text
        ]
        return [url for url in urls if INTERNSHIP_PATTERN.search(url) and RELEVANCE_PATTERN.search(url)]

    def to_vacancy(self, url: str, title: str, posting: dict) -> VacancyRecord:
        raw_text = _clean_description(posting)
        company = (posting.get("hiringOrganization") or {}).get("name")
        address = _job_location(posting).get("address") or {}
        location = ", ".join(
            part for part in (address.get("addressLocality"), address.get("addressCountry")) if part
        ) or None
        return VacancyRecord(
            source=self.source_name,
            url=url,
            title=title,
            company=company,
            location=location,
            raw_text=raw_text,
        )

    def run(self, conn: sqlite3.Connection) -> int:
        count = 0
        for url in self.fetch_candidate_urls():
            try:
                response = self.get(url)
            except ScraperError as exc:
                print(f"Skipping {url}: {exc}")
                continue

            posting = _extract_job_posting(response.text)
            if posting is None:
                print(f"Skipping {url}: no JobPosting data found on page")
                continue

            country = _country(posting)
            if country is not None and country != "NL":
                print(f"Skipping {url}: not in the Netherlands ({country})")
                continue

            required_language = _extract_required_language(response.text)
            if required_language and "nederlands" in required_language.lower():
                print(f"Skipping {url}: requires Dutch ({required_language})")
                continue

            title = _extract_title(response.text) or posting.get("title")
            if not title:
                print(f"Skipping {url}: could not determine a title")
                continue

            try:
                vacancy = self.to_vacancy(url, title, posting)
            except KeyError as exc:
                print(f"Skipping malformed posting at {url}: missing {exc}")
                continue

            upsert_vacancy(conn, vacancy)
            count += 1
        return count


if __name__ == "__main__":
    import os

    from dotenv import load_dotenv

    from src.db.schema import init_db

    load_dotenv()
    db_path = os.environ.get("DATABASE_PATH", "data/job_scout.db")
    init_db(db_path)
    conn = sqlite3.connect(db_path)
    scraper = StudentJobScraper()
    try:
        total = scraper.run(conn)
        print(f"Upserted {total} vacancies from StudentJob.nl.")
    finally:
        scraper.close()
        conn.close()
