"""LLM-as-judge: asks the same local Ollama model used for extraction to score
each posting directly against the user's preference profile, using the same
/api/chat + JSON-schema "format" trick as the Java OllamaExtractor. This is
"instructed judgment" rather than learned from labels -- the 0/1/2 labels
are never shown to the model, only the fixed preference profile.

Scores 0-100 rather than a discrete 0/1/2: a 3-way rating gave the model only
3 possible answers, so most of the 195 postings tied on the same value and
"top k" beyond that point was decided by arbitrary tie-breaking, not real
judgment (confirmed via ranking/benchmark.py's rating-distribution diagnostic).
A wide numeric scale gives it room to actually rank postings against each other.
"""

import json
import os
import urllib.request

import numpy as np
import pandas as pd

from ranking.preferences import PREFERENCE_PROFILE

OLLAMA_BASE_URL = os.environ.get("OLLAMA_BASE_URL", "http://localhost:11434")
OLLAMA_MODEL = os.environ.get("OLLAMA_MODEL", "qwen3:8b")
MAX_POSTING_CHARS = 6000
REQUEST_TIMEOUT_SECONDS = 180

SCORE_SCHEMA = {
    "type": "object",
    "properties": {
        "fit_score": {
            "type": "integer",
            "minimum": 0,
            "maximum": 100,
            "description": (
                "How well this posting fits the candidate's preference profile, "
                "0 = not a fit at all, 100 = perfect fit. Use the full range -- "
                "don't cluster everything around the same number."
            ),
        }
    },
    "required": ["fit_score"],
}


def build_prompt(raw_text: str) -> str:
    return (
        f"Here is a candidate's job preference profile:\n{PREFERENCE_PROFILE}\n\n"
        f"Here is a job posting:\n{raw_text[:MAX_POSTING_CHARS]}\n\n"
        "Score how well this posting fits the candidate's preference profile, "
        "from 0 (not a fit at all) to 100 (perfect fit)."
    )


def judge_vacancy(raw_text: str) -> int:
    body = json.dumps({
        "model": OLLAMA_MODEL,
        "messages": [{"role": "user", "content": build_prompt(raw_text)}],
        "stream": False,
        "format": SCORE_SCHEMA,
        "think": False,  # qwen3's chain-of-thought reasoning turns a 0.4s call into 30-50s
    }).encode("utf-8")             # for no real benefit on a simple scoring task

    request = urllib.request.Request(
        f"{OLLAMA_BASE_URL}/api/chat", data=body, headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
        payload = json.loads(response.read())

    fields = json.loads(payload["message"]["content"])
    return int(fields["fit_score"])


def judge_all(df: pd.DataFrame) -> np.ndarray:
    scores = np.zeros(len(df), dtype=int)
    for i, raw_text in enumerate(df["raw_text"]):
        scores[i] = judge_vacancy(raw_text or "")
        print(f"  judged {i + 1}/{len(df)}", end="\r")
    print()
    return scores
