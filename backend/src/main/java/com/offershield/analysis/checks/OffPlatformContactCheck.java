package com.offershield.analysis.checks;

import com.offershield.common.FreeMailDomains;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * C8, off-platform contact only (PRD §7.1): +15 (§7.2) when the letter sends you to WhatsApp or Telegram and gives no
 * company channel, meaning no email address (typed or in the letter) on a domain that is not a free-mail provider.
 * Source kind 1.
 */
@Component
public class OffPlatformContactCheck implements RiskCheck {

    static final int POINTS = 15;
    private static final Pattern MESSENGER = Pattern.compile(
            "\\bwhats\\s?app\\b|\\bwa\\.me\\b|\\btelegram\\b|\\bt\\.me\\b", Pattern.CASE_INSENSITIVE);

    private final FreeMailDomains freeMail;

    public OffPlatformContactCheck(FreeMailDomains freeMail) {
        this.freeMail = freeMail;
    }

    @Override
    public CheckCode code() {
        return CheckCode.C8;
    }

    @Override
    public CheckResult evaluate(Letter letter) {
        String mention = Clauses.of(letter.text()).stream()
                .filter(clause -> MESSENGER.matcher(clause).find())
                .findFirst()
                .orElse(null);
        if (mention == null) {
            return CheckResult.passed(CheckCode.C8, "The letter doesn't ask you to talk only on WhatsApp or Telegram.", 1);
        }
        if (hasCompanyEmail(letter)) {
            return CheckResult.passed(CheckCode.C8,
                    "The letter mentions WhatsApp or Telegram but also gives a company email address.", 1);
        }
        return CheckResult.triggered(CheckCode.C8, POINTS,
                "The only contact offered is WhatsApp or Telegram, with no company email address. Real employers "
                        + "give you an official channel.",
                Evidence.snippet(mention), 1);
    }

    /** True when the text sends the reader to WhatsApp or Telegram (also used by the copied-letter guard, P13). */
    static boolean mentionsMessenger(String text) {
        return text != null && MESSENGER.matcher(text).find();
    }

    private boolean hasCompanyEmail(Letter letter) {
        Set<String> domains = new HashSet<>(EmailDomains.in(letter.text()));
        String sender = EmailDomains.domainName(letter.senderDomain());
        if (sender != null) {
            domains.add(sender);
        }
        return domains.stream().anyMatch(domain -> !freeMail.contains(domain));
    }
}
