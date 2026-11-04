package com.offershield.analysis.checks;

import com.offershield.registry.MatchedMarker;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * C12, unverified user reports (PRD §7.1, §7.2, §7.3): any matching UNVERIFIED marker, counted by distinct people
 * across all matching markers (D-33.5). 3 or more reporters +25, 1–2 +10, source kind 5, shown as "Reported as a scam
 * by N users (not verified)". It never forces High (R-B, the abuse guard: false reports alone cannot make a real
 * company a "confirmed scam"); the {@code ScoringEngine} ignores any override from C12 as well.
 */
@Component
public class UnverifiedReportsCheck implements RiskCheck {

    static final int MANY_REPORTERS = 3;
    static final int MANY_POINTS = 25;
    static final int FEW_POINTS = 10;
    private static final int SOURCE_KIND = 5;

    @Override
    public CheckCode code() {
        return CheckCode.C12;
    }

    @Override
    public CheckResult evaluate(Letter letter) {
        RegistryFindings registry = letter.registry();
        if (!registry.available()) {
            return CheckResult.unavailable(CheckCode.C12, "The scam registry could not be checked this time.",
                    SOURCE_KIND);
        }
        if (!registry.exactKeys() && !registry.fingerprint()) {
            return CheckResult.notApplicable(CheckCode.C12, "The letter has no sender domain, phone number, UPI ID or "
                    + "long enough text to compare with user reports.", SOURCE_KIND);
        }
        int reporters = registry.match().unverifiedReporters();
        List<MatchedMarker> reported = registry.match().unverified();
        if (reporters == 0 || reported.isEmpty()) {
            return CheckResult.passed(CheckCode.C12, "No other user has reported a scam with this letter's details.",
                    SOURCE_KIND);
        }
        boolean aiOnly = RegistryMarkers.onlyThroughAi(reported, registry.aiOnlyKeys());
        String matched = RegistryMarkers.describe(reported);
        int points = reporters >= MANY_REPORTERS ? MANY_POINTS : FEW_POINTS;
        return new CheckResult(CheckCode.C12, CheckOutcome.TRIGGERED, points, false,
                (aiOnly ? "AI-extracted: " : "") + label(reporters) + " Other OfferShield users reported a scam with "
                        + matched + "; no reviewer has confirmed it, so treat it as a warning, not as proof.",
                Evidence.snippet("Reported markers matched: " + matched), SOURCE_KIND, aiOnly);
    }

    /** The mandated trust label (CLAUDE.md product rules), "1 user" / "N users". */
    static String label(int reporters) {
        return "Reported as a scam by " + reporters + (reporters == 1 ? " user" : " users") + " (not verified).";
    }
}
