package com.offershield.submission;

/** The optional details typed next to a letter; stored only encrypted (as JSON) in {@code contacts_cipher}. */
public record SubmissionContacts(String senderEmail, String recruiterPhone, String upiId) {

    boolean isEmpty() {
        return senderEmail == null && recruiterPhone == null && upiId == null;
    }

    @Override
    public String toString() {
        return "SubmissionContacts[redacted]";
    }
}
