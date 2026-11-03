package com.offershield.analysis.checks;

import java.util.List;

/**
 * What the AI extraction adds to a {@link Letter} as <b>candidates</b> for the deterministic checks (PRD §7, R-A).
 * Every value was found in the letter; the checks decide what it means. AI can only add risk this way, never remove
 * or lower a finding.
 * <ul>
 *   <li>{@code paymentMentions}: sentences the AI says ask for money (masked). C1 runs its own rule on them, D-09
 *       included; a hit is labelled "AI-extracted".</li>
 *   <li>{@code companyName}: the employer the letter claims. C3 ({@link CompanyDomainMismatchCheck}, P8) uses it only
 *       when no known company is named in the text, and only to add risk: a mismatch is TRIGGERED ("AI-extracted"),
 *       a match is never PASSED. <b>R-A:</b> it is never what lets the C11 copied-letter guard
 *       ({@link CopiedLetterGuard}, P9) downgrade High to +20; that needs a company identified by the rules (the
 *       known company's name in the text and the sender on its official domain); the guard never reads this field.
 *       </li>
 *   <li>{@code phoneNumbers}, {@code upiIds}: real values, hashed at once into registry keys for C10/C12
 *       ({@code RegistryLookup}, P9); a match resting only on them is labelled "AI-extracted".</li>
 * </ul>
 * {@link #toString()} never shows the values.
 */
public record AiFindings(List<String> paymentMentions, String companyName, List<String> phoneNumbers,
                         List<String> upiIds) {

    public static final AiFindings NONE = new AiFindings(List.of(), null, List.of(), List.of());

    public AiFindings {
        paymentMentions = paymentMentions == null ? List.of() : List.copyOf(paymentMentions);
        phoneNumbers = phoneNumbers == null ? List.of() : List.copyOf(phoneNumbers);
        upiIds = upiIds == null ? List.of() : List.copyOf(upiIds);
    }

    @Override
    public String toString() {
        return "AiFindings[redacted]";
    }
}
