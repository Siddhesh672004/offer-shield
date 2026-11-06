package com.offershield.ai;

import java.util.List;

/**
 * An AI extraction the analysis may use (PRD §7, §8.1): every value was found in the letter the model saw. Phone
 * numbers and UPI IDs are the real values (placeholders mapped back locally); payment mentions stay masked (they become
 * report evidence); text fields never contain a placeholder. {@code model} is the model that answered, {@code null}
 * when a cached answer was reused ({@code fromCache}). {@link #toString()} never shows the values.
 */
public record Extraction(String companyName, String recruiterName, String ctc, String joiningDate,
                         List<String> phoneNumbers, List<String> upiIds, List<String> paymentMentions,
                         String model, boolean fromCache) {

    public Extraction {
        phoneNumbers = phoneNumbers == null ? List.of() : List.copyOf(phoneNumbers);
        upiIds = upiIds == null ? List.of() : List.copyOf(upiIds);
        paymentMentions = paymentMentions == null ? List.of() : List.copyOf(paymentMentions);
    }

    @Override
    public String toString() {
        return "Extraction[model=" + model + ", fromCache=" + fromCache + ", redacted]";
    }
}
