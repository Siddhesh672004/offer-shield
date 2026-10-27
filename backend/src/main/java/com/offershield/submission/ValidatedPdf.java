package com.offershield.submission;

/** A PDF that passed every upload check: its bytes (for private storage), its text layer and its page count. */
public record ValidatedPdf(byte[] bytes, String text, int pages) {

    @Override
    public String toString() {
        return "ValidatedPdf[size=" + bytes.length + ", pages=" + pages + ", textLength=" + text.length() + "]";
    }
}
