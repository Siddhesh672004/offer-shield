package com.offershield.submission;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.multipart.MultipartFile;

/**
 * The multipart form of {@code POST /api/submissions}: a PDF {@code file} or pasted {@code text} (D-33.1: at most
 * 20,000 characters), plus optional details. Blank fields become {@code null} before validation; violations are
 * reported by field name only, never with the submitted value.
 */
public record SubmissionForm(
        MultipartFile file,
        @Size(max = 20_000, message = "must be at most 20,000 characters") String text,
        @Email(message = "must be an email address") @Size(max = 254) String senderEmail,
        @Size(max = 32) @Pattern(regexp = "\\+?[0-9][0-9 ()-]{5,30}", message = "must be a phone number")
        String recruiterPhone,
        @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]{2,64}@[A-Za-z][A-Za-z0-9.-]{1,34}",
                message = "must be a UPI ID like name@bank")
        String upiId) {

    public SubmissionForm {
        if (file != null && file.isEmpty() && (file.getOriginalFilename() == null || file.getOriginalFilename().isEmpty())) {
            file = null; // a browser sends an empty part when no file was chosen
        }
        text = text == null || text.isBlank() ? null : text;
        senderEmail = trimToNull(senderEmail);
        recruiterPhone = trimToNull(recruiterPhone);
        upiId = trimToNull(upiId);
    }

    NewSubmission toCommand(String idempotencyKey) {
        return new NewSubmission(idempotencyKey, file, text, senderEmail, recruiterPhone, upiId);
    }

    @Override
    public String toString() {
        return "SubmissionForm[file=" + (file != null) + ", text=" + (text != null) + "]";
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
