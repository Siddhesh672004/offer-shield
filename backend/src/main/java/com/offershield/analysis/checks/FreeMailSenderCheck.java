package com.offershield.analysis.checks;

import com.offershield.common.FreeMailDomains;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * C2, free-mail sender claiming a company (PRD §7.1): +25 (§7.2) when the sender domain (D-33.6: the typed sender
 * email, else the first email address in the letter) is on the free-mail list (D-08). An offer letter always claims
 * an employer, so the sender alone decides. Source kind 1: the address is in what the user gave us.
 */
@Component
public class FreeMailSenderCheck implements RiskCheck {

    static final int POINTS = 25;

    private final FreeMailDomains freeMail;

    public FreeMailSenderCheck(FreeMailDomains freeMail) {
        this.freeMail = freeMail;
    }

    @Override
    public CheckCode code() {
        return CheckCode.C2;
    }

    @Override
    public CheckResult evaluate(Letter letter) {
        String domain = letter.senderDomain() == null ? null : letter.senderDomain().strip().toLowerCase(Locale.ROOT);
        if (domain == null || domain.isEmpty()) {
            return CheckResult.notApplicable(CheckCode.C2,
                    "No sender email address was given or found in the letter, so this check didn't apply.", 1);
        }
        if (freeMail.contains(domain)) {
            return CheckResult.triggered(CheckCode.C2, POINTS,
                    "The offer comes from a free email service (" + domain + ") instead of a company address. "
                            + "Companies normally send offers from their own domain.",
                    Evidence.snippet("Sender address ends in @" + domain), 1);
        }
        return CheckResult.passed(CheckCode.C2, "The sender's address is not from a free email service.", 1);
    }
}
