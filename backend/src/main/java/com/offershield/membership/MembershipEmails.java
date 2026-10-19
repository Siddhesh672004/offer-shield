package com.offershield.membership;

import com.offershield.notification.EmailRequested;

/** Plain-text copy for decision emails (D-33.7). Never says "verified", "safe", "legit" or "guaranteed". */
final class MembershipEmails {

    static final String SUBJECT = "OfferShield: update on your college request";

    private MembershipEmails() {
    }

    static EmailRequested decided(String to, String collegeName, MembershipStatus outcome, String reason) {
        String line = outcome == MembershipStatus.APPROVED
                ? "Your request to join " + collegeName + " was approved."
                : "Your request to join " + collegeName + " was not approved. Reason: " + reason;
        String body = "Hello,\n\n" + line + "\n\nSign in to OfferShield to see your request.\n\n- OfferShield\n";
        return new EmailRequested(to, SUBJECT, body);
    }
}
