"""The user's stated job preferences, as a single source of truth shared by the
LLM-as-judge prompt and the cosine-similarity query. Written once from the
patterns the 0/1/2 labels showed across 201 postings -- not iteratively tuned
against those labels, since doing so would leak the test set into the prompt.
"""

from ranking.filters import NETHERLANDS_LOCATION_TERMS

PREFERENCE_PROFILE = (
    "Looking for software engineering / AI-ML roles in Europe, junior to mid-level "
    "(internships and entry-level welcome). Strong interest in machine learning and AI: "
    "reinforcement learning, retrieval-augmented generation (RAG), agentic AI, LLM APIs, "
    "PyTorch, TensorFlow, foundation models. Also genuinely interested in plain backend "
    "engineering roles, independent of ML content. Not interested in generic DevOps/cloud "
    "infrastructure work, embedded systems, plain full-stack CRUD development, generic data "
    "engineering/ETL pipelines, academic thesis-style research postings unrelated to core ML, "
    "frontend development, or mobile development. Must not require proficiency in a language "
    "other than English."
)

# Seniority levels the daily digest is allowed to show. This is a VIEW filter,
# not a modeling choice: the ranking model still trains on every labeled posting
# regardless of seniority (more data is strictly better), and scores are computed
# per posting independently, so narrowing this changes which postings you see,
# never the order they come in.
#
# Set to None to show everything, ranked. Override for a single run with
# `--seniority a,b` or `--all-seniority`.
#
# Tradeoff worth knowing before you narrow it: of 66 postings labeled "yes" so
# far, only 24 were tagged internship. The rest were mid (16), junior (12) and
# unknown (11) -- the extractor calls a role "mid" off a phrase like "2+ years"
# that you may well judge applicable anyway. Narrowing trades recall for focus.
SENIORITY_INCLUDE: set[str] | None = {"internship"}

# Locations the digest is allowed to show, matched as tokens against the
# posting's free-text location. Same deal as SENIORITY_INCLUDE: a view filter,
# not a modeling choice. None shows everywhere, ranked.
#
# Set to the Netherlands, where the user lives. Note that this is stricter than
# the scraper's scope, which is Europe-wide, so it hides roles that are a strong
# fit but would need relocating -- including London postings the model has rated
# highest. Override for one run with `--location germany,berlin` or
# `--all-locations`.
LOCATION_INCLUDE: set[str] | None = NETHERLANDS_LOCATION_TERMS
