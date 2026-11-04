package com.offershield.analysis.checks;

import com.offershield.company.KnownCompanyDirectory;
import com.offershield.registry.MatchService;
import com.offershield.registry.MatchedMarker;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * C11, near-duplicate of a confirmed scam letter (PRD §7.1, §7.2, §10.1): the letter's SimHash is within
 * {@value MatchService#NEAR_DUPLICATE_BITS} of 64 bits of a CONFIRMED wording marker (R-B: UNVERIFIED ones feed C12
 * only). Source kind 4. The copied-letter guard (PRD §7.3, {@link CopiedLetterGuard}) decides the effect:
 * <ul>
 *   <li>the sender is not on an official domain of a company the letter names → hard override to High;</li>
 *   <li>it is (the company identified by the rules, never by the AI, R-A) → +20 with the warning "wording resembles a
 *       reported scam": a genuine employer's letter can match a confirmed copy of itself.</li>
 * </ul>
 * Letters under 50 words have no fingerprint (not applicable).
 */
@Component
public class NearDuplicateCheck implements RiskCheck {

    static final int OFFICIAL_SENDER_POINTS = 20;
    private static final int SOURCE_KIND = 4;

    private final CopiedLetterGuard guard;

    public NearDuplicateCheck(CopiedLetterGuard guard) {
        this.guard = guard;
    }

    @Override
    public CheckCode code() {
        return CheckCode.C11;
    }

    @Override
    public CheckResult evaluate(Letter letter) {
        RegistryFindings registry = letter.registry();
        if (!registry.available()) {
            return CheckResult.unavailable(CheckCode.C11, "The scam registry could not be checked this time.",
                    SOURCE_KIND);
        }
        if (!registry.fingerprint()) {
            return CheckResult.notApplicable(CheckCode.C11, "The letter is too short (under 50 words) to compare its "
                    + "wording reliably.", SOURCE_KIND);
        }
        Optional<MatchedMarker> closest = registry.match().closestConfirmedWording();
        if (closest.isEmpty()) {
            return CheckResult.passed(CheckCode.C11, "The wording doesn't closely match any letter confirmed as a scam.",
                    SOURCE_KIND);
        }
        String evidence = Evidence.snippet("Wording matches a confirmed scam letter (" + closest.get().distance()
                + " of 64 fingerprint bits differ)");
        Optional<KnownCompanyDirectory.Company> official = guard.officialSender(letter);
        if (official.isPresent()) {
            String name = official.get().name();
            return CheckResult.triggered(CheckCode.C11, OFFICIAL_SENDER_POINTS, "Wording resembles a reported scam. "
                    + "The sender uses an official domain of " + name + ", so this is a warning, not a confirmed scam: "
                    + "scammers copy real companies' letters word for word. Check the offer with " + name
                    + " directly.", evidence, SOURCE_KIND);
        }
        return CheckResult.override(CheckCode.C11, "Confirmed scam: the wording almost exactly matches a letter "
                + "confirmed as a scam (only names, numbers or contact details differ), and it was not sent from an "
                + "official domain of a company the letter names. Do not pay anything.", evidence, SOURCE_KIND);
    }
}
