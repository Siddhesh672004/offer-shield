package com.offershield.analysis;

import com.offershield.ai.Extraction;
import com.offershield.analysis.checks.AiFindings;
import com.offershield.common.CopyRules;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Turns an AI {@link Extraction} into what the analysis may use (PRD §7, R-A). The AI only <b>adds</b>: its payment
 * mentions, claimed company, phone numbers and UPI IDs become {@link AiFindings} candidates that the deterministic
 * checks evaluate with their own rules; nothing the rules found is removed or lowered, and the score never comes from
 * the AI. Its company name, recruiter name, CTC and joining date become {@link AiDetails} for the report, labelled
 * "AI-extracted", after a last check that each is short, has no placeholder and uses no forbidden wording.
 */
@Component
public class ExtractionMerger {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\[[A-Z]+_\\d+]");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    public AiFindings findings(Optional<Extraction> extraction) {
        return extraction.map(found -> new AiFindings(found.paymentMentions(), found.companyName(),
                found.phoneNumbers(), found.upiIds())).orElse(AiFindings.NONE);
    }

    public AiDetails details(Optional<Extraction> extraction) {
        return extraction.map(found -> new AiDetails(shown(found.companyName()), shown(found.recruiterName()),
                shown(found.ctc()), shown(found.joiningDate()))).orElse(AiDetails.NONE);
    }

    private static String shown(String value) {
        if (value == null) {
            return null;
        }
        String collapsed = SPACES.matcher(value).replaceAll(" ").strip();
        if (collapsed.isEmpty() || collapsed.length() > AiDetails.MAX_LENGTH || PLACEHOLDER.matcher(collapsed).find()
                || CopyRules.violates(collapsed)) {
            return null;
        }
        return collapsed;
    }
}
