package com.offershield.analysis.lookup;

/** What DNS said about a domain's mail setup (C6), or {@code available = false} when the lookup failed. */
public record MailRecords(boolean available, boolean hasMx, boolean hasDmarc) {

    public static MailRecords found(boolean hasMx, boolean hasDmarc) {
        return new MailRecords(true, hasMx, hasDmarc);
    }

    public static MailRecords unavailable() {
        return new MailRecords(false, false, false);
    }
}
