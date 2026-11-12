package com.offershield.registry;

import java.util.List;

/**
 * What one letter offers for matching (PRD §7.1 C10–C12): the sender's registrable domain (or {@code null}), the HMAC
 * keys of its phone numbers and UPI IDs ({@link IndicatorHasher}) and its SimHash (or {@code null}). Never raw phone
 * numbers or UPI IDs. {@link #toString()} shows only how many keys there are.
 */
public record MarkerQuery(String domain, List<String> phoneHmacs, List<String> upiHmacs, Long simhash) {

    public MarkerQuery {
        phoneHmacs = phoneHmacs == null ? List.of() : List.copyOf(phoneHmacs);
        upiHmacs = upiHmacs == null ? List.of() : List.copyOf(upiHmacs);
    }

    /** True when there is nothing to compare. */
    public boolean isEmpty() {
        return domain == null && phoneHmacs.isEmpty() && upiHmacs.isEmpty() && simhash == null;
    }

    @Override
    public String toString() {
        return "MarkerQuery[domain=" + (domain != null) + ", phones=" + phoneHmacs.size() + ", upiIds=" + upiHmacs.size()
                + ", simhash=" + (simhash != null) + "]";
    }
}
