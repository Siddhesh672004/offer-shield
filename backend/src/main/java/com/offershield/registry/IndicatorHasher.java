package com.offershield.registry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Turns phone numbers and UPI IDs into registry keys (PRD §10.1, §14.6; D-18): HMAC-SHA256 with the server's secret
 * pepper ({@code INDICATOR_HMAC_PEPPER}), as lower-case hex. A plain SHA-256 of an Indian mobile number could be
 * reversed by trying all ~10^10 numbers; without the pepper the HMAC cannot. The raw values are never stored, logged or
 * sent anywhere: only these keys leave the analysis (in the report, {@code analysis.completed} and the snapshot).
 *
 * <p>Before hashing, a phone number is normalised so every way of writing it gives the same key: Indian numbers become
 * {@code +91} and 10 digits ({@code 099999 01234}, {@code 0091 99999-01234} and {@code 9999901234} are all
 * {@code +919999901234}); other numbers written with {@code +} or {@code 00} keep their country code. A UPI ID is
 * trimmed and lower-cased. Phone and UPI keys are hashed in separate namespaces.
 *
 * <p>A missing or short pepper stops startup with a message that never contains it.
 */
@Component
public class IndicatorHasher {

    public static final int MIN_PEPPER_LENGTH = 32;
    private static final String ALGORITHM = "HmacSHA256";
    private static final int MIN_DIGITS = 8;
    private static final int MAX_DIGITS = 15;
    private static final Pattern UPI = Pattern.compile("[a-z0-9._-]{2,64}@[a-z][a-z0-9]{1,34}");
    private static final Pattern NON_DIGIT = Pattern.compile("\\D");
    private static final String HIDDEN = "••••";

    private final SecretKeySpec key;

    public IndicatorHasher(@Value("${offershield.registry.hmac-pepper:}") String pepper) {
        if (pepper == null || pepper.isBlank()) {
            throw new IllegalStateException("INDICATOR_HMAC_PEPPER is not set. Generate one with "
                    + "'openssl rand -base64 48' and put it in .env.");
        }
        byte[] bytes = pepper.strip().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_PEPPER_LENGTH) {
            throw new IllegalStateException("INDICATOR_HMAC_PEPPER is too short: use at least " + MIN_PEPPER_LENGTH
                    + " characters (openssl rand -base64 48).");
        }
        this.key = new SecretKeySpec(bytes, ALGORITHM);
    }

    /** The registry key of a phone number, or empty when {@code raw} is not a phone number (8–15 digits). */
    public Optional<String> phone(String raw) {
        return normalisePhone(raw).map(value -> hmac("phone:" + value));
    }

    /** The registry key of a UPI ID ({@code name@bank}), or empty when {@code raw} is not one. */
    public Optional<String> upi(String raw) {
        return normaliseUpi(raw).map(value -> hmac("upi:" + value));
    }

    /**
     * P13 (N2): what a TPO or the admin may see of a phone number: "••••" and its last 4 digits (after the same
     * normalisation as the key), or empty when {@code raw} is not a phone number. Never the full number.
     */
    public Optional<String> phoneHint(String raw) {
        return normalisePhone(raw).map(value -> HIDDEN + value.substring(value.length() - 4));
    }

    /**
     * P13 (N2): what a TPO or the admin may see of a UPI ID: "••••", the last 2 characters of the handle (none when the
     * handle is shorter than 4) and "@provider", e.g. "••••ar@okaxis"; empty when {@code raw} is not a UPI ID.
     */
    public Optional<String> upiHint(String raw) {
        return normaliseUpi(raw).map(value -> {
            int at = value.lastIndexOf('@');
            String handle = value.substring(0, at);
            return HIDDEN + (handle.length() >= 4 ? handle.substring(handle.length() - 2) : "") + value.substring(at);
        });
    }

    /**
     * {@code +91} and 10 digits for Indian numbers (10 digits; 11 with a leading 0; 12 starting 91; or written with
     * +91 / 0091), {@code +} and the digits for other numbers written with {@code +} or {@code 00}, else the digits.
     */
    static Optional<String> normalisePhone(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String value = raw.strip();
        int start = 0;
        while (start < value.length() && value.charAt(start) != '+' && !Character.isDigit(value.charAt(start))) {
            start++; // "(+91) …"
        }
        value = value.substring(start);
        String digits = NON_DIGIT.matcher(value).replaceAll("");
        boolean international = value.startsWith("+");
        if (!international && digits.startsWith("00")) {
            international = true;
            digits = digits.substring(2);
        }
        if (digits.length() < MIN_DIGITS || digits.length() > MAX_DIGITS) {
            return Optional.empty();
        }
        if (international) {
            return Optional.of("+" + digits);
        }
        if (digits.length() == 10) {
            return Optional.of("+91" + digits);
        }
        if (digits.length() == 11 && digits.charAt(0) == '0') {
            return Optional.of("+91" + digits.substring(1));
        }
        if (digits.length() == 12 && digits.startsWith("91")) {
            return Optional.of("+" + digits);
        }
        return Optional.of(digits);
    }

    static Optional<String> normaliseUpi(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String value = raw.strip().toLowerCase(Locale.ROOT);
        return UPI.matcher(value).matches() ? Optional.of(value) : Optional.empty();
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 failed: " + ex.getClass().getSimpleName());
        }
    }

    @Override
    public String toString() {
        return "IndicatorHasher[HMAC-SHA256, pepper hidden]";
    }
}
