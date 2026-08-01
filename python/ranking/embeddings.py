"""Cosine-similarity scoring: embeds the user's preference profile once and every
posting's text, then scores each posting by how closely its embedding points in
the same direction as the profile's. Unlike the logistic regression model or the
LLM judge, this never looks at the 0/1/2 labels or reasons about the text --
it's a fixed, pretrained notion of semantic closeness.
"""

import json
import os
import urllib.request

import numpy as np
import pandas as pd

from ranking.preferences import PREFERENCE_PROFILE

OLLAMA_BASE_URL = os.environ.get("OLLAMA_BASE_URL", "http://localhost:11434")
EMBED_MODEL = os.environ.get("OLLAMA_EMBED_MODEL", "nomic-embed-text")
MAX_POSTING_CHARS = 6000


def embed(text: str) -> np.ndarray:
    body = json.dumps({"model": EMBED_MODEL, "prompt": text[:MAX_POSTING_CHARS]}).encode("utf-8")
    request = urllib.request.Request(
        f"{OLLAMA_BASE_URL}/api/embeddings", data=body, headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        payload = json.loads(response.read())
    return np.array(payload["embedding"])


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
