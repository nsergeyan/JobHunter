package com.jobscout.extraction;

import java.util.List;

/**
 * Instructions and field descriptions shared by every Extractor implementation.
 * Keeping this in one place means Gemini and Ollama (and any future provider) are
 * always extracting against the identical prompt -- if they drifted, a Gemini-vs-
 * Ollama quality comparison would be comparing prompts, not models.
 */
final class ExtractionPrompt {
    private ExtractionPrompt() {
    }

    static final String PREFIX = """
            Extract structured fields from the job posting below. Use only information
            explicitly present in the text -- do not guess or infer values that are not
            stated. Leave a field as an empty string (or 0 for the salary fields) if the
            posting does not mention it. Use "unknown" for seniority/remote_policy if the
            posting is genuinely ambiguous, and an empty list for skills if none are
            mentioned. If a salary is stated as a rate (e.g. "$60-80/hr"), set
            salary_period to "hourly"; if it's an annual figure, set it to "yearly"; if
            no salary is mentioned, set it to "unknown".

            Seniority guidance: check for "internship" FIRST, before anything else --
            if the job title or posting explicitly identifies the role as an
            internship, intern position, or co-op, seniority is always "internship",
            regardless of any experience-range or seniority language elsewhere in the
            text (internship postings often describe the target candidate's academic
            background using words like "graduate" or "PhD", which is not the same as
            the role itself being senior). Otherwise, when the posting states a RANGE
            of years of experience (e.g. "2-12+ years"), classify by the LOW end of
            that range, not the high end -- a wide range means the employer will
            consider candidates at the low end, which is what determines fit here. Use
            "senior" only if the job title itself contains
            Senior/Staff/Principal/Lead/Director, or the experience requirement has no
            low end below 5 years (e.g. "5+ years", not "2-5 years" or "2-12+ years").
            Use "junior" if the stated minimum experience is 0-2 years (e.g. "2 years
            of experience", "0-2 years"), or the posting explicitly says entry-level,
            new grad, or junior. Use "mid" for a stated minimum of roughly 3-4 years,
            or when the posting gives no explicit experience number but also shows no
            internship/junior/senior signal. Don't default everything that isn't
            obviously senior to "mid" -- a flat "2 years" requirement is "junior", not
            "mid".

            Skills guidance: each entry in "skills" must be a single, atomic
            technology/tool/language/framework name (e.g. "Python", "AWS", "Docker") --
            never bundle multiple technologies into one string and never include
            parenthetical examples or explanations inside a skill entry. If the
            posting says "LLM APIs (OpenAI, Anthropic, etc.)", extract "OpenAI" and
            "Anthropic" as separate entries (and "LLM APIs" too, if it reads as its
            own named skill) -- do not output "LLM APIs (OpenAI, Anthropic, etc.)" as
            a single entry.

            Job posting:
            """;

    static final List<String> SENIORITY_VALUES = List.of("internship", "junior", "mid", "senior", "unknown");
    static final List<String> REMOTE_POLICY_VALUES = List.of("remote", "hybrid", "onsite", "unknown");
    static final List<String> SALARY_PERIOD_VALUES = List.of("hourly", "yearly", "unknown");

    static final String SKILLS_DESCRIPTION =
            "Technical skills, tools, languages, or frameworks explicitly mentioned. "
                    + "One atomic item per entry -- never bundle multiple technologies "
                    + "or add parenthetical examples inside a single entry.";
    static final String SENIORITY_DESCRIPTION =
            "Seniority level. If the posting is explicitly an internship/intern/co-op, "
                    + "this is always \"internship\" regardless of other language. "
                    + "Otherwise use the LOW end of any stated experience range: 0-2 "
                    + "years -> \"junior\", ~3-4 years -> \"mid\", no low end below 5 "
                    + "years or title says Senior/Staff/Principal/Lead -> \"senior\". "
                    + "Don't default to \"mid\" just because a posting isn't obviously "
                    + "senior.";
    static final String SALARY_MIN_DESCRIPTION = "Minimum stated salary, 0 if not mentioned.";
    static final String SALARY_MAX_DESCRIPTION = "Maximum stated salary, 0 if not mentioned.";
    static final String SALARY_CURRENCY_DESCRIPTION = "ISO currency code (e.g. USD, EUR), empty if not mentioned.";
    static final String SALARY_PERIOD_DESCRIPTION =
            "Whether salary_min/salary_max are an hourly rate or an annual figure.";
    static final String LANGUAGE_REQUIREMENT_DESCRIPTION =
            "Required spoken/written language(s), empty if not mentioned.";
    static final String REMOTE_POLICY_DESCRIPTION = "Remote-work policy stated or implied by the posting.";
}
