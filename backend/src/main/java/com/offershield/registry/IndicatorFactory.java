package com.offershield.registry;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The scam markers a report snapshot adds to the registry when its scam is reported or confirmed (PRD §6.4 step 3,
 * §10.1): the sender's registrable domain, the HMAC keys of its phone numbers and UPI IDs, and its SimHash. D-08: a
 * free-mail or known company's official domain never becomes a marker ({@link SharedDomains}). Keys that are not
 * well-formed HMAC values are skipped, so a raw phone number or UPI ID can never be stored as a marker.
 */
@Component
public class IndicatorFactory {

    private static final Pattern HMAC_HEX = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern DOMAIN = Pattern.compile("[a-z0-9.-]{1,253}");
    private static final String LETTER = "LETTER";

    private final SharedDomains sharedDomains;

    public IndicatorFactory(SharedDomains sharedDomains) {
        this.sharedDomains = sharedDomains;
    }

    public List<MarkerCandidate> candidates(ReportSnapshotView snapshot) {
        List<MarkerCandidate> markers = new ArrayList<>();
        String domain = snapshot.domain();
        if (domain != null && DOMAIN.matcher(domain).matches() && !sharedDomains.isShared(domain)
                && reliableSender(snapshot)) {
            markers.add(new MarkerCandidate(IndicatorType.DOMAIN, domain, null));
        }
        add(markers, IndicatorType.PHONE, snapshot.phoneHmacs());
        add(markers, IndicatorType.UPI, snapshot.upiHmacs());
        if (snapshot.simhash() != null) {
            markers.add(MarkerCandidate.simhash(snapshot.simhash()));
        }
        return markers;
    }

    /**
     * P13 (security review S2): a sender domain the student typed, or one from a report made before P13, may become a
     * marker. One taken from the letter itself (D-33.6) only when it is the letter's only email domain: otherwise its
     * author may have written an innocent company's address first to get that domain branded a scam.
     */
    private static boolean reliableSender(ReportSnapshotView snapshot) {
        return !LETTER.equals(snapshot.senderSource()) || Integer.valueOf(1).equals(snapshot.letterEmailDomains());
    }

    private static void add(List<MarkerCandidate> markers, IndicatorType type, List<String> keys) {
        keys.stream().filter(key -> key != null && HMAC_HEX.matcher(key).matches()).distinct()
                .forEach(key -> markers.add(new MarkerCandidate(type, key, null)));
    }
}
