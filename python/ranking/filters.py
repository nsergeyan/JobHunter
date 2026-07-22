"""Hard, rule-based filters applied before ranking -- not learned by the model.

Narek doesn't want postings that require proficiency in a language other than
English, regardless of how well the role otherwise matches. This is a small,
explicit list rather than an exhaustive one: it only catches postings where the
LLM extraction step already pulled out a named language, so it won't catch a
posting that's silently non-English or phrases the requirement without naming
a language.
"""

NON_ENGLISH_LANGUAGES = {
    "dutch", "french", "german", "spanish", "italian", "portuguese",
    "swedish", "norwegian", "danish", "finnish", "polish",
}


def requires_non_english_language(language_requirement: str | None) -> bool:
    if not isinstance(language_requirement, str) or not language_requirement:
        return False
    tokens = {t.strip().lower() for t in language_requirement.split(",")}
    return bool(tokens & NON_ENGLISH_LANGUAGES)
