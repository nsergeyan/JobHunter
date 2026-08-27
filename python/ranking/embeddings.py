"""Cosine-similarity scoring: embeds the user's preference profile once and every
posting's text, then scores each posting by how closely its embedding points in
the same direction as the profile's. Unlike the logistic regression model or the
LLM judge, this never looks at the 0/1/2 labels or reasons about the text --
it's a fixed, pretrained notion of semantic closeness.
"""

import json
import os
import urllib.error
import urllib.request

import numpy as np
import pandas as pd

from ranking.preferences import PREFERENCE_PROFILE

OLLAMA_BASE_URL = os.environ.get("OLLAMA_BASE_URL", "http://localhost:11434")
EMBED_MODEL = os.environ.get("OLLAMA_EMBED_MODEL", "nomic-embed-text")

# Starting budget. Note this is a CHARACTER budget standing in for the model's real
# limit, which is measured in TOKENS, and the two do not convert at a fixed rate.
# English runs about 4 chars per token, but a Polish posting in this corpus (ING
# Bank Slaski, 5953 chars) tokenises far more finely and blew past a 2048-token
# context that the same number of English characters would have fitted inside.
MAX_POSTING_CHARS = 6000

# Stop halving here. Below this a posting is too short to embed meaningfully, and a
# model still refusing it means something else is wrong and should surface.
MIN_POSTING_CHARS = 750


class ContextLengthExceeded(RuntimeError):
    """The model refused the text as too long. Retryable by sending less of it."""


def _embed_once(text: str) -> np.ndarray:
    body = json.dumps({"model": EMBED_MODEL, "prompt": text}).encode("utf-8")
    request = urllib.request.Request(
        f"{OLLAMA_BASE_URL}/api/embeddings", data=body, headers={"Content-Type": "application/json"}
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            payload = json.loads(response.read())
    except urllib.error.HTTPError as exc:
        # Ollama puts the actual reason in the response body. Without reading it you
        # get a bare "HTTP Error 500" that says nothing about which of the several
        # possible causes it was, which is exactly how this bug stayed puzzling.
        detail = exc.read().decode("utf-8", "replace")[:300]
        if "context length" in detail or "too long" in detail:
            raise ContextLengthExceeded(detail) from exc
        raise RuntimeError(f"Ollama embedding failed with HTTP {exc.code}: {detail}") from exc
    return np.array(payload["embedding"])


def embed(text: str) -> np.ndarray:
    """Embed a posting, shortening it only as far as the model actually requires.

    Adaptive rather than a fixed cut, because the right character budget depends on
    the language: trimming every posting to whatever the densest one needs would
    throw away half of the English ones for no reason.
    """
    limit = MAX_POSTING_CHARS
    while True:
        try:
            return _embed_once(text[:limit])
        except ContextLengthExceeded:
            if limit <= MIN_POSTING_CHARS:
                raise
            limit //= 2


def cosine_similarity(a: np.ndarray, b: np.ndarray) -> float:
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b)))


def embedding_scores(df: pd.DataFrame) -> np.ndarray:
    profile_vector = embed(PREFERENCE_PROFILE)
    scores = np.zeros(len(df))
    for i, raw_text in enumerate(df["raw_text"]):
        posting_vector = embed(raw_text or "")
        scores[i] = cosine_similarity(profile_vector, posting_vector)
        print(f"  embedded {i + 1}/{len(df)}", end="\r")
    print()
    return scores
