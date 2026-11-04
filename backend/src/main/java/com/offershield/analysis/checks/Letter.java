package com.offershield.analysis.checks;

import com.offershield.analysis.lookup.ValidatedDomain;
import com.offershield.common.SenderSource;

/**
 * What the checks see of one submission: the decrypted letter text and the optional typed details.
 * {@code senderDomain} is the raw D-33.6 value (typed sender email, else the first email in the letter);
 * {@code domain} is the same domain after the SSRF guard ({@code DomainValidator}), or {@code null} when it is missing
 * or not a public domain name: only then may C5/C6 make network lookups. {@code ai} holds the AI extraction's
 * candidates ({@link AiFindings#NONE} without AI); {@code registry} holds the scam registry's answer for C10–C12
 * ({@link RegistryFindings#NOTHING_TO_COMPARE} until it was asked). {@code senderSource} (P13, S2) says whether the
 * student typed the sender email or it was taken from the letter itself ({@code null} without a sender domain).
 * {@link #toString()} never shows content.
 */
public record Letter(String text, String senderEmail, String senderDomain, ValidatedDomain domain,
                     String recruiterPhone, String upiId, AiFindings ai, RegistryFindings registry,
                     SenderSource senderSource) {

    public Letter {
        text = text == null ? "" : text;
        ai = ai == null ? AiFindings.NONE : ai;
        registry = registry == null ? RegistryFindings.NOTHING_TO_COMPARE : registry;
        senderSource = senderDomain == null ? null
                : senderSource != null ? senderSource : SenderSource.of(senderEmail, senderDomain);
    }

    /** A letter whose sender source follows from the typed sender email (TYPED when there is one, else LETTER). */
    public Letter(String text, String senderEmail, String senderDomain, ValidatedDomain domain, String recruiterPhone,
                  String upiId, AiFindings ai, RegistryFindings registry) {
        this(text, senderEmail, senderDomain, domain, recruiterPhone, upiId, ai, registry, null);
    }

    /** A letter without AI findings or a registry answer. */
    public Letter(String text, String senderEmail, String senderDomain, ValidatedDomain domain, String recruiterPhone,
                  String upiId) {
        this(text, senderEmail, senderDomain, domain, recruiterPhone, upiId, AiFindings.NONE,
                RegistryFindings.NOTHING_TO_COMPARE, null);
    }

    public Letter withAi(AiFindings findings) {
        return new Letter(text, senderEmail, senderDomain, domain, recruiterPhone, upiId, findings, registry,
                senderSource);
    }

    public Letter withRegistry(RegistryFindings findings) {
        return new Letter(text, senderEmail, senderDomain, domain, recruiterPhone, upiId, ai, findings, senderSource);
    }

    /** True when the sender address was taken from the letter itself, not typed by the student (P13, S2). */
    public boolean senderFromLetter() {
        return senderSource == SenderSource.LETTER;
    }

    @Override
    public String toString() {
        return "Letter[redacted]";
    }
}
