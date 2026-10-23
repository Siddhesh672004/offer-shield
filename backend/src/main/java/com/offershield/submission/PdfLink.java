package com.offershield.submission;

import java.time.Instant;

/**
 * A short-lived signed download link to a submission's private PDF (D-05, M-07: 5 minutes), returned only to the TPO
 * of the case's college or to the admin for a reported submission. The URL is a credential for the file until
 * {@code expiresAt}, so {@link #toString()} never shows it and it is never logged (D-27).
 */
public record PdfLink(String url, Instant expiresAt) {

    @Override
    public String toString() {
        return "PdfLink[url=redacted, expiresAt=" + expiresAt + "]";
    }
}
