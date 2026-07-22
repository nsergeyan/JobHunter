"""Narek's stated job preferences, as a single source of truth shared by the
LLM-as-judge prompt and the cosine-similarity query. Written once from the
patterns his 0/1/2 labels showed across 201 postings -- not iteratively tuned
against those labels, since doing so would leak the test set into the prompt.
"""

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
