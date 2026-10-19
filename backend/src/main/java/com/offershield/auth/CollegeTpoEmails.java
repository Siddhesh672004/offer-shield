package com.offershield.auth;

import java.util.List;

/** For the admin college list: current TPOs of a college, and emails still waiting for their first verified sign-in. */
public record CollegeTpoEmails(List<String> tpoEmails, List<String> pendingTpoEmails) {

    public static final CollegeTpoEmails NONE = new CollegeTpoEmails(List.of(), List.of());

    public CollegeTpoEmails {
        tpoEmails = List.copyOf(tpoEmails);
        pendingTpoEmails = List.copyOf(pendingTpoEmails);
    }
}
