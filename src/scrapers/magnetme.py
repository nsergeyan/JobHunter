"""Magnet.me scraper, focused on Data Science / AI internships in the Netherlands.

Magnet.me's robots.txt disallows crawling their search/listing pages (any URL
containing `query=` or `country=`), so instead of searching directly we:
  1. Download their public sitemap of opportunity URLs.
  2. Filter the URL list ourselves for internship + DS/AI-relevant titles.
  3. Visit only those specific opportunity pages (plain, non-query URLs -- allowed).

Each opportunity page embeds a schema.org JobPosting as JSON-LD, which gives us
clean structured fields (title, company, location, description) instead of
having to parse the rendered page markup.

Note: Python's stdlib `urllib.robotparser` does not support the wildcard
patterns (`*query=*`) or Crawl-delay used in this robots.txt and silently
mis-parses it -- so compliance here is handled explicitly (no query strings
are ever constructed, and the few specific URLs robots.txt calls out are
hardcoded below) rather than delegated to that module.
"""

import html
import json
import re
import sqlite3
import xml.etree.ElementTree as ET

from bs4 import BeautifulSoup

from src.db.repository import VacancyRecord, upsert_vacancy
from src.scrapers.base import BaseScraper, ScraperError

SITEMAP_URL = "https://magnet.me/sitemaps/en-opportunities.xml"
SITEMAP_NS = {"sm": "http://www.sitemaps.org/schemas/sitemap/0.9"}

INTERNSHIP_PATTERN = re.compile(r"\b(intern|internship)\b", re.I)
RELEVANCE_PATTERN = re.compile(
    r"data-scien|machine-learning|\bai\b|artificial-intelligence|data-analy|\bml\b|data-engineer"
    r"|software-engineer|software-develop|\bdeveloper\b|backend|back-end|frontend|front-end"
    r"|full-stack|fullstack|\bprogrammer\b|devops",
    re.I,
)

# Specific postings magnet.me's robots.txt calls out as crawled too often --
# excluded defensively even though they're unlikely to match our filters above.
EXCLUDED_URL_SUFFIXES = (
    "/nl-NL/vacature/70731/account-support-manager-afh",
    "/nl-NL/vacature/71574/one-finance-junior-consultant---accenture-consulting",
    "/en-GB/opportunity/64360/junior-android-developer---mobgen-accenture-interactive",
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


def _country(posting: dict) -> str | None:
    address = (posting.get("jobLocation") or {}).get("address") or {}
    return address.get("addressCountry")


def _extract_required_languages(page_html: str) -> list[str]:
    """Read the page's own "Required language(s)" box (data-testid="languages").

    This is Magnet.me's own structured field -- not part of the JSON-LD block --
    and is far more reliable than guessing from the free-text description.
    """
    soup = BeautifulSoup(page_html, "html.parser")
    container = soup.find(attrs={"data-testid": "languages"})
    if container is None:
        return []
    divs = container.find_all("div", recursive=False)
    if len(divs) < 2:
        return []
    value_div = divs[1]
    languages = []
    for entry in value_div.find_all("div", recursive=False):
        spans = entry.find_all("span")
        if spans:
            languages.append(spans[0].get_text(strip=True))
    return languages


class MagnetMeScraper(BaseScraper):
    source_name = "magnetme"

    def fetch_candidate_urls(self) -> list[str]:
        response = self.get(SITEMAP_URL)
        root = ET.fromstring(response.content)
        urls = [
            loc.text for loc in root.iterfind(".//sm:url/sm:loc", SITEMAP_NS) if loc.text
        ]
        return [
            url
            for url in urls
            if INTERNSHIP_PATTERN.search(url)
            and RELEVANCE_PATTERN.search(url)
            and not url.endswith(EXCLUDED_URL_SUFFIXES)
        ]

    def to_vacancy(self, url: str, posting: dict) -> VacancyRecord:
        raw_text = _clean_description(posting)
        company = (posting.get("hiringOrganization") or {}).get("name")
        address = (posting.get("jobLocation") or {}).get("address") or {}
        location = ", ".join(
            part for part in (address.get("addressLocality"), address.get("addressCountry")) if part
        ) or None
        return VacancyRecord(
            source=self.source_name,
            url=url,
            title=posting["title"],
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

            required_languages = _extract_required_languages(response.text)
            if any(lang.strip().lower() == "dutch" for lang in required_languages):
                print(f"Skipping {url}: requires Dutch ({', '.join(required_languages)})")
                continue

            try:
                vacancy = self.to_vacancy(url, posting)
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
    scraper = MagnetMeScraper()
    try:
        total = scraper.run(conn)
        print(f"Upserted {total} vacancies from Magnet.me.")
    finally:
        scraper.close()
        conn.close()
