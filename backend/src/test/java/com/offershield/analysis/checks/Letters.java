package com.offershield.analysis.checks;

import com.offershield.analysis.lookup.DomainValidator;
import com.offershield.analysis.lookup.ValidatedDomain;
import com.offershield.common.SenderSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fake letters (05 §4) from {@code src/test/resources/fixtures/letters/} and quick {@link Letter} builders. */
public final class Letters {

    public static final String GENUINE = "genuine-offer.txt";
    public static final String DEPOSIT_SCAM = "deposit-scam.txt";
    public static final String HINGLISH_FEE_SCAM = "hinglish-fee-scam.txt";
    public static final String NO_FEE_GENUINE = "no-fee-genuine.txt";
    public static final String NO_INTERVIEW_PAY_FEE = "no-interview-pay-fee.txt";
    public static final String WHATSAPP_ONLY = "whatsapp-only.txt";
    public static final String PRESSURE = "pressure-language.txt";
    /** {@link #GENUINE} re-typed with other amounts, dates and contact address (P9 SimHash, 05 §4). */
    public static final String COPIED_GENUINE = "copied-genuine-altered-payment.txt";
    /** Another fictional employer's genuine-style letter sharing {@link #GENUINE}'s standard sentences (P9). */
    public static final String OTHER_COMPANY_GENUINE = "genuine-offer-other-company.txt";

    private Letters() {
    }

    public static String fixture(String name) {
        try (InputStream in = Letters.class.getResourceAsStream("/fixtures/letters/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static Letter text(String text) {
        return new Letter(text, null, null, null, null, null);
    }

    /** A letter whose typed sender email is {@code senderEmail}; the domain goes through the real SSRF guard. */
    public static Letter from(String senderEmail, String text) {
        String domain = senderEmail.substring(senderEmail.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT);
        ValidatedDomain validated = new DomainValidator().validate(domain).orElse(null);
        return new Letter(text, senderEmail, domain, validated, null, null);
    }

    /**
     * D-33.6 without a typed sender email: the sender domain is the first email address written in the letter
     * ({@link SenderSource#LETTER}, P13 S2); the domain goes through the real SSRF guard.
     */
    public static Letter fromLetter(String text) {
        Matcher first = FIRST_EMAIL.matcher(text);
        if (!first.find()) {
            throw new IllegalArgumentException("the letter has no email address");
        }
        String domain = first.group(1).toLowerCase(Locale.ROOT);
        ValidatedDomain validated = new DomainValidator().validate(domain).orElse(null);
        return new Letter(text, null, domain, validated, null, null, null, null, SenderSource.LETTER);
    }

    private static final Pattern FIRST_EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+)");
}
