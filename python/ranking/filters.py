"""Hard, rule-based filters applied before ranking -- not learned by the model.

Language is handled by two checks that are deliberately DIFFERENT KINDS of filter,
and confusing them would be a mistake:

1. HARD constraint: the posting NAMES a non-English language it requires. A role
   demanding fluent Dutch is one you cannot take, so it is dropped unconditionally,
   from training and from the digest alike.

2. VIEW filter: the posting is WRITTEN in another language. Check 1 misses a
   posting written entirely in German that never says so, which is common, since
   the requirement is obvious to whoever wrote it. But an ad in German often
   describes a role whose working language is English, which is routine at Bosch
   research and certain at Palantir. The labels bear this out: of the postings this
   check catches, 3 were rated "yes" and 8 "maybe". Treating it as a hard drop
   would contradict those ratings, so it only hides postings from the digest and
   the model still trains on every one of them.

The ranking model had spotted the gap before either check existed: with description
features on, it learned German function words as a signal for "no", a real
preference being expressed through a proxy instead of a rule.

The seniority filter is different in kind: the language filter reflects a hard
constraint (a role you cannot do), while seniority reflects what you feel like
looking at today. It is therefore configurable per run rather than fixed here,
and it applies to the digest's view only, never to what the model trains on.
"""

import re

import pandas as pd

NON_ENGLISH_LANGUAGES = {
    "dutch", "french", "german", "spanish", "italian", "portuguese",
    "swedish", "norwegian", "danish", "finnish", "polish",
}


KNOWN_SENIORITIES = {"internship", "junior", "mid", "senior", "unknown"}

# Function words used to detect the language a posting is WRITTEN in. Function
# words are the right signal because they are unavoidable in running prose and are
# not borrowed the way technical nouns are: "Kubernetes" appears in every language,
# "und" does not.
#
# Two rules for membership, both learned the hard way against real postings. A
# marker must not be an English word ("door", "come", "plus", "tot" were all
# rejected for this), and it must not be a fragment English tokenises into: "per"
# matched "per year" and "e" matched "e.g.", between them flagging a dozen English
# postings as Italian.
NON_ENGLISH_MARKERS = {
    "german": {"und", "für", "mit", "wir", "ist", "eine", "einen", "einem", "oder", "nicht",
               "auch", "werden", "haben", "sind", "unser", "unsere", "deine", "sowie", "zum",
               "zur", "des", "dem", "den", "das", "ihre", "ihren", "aus", "durch", "über",
               "bei", "als", "wird", "dich", "du", "sich", "nach", "vor", "beim", "dass",
               "kannst", "unseren", "einer"},
    "dutch": {"een", "het", "van", "voor", "wij", "onze", "jij", "jouw", "niet", "ook",
              "worden", "hebben", "zijn", "bij", "met", "aan", "naar", "je", "ze", "dat",
              "deze", "wat", "waar", "wordt", "kun", "kunt", "binnen", "samen", "de", "te",
              "om", "op", "zich", "ons", "uit", "maar", "meer", "wil", "kan", "heeft", "hun",
              "waarbij", "waarin", "zoals", "nog", "alle", "andere", "werk", "ervaring",
              "jaar", "goed", "onder", "tussen"},
    "french": {"pour", "les", "des", "une", "vous", "nous", "avec", "dans", "sur", "est",
               "sont", "notre", "votre", "aux", "leur", "cette", "ces", "chez", "ainsi",
               "être", "qui", "que", "ils", "nos", "vos", "sera", "aussi", "tous"},
    "spanish": {"para", "los", "las", "una", "que", "del", "por", "como", "más", "este",
                "esta", "nuestro", "nuestra", "sus", "el", "se", "su", "muy", "también",
                "tiene", "puede"},
    "italian": {"del", "della", "una", "che", "nel", "alla", "sono", "nostro", "questa",
                "il", "di", "gli", "dei", "delle", "anche", "sia"},
}

# ISO 639-1 codes for display. Slicing the name would give "ge" for german and
# "du" for dutch, which are not codes anyone recognises.
LANGUAGE_CODES = {
    "german": "de",
    "dutch": "nl",
    "french": "fr",
    "spanish": "es",
    "italian": "it",
}

# Share of tokens that must be one language's function words before a posting counts
# as written in it. Calibrated against all 665 scraped postings, where the split is
# clean: everything at or above 11.3% was genuinely foreign (German Bosch listings,
# French Palantir, Italian Doctolib), and the highest scoring English posting sat at
# 9.0%. That one is a bilingual Deutsche Bank listing whose text literally begins
# "*English version below*", so keeping it is the right call, not a near miss.
NON_ENGLISH_MARKER_RATIO = 0.10

# Below this a ratio is too jumpy to trust: a 20-token stub of boilerplate can hit
# any threshold by accident.
MIN_TOKENS_FOR_DETECTION = 30

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
    """Check 1: the posting NAMES a non-English language it requires."""
    if not isinstance(language_requirement, str) or not language_requirement:
        return False
    tokens = {t.strip().lower() for t in language_requirement.split(",")}
    return bool(tokens & NON_ENGLISH_LANGUAGES)


def detect_written_language(raw_text: str | None) -> tuple[str | None, float]:
    """The most likely non-English language of a posting's text, and how strongly.

    Returns the best-matching language and the share of tokens that are its function
    words. A short or missing text returns (None, 0.0) rather than guessing.
    """
    if not isinstance(raw_text, str) or not raw_text:
        return None, 0.0
    tokens = [t for t in re.split(r"[^a-zà-ÿ0-9]+", raw_text.lower()) if t]
    if len(tokens) < MIN_TOKENS_FOR_DETECTION:
        return None, 0.0

    best_language, best_hits = None, 0
    for language, markers in NON_ENGLISH_MARKERS.items():
        hits = sum(1 for token in tokens if token in markers)
        if hits > best_hits:
            best_language, best_hits = language, hits
    return best_language, best_hits / len(tokens)


def is_written_in_non_english(raw_text: str | None) -> bool:
    """Check 2: the posting is WRITTEN in a language other than English.

    Deliberately lenient. A posting that mixes languages, most often one carrying
    both a German and an English version, stays below the threshold and is kept,
    since an English version you can actually read is there.
    """
    _, ratio = detect_written_language(raw_text)
    return ratio >= NON_ENGLISH_MARKER_RATIO


def drop_language_blocked(df: pd.DataFrame) -> pd.DataFrame:
    """HARD filter, applied everywhere: drop postings that NAME a non-English
    language requirement.

    Note what this does NOT do. A posting merely written in German survives here and
    stays in the training set, because the labels say some of those are wanted. The
    digest hides them separately via matches_language_view.
    """
    if df.empty:
        return df
    keep = ~df["language_requirement"].apply(requires_non_english_language)
    return df[keep].reset_index(drop=True)


def matches_language_view(raw_text: str | None, hide_non_english: bool) -> bool:
    """VIEW filter: should the digest show this posting, given its written language?

    `hide_non_english=False` means show everything. Like the seniority and location
    views, this only narrows what is DISPLAYED: scores are computed per posting, so
    it never reorders the ranking.
    """
    if not hide_non_english:
        return True
    return not is_written_in_non_english(raw_text)


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
