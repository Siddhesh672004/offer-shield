package com.offershield.analysis;

/**
 * Facts the AI extraction read from the letter, shown in the report labelled "AI-extracted" (PRD §7, §8.1). Each is
 * text found in the letter, at most {@value #MAX_LENGTH} characters, never a placeholder and never wording the product
 * may not show ("verified", "safe", "legit", "guaranteed"). They never affect the score.
 */
public record AiDetails(String companyName, String recruiterName, String ctc, String joiningDate) {

    public static final int MAX_LENGTH = 100;
    public static final AiDetails NONE = new AiDetails(null, null, null, null);

    public boolean isEmpty() {
        return companyName == null && recruiterName == null && ctc == null && joiningDate == null;
    }

    @Override
    public String toString() {
        return "AiDetails[redacted]";
    }
}
