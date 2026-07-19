"""Shared scraper base class: rate limiting, User-Agent, and request error handling."""

import os
import random
import time

import httpx


class ScraperError(Exception):
    """Raised when a scraper can't get usable data (bad response, changed layout, etc.)."""


class BaseScraper:
    source_name = "base"

    def __init__(self, client: httpx.Client | None = None):
        user_agent = os.environ.get(
            "SCRAPER_USER_AGENT", "job-scout-bot/0.1 (personal project)"
        )
        self.min_delay = float(os.environ.get("SCRAPER_MIN_DELAY_SECONDS", "2"))
        self.max_delay = float(os.environ.get("SCRAPER_MAX_DELAY_SECONDS", "5"))
        self.client = client or httpx.Client(headers={"User-Agent": user_agent}, timeout=10.0)

    def get(self, url: str) -> httpx.Response:
        try:
            response = self.client.get(url)
            response.raise_for_status()
        except httpx.HTTPError as exc:
            raise ScraperError(f"Request to {url} failed: {exc}") from exc
        finally:
            time.sleep(random.uniform(self.min_delay, self.max_delay))
        return response

    def close(self) -> None:
        self.client.close()
