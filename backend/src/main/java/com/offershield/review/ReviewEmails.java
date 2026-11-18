package com.offershield.review;

import com.offershield.notification.EmailRequested;

/**
 * Plain-text copy for the student's email after a TPO's review decision (D-33.7, PRD §4 F5, F9). Our own text never
 * says "verified", "safe", "legit" or "guaranteed"; the TPO's note is quoted as theirs. P13 (silent failure 7): a scam
 * confirmation claims that matching letters are now flagged only when it really recorded scam markers.
 */
final class ReviewEmails {

    static final String SUBJECT = "OfferShield: your TPO reviewed your offer letter";
    static final String NO_MARKERS = "No scam markers could be recorded for this letter (it has no details the shared "
            + "registry can use), so other letters are not flagged because of it.";

    private ReviewEmails() {
    }

    static EmailRequested decided(String to, ReviewStatus outcome, String note, int markersRecorded) {
        String line = switch (outcome) {
            case CONFIRMED_SCAM -> "Your TPO reviewed the offer letter you shared and marked it as a scam. Do not pay "
                    + "anything or share your documents. " + (markersRecorded > 0
                    ? "Letters that share its details are now flagged as \"Confirmed scam\" for everyone."
                    : NO_MARKERS);
            case CONFIRMED_GENUINE -> "Your TPO reviewed the offer letter you shared and marked it as genuine. Still, "
                    + "never pay money to get a job, and check any change of details with the company directly.";
            case INCONCLUSIVE -> "Your TPO reviewed the offer letter you shared but could not reach a conclusion. "
                    + "Check the offer through the company's official website or careers page before you act.";
            case OPEN -> throw new IllegalArgumentException("An open case has no decision to email");
        };
        String noteLine = note == null || note.isBlank() ? "" : "\n\nNote from your TPO: " + note;
        String body = "Hello,\n\n" + line + noteLine + "\n\nSign in to OfferShield to see the review.\n\n- OfferShield\n";
        return new EmailRequested(to, SUBJECT, body);
    }
}
