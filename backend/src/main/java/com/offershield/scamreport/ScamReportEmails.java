package com.offershield.scamreport;

import com.offershield.notification.EmailRequested;

/**
 * Plain-text copy for the reporter's email after the admin's decision (D-33.7, PRD §4 F9). Never says "verified",
 * "safe", "legit" or "guaranteed", and never includes the letter, the description or any contact detail. P13 (silent
 * failure 7): a confirmation claims that matching letters are now flagged only when it really confirmed scam markers.
 */
final class ScamReportEmails {

    static final String SUBJECT = "OfferShield: update on your scam report";
    static final String NO_MARKERS = "No scam markers could be recorded for this letter (it has no details the shared "
            + "registry can use), so other letters are not flagged because of it.";

    private ScamReportEmails() {
    }

    static EmailRequested decided(String to, ScamReportStatus outcome, int markersRecorded) {
        String line;
        if (outcome == ScamReportStatus.CONFIRMED) {
            line = "An OfferShield admin reviewed your scam report and confirmed it. " + (markersRecorded > 0
                    ? "Letters that share its sender domain, phone numbers, UPI IDs or wording are now flagged as "
                    + "\"Confirmed scam\" for everyone."
                    : NO_MARKERS) + " Thank you for warning other students.";
        } else {
            line = "An OfferShield admin reviewed your scam report and could not confirm it, so it was closed and no "
                    + "longer counts in \"Reported as a scam\" warnings. This does not mean the offer is genuine: never "
                    + "pay money to get a job.";
        }
        String body = "Hello,\n\n" + line + "\n\nSign in to OfferShield to see your reports.\n\n- OfferShield\n";
        return new EmailRequested(to, SUBJECT, body);
    }
}
