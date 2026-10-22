package com.offershield.common;

/**
 * Where a submission's sender domain came from (D-33.6; P13, security review S2): {@link #TYPED} = the sender email
 * the student typed next to the letter, {@link #LETTER} = the first email address written in the letter itself. A
 * LETTER-sourced domain is chosen by whoever wrote the letter, so it never vouches for the letter: C3 never passes on it,
 * the C11/C12 copied-letter guard applies only when every email domain in the letter is official (and there is no
 * messenger contact), and it becomes a scam marker only when the letter has exactly one email domain.
 */
public enum SenderSource {
    TYPED,
    LETTER;

    /**
     * The source of a sender domain picked by D-33.6: {@code null} without a domain, TYPED when the student typed a
     * sender email, else LETTER.
     */
    public static SenderSource of(String typedSenderEmail, String senderDomain) {
        if (senderDomain == null) {
            return null;
        }
        return typedSenderEmail != null ? TYPED : LETTER;
    }
}
