package com.offershield.analysis.checks;

import com.offershield.registry.MatchedMarker;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * C10, confirmed registry match (PRD §7.1, §7.2): the sender domain, a phone number or a UPI ID matches a scam marker
 * that the admin or a TPO confirmed → hard override to High (source kind 4: a named, accountable reviewer). Only
 * CONFIRMED markers count (R-B); UNVERIFIED ones feed C12 only. Shared domains never match (D-08, in the registry).
 * A match resting only on a phone number or UPI ID that the AI found is labelled "AI-extracted" (R-A: it adds risk).
 */
@Component
public class ConfirmedIndicatorCheck implements RiskCheck {

    private static final int SOURCE_KIND = 4;

    @Override
    public CheckCode code() {
        return CheckCode.C10;
    }

    @Override
    public CheckResult evaluate(Letter letter) {
        RegistryFindings registry = letter.registry();
        if (!registry.available()) {
            return CheckResult.unavailable(CheckCode.C10, "The scam registry could not be checked this time.",
                    SOURCE_KIND);
        }
        if (!registry.exactKeys()) {
            return CheckResult.notApplicable(CheckCode.C10, "The letter has no sender domain, phone number or UPI ID "
                    + "to compare with confirmed scams.", SOURCE_KIND);
        }
        List<MatchedMarker> confirmed = registry.match().confirmedExact();
        if (confirmed.isEmpty()) {
            return CheckResult.passed(CheckCode.C10, "No sender domain, phone number or UPI ID in this letter matches a "
                    + "confirmed scam.", SOURCE_KIND);
        }
        boolean aiOnly = RegistryMarkers.onlyThroughAi(confirmed, registry.aiOnlyKeys());
        String matched = RegistryMarkers.describe(confirmed);
        return new CheckResult(CheckCode.C10, CheckOutcome.TRIGGERED, 0, true,
                (aiOnly ? "AI-extracted: " : "") + "Confirmed scam: this letter shares " + matched + " with a scam that "
                        + "a TPO or the admin confirmed. Do not pay anything or share your documents.",
                Evidence.snippet("Confirmed scam markers matched: " + matched), SOURCE_KIND, aiOnly);
    }
}
