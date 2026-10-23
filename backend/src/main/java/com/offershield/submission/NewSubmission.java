package com.offershield.submission;

import org.springframework.web.multipart.MultipartFile;

/**
 * An upload request after bean validation: exactly one of {@code file} and {@code text} is expected (the service
 * checks), and blank optional fields are {@code null}. {@link #toString()} never shows the content.
 */
public record NewSubmission(String idempotencyKey, MultipartFile file, String text, String senderEmail,
                            String recruiterPhone, String upiId) {

    @Override
    public String toString() {
        return "NewSubmission[file=" + (file != null) + ", text=" + (text != null) + "]";
    }
}
