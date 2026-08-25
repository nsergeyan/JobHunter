"""Hard, rule-based filters applied before ranking -- not learned by the model.

Postings that require proficiency in a language other than English are excluded
regardless of how well the role otherwise matches. This is a small,
explicit list rather than an exhaustive one: it only catches postings where the
LLM extraction step already pulled out a named language, so it won't catch a
posting that's silently non-English or phrases the requirement without naming
a language.

The seniority filter is different in kind: the language filter reflects a hard
constraint (a role you cannot do), while seniority reflects what you feel like
looking at today. It is therefore configurable per run rather than fixed here,
and it applies to the digest's view only, never to what the model trains on.
"""

import re

NON_ENGLISH_LANGUAGES = {
    "dutch", "french", "german", "spanish", "italian", "portuguese",
    "swedish", "norwegian", "danish", "finnish", "polish",
}


KNOWN_SENIORITIES = {"internship", "junior", "mid", "senior", "unknown"}

# Location is free text and differs per ATS platform: "Amsterdam, NL",
# "Veldhoven, Netherlands", "ACT (Amsterdam - Acanthus)", plain "Eindhoven".
# Matching is therefore on TOKENS, never substrings -- a substring test for "nl"
# also matches "Finland", and "best" is both a Dutch city and a common word.
#
# The country tokens catch almost everything; the city list exists for postings
# that name a city with no country (ING's office codes, bare "Eindhoven").
# Cities that are also ordinary English words are deliberately left out.
NETHERLANDS_LOCATION_TERMS = {
    "nl", "netherlands", "nederland", "holland",
    "amsterdam", "rotterdam", "utrecht", "eindhoven", "delft", "groningen",
    "tilburg", "nijmegen", "haarlem", "leiden", "wageningen", "enschede",
    "maastricht", "hilversum", "veldhoven", "schiphol", "breda", "arnhem",
    "apeldoorn", "zwolle", "amersfoort", "almere", "hague", "dordrecht",
    "venlo", "helmond", "alkmaar", "noordwijk", "petten",
}


def _location_tokens(location: str) -> set[str]:
    """Lowercased alphanumeric tokens, so punctuation and layout don't matter."""
    return {token for token in re.split(r"[^a-z0-9]+", location.lower()) if token}


def requires_non_english_language(language_requirement: str | None) -> bool:
    if not isinstance(language_requirement, str) or not language_requirement:
        return False
    tokens = {t.strip().lower() for t in language_requirement.split(",")}
    return bool(tokens & NON_ENGLISH_LANGUAGES)


def matches_seniority(seniority: str | None, include: set[str] | None) -> bool:
    """True if a posting's seniority is one the digest should show.

    `include=None` means "no filter". A missing or unrecognised seniority is
    treated as "unknown", so it is shown only when "unknown" is explicitly in
    the include set -- extraction leaves this field unknown on ~23% of postings,
    and silently discarding them is a real recall cost worth opting into.
    """
    if include is None:
        return True
    value = seniority.strip().lower() if isinstance(seniority, str) and seniority.strip() else "unknown"
    if value not in KNOWN_SENIORITIES:
        value = "unknown"
    return value in include


def matches_location(location: str | None, include: set[str] | None) -> bool:
    """True if a posting's free-text location contains one of the include tokens.

    `include=None` means "no filter". A missing location is excluded when a
    filter is active: unlike seniority, an unknown location can't be judged from
    the field itself, and every posting in the database currently has one.
    """
    if include is None:
        return True
    if not isinstance(location, str) or not location.strip():
        return False
    return bool(_location_tokens(location) & include)
