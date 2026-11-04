package com.offershield.analysis.checks;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * C1, money request (PRD §7.1): a hard override to High (§7.2). Works clause by clause ({@link Clauses}).
 *
 * <p>A clause asks for money when it has
 * <ul>
 *   <li>a kind of fee or deposit: "registration fee", "training charges", "security deposit", "laptop amount", …</li>
 *   <li>the word "refundable"</li>
 *   <li>"pay / transfer / send / remit / deposit" followed by an amount ("pay ₹5,000")</li>
 *   <li>"deposit", "fee(s)" or "charges" together with an amount or with another paying word ("pay the fee")</li>
 *   <li>a Hinglish paying phrase with a money word ("3,500 rupaye jama karni hogi", "paise bhejo")</li>
 * </ul>
 * A Hinglish number counts as money only next to "bhugtan" (payment): "Documents 2 din mein bhejo" is not a request.
 *
 * <p>D-09: a clause with a negation (no, not, never, without, free of, nil, zero, N/A, waived, nahi, bina, …) and no
 * amount (₹/Rs/INR/rupees with a number, "5000/-", or "5,000") is ignored, so "No registration fee is charged" is not
 * flagged. The negation must sit in the same clause, so "No interview, pay registration fee today" is still flagged.
 * A zero amount ("Registration fee: ₹0") counts as a negation.
 *
 * <p>P13 (01 §3, "Answers (2026-10-21, P13 fixes A)"), checked in this order before the rules above:
 * <ol>
 *   <li>A fraud warning ("Beware of fraudsters who demand a deposit", "If anyone asks you to pay a fee, report it")
 *       counts like a negation, unless the clause states an amount or starts by telling you to pay.</li>
 *   <li>Money the employer pays or covers ("we will pay ₹50,000 as a joining bonus", "borne by the company", "the
 *       company will reimburse the fee") or an employer benefit ("group medical insurance amount") is not a request,
 *       unless the same clause makes the candidate pay ("after you pay", "refundable", "security deposit").</li>
 *   <li>A penalty for leaving early anywhere in the sentence (service bond, liquidated damages, notice-period buyout,
 *       "if you resign") is not a request, unless the clause asks for it up front ("before joining", "in advance",
 *       "refundable", or starts with "Pay").</li>
 * </ol>
 *
 * <p>P7, R-A: when the rule finds nothing in the letter, it runs on the sentences the AI extraction marked as payment
 * requests ({@link AiFindings#paymentMentions()}, each already found in the letter). A hit there is labelled
 * "AI-extracted" ({@code aiExtracted}); the evidence is still the letter's own words, source kind 1.
 */
@Component
public class MoneyRequestCheck implements RiskCheck {

    static final String TRIGGERED = "The letter asks you to pay money, such as a fee or a deposit. Real employers "
            + "don't charge candidates to get or start a job.";
    static final String TRIGGERED_AI = "AI-extracted: the AI pointed to this sentence in the letter, and our money rule "
            + "confirmed it asks you to pay money, such as a fee or a deposit. Real employers don't charge candidates "
            + "to get or start a job.";
    static final String PASSED = "No request for money was found in the letter.";

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    // Every repetition is bounded (an amount is short), so no pattern backtracks quadratically on hostile text.
    private static final String CURRENCY = "(?:₹|\\brs\\b\\.?|\\binr\\b|\\brupees?\\b|\\brupay\\w{0,4}|\\brupai\\w{0,4})";
    private static final String NUMBER = "\\d[\\d,]{0,15}(?:\\.\\d{1,2})?";
    private static final String AMOUNT_TEXT = CURRENCY + "\\s{0,3}" + NUMBER
            + "|" + NUMBER + "\\s{0,3}(?:/-|" + CURRENCY + ")"
            + "|\\b\\d{1,3}(?:,\\d{2,3}){1,5}\\b";
    private static final Pattern AMOUNT = Pattern.compile(AMOUNT_TEXT, FLAGS);
    private static final Pattern NEGATION = Pattern.compile(
            "\\b(?:no|not|never|none|nor|neither|without|nil|zero|waived|nahi|nahin|mat|bina)\\b|n['’]t\\b"
                    + "|\\bn\\s?/\\s?a\\b|\\bfree\\s+of\\b|\\bcost[- ]free\\b", FLAGS);
    /** P13: a fraud warning or a "report it" instruction is not a request either ("Beware of fraudsters who …"). */
    private static final Pattern WARNING = Pattern.compile(
            "\\bbeware\\b|\\bfraud\\w{0,10}|\\bfake\\b|\\bscam\\w{0,10}|\\bimpersonat\\w{0,10}"
                    + "|\\bif\\s{1,5}(?:anyone|anybody|someone|somebody)\\b"
                    + "|\\breport\\s{1,5}(?:any|such|it|this|them|these)\\b|\\bentertain\\b", FLAGS);
    /** A clause that starts by telling the candidate to pay ("Pay …", "Kindly transfer …"). */
    private static final Pattern IMPERATIVE_PAY = Pattern.compile(
            "^\\W{0,5}(?:(?:kindly|please|just|so|now|then|and)\\s{1,5}){0,2}(?:pay|deposit|transfer|remit|send)\\b",
            FLAGS);
    /** The candidate is the one who pays ("after you pay", "once you deposit", "deducted from your salary"). */
    private static final Pattern CANDIDATE_PAYS = Pattern.compile(
            "\\b(?:you|candidates?|students?|applicants?)\\s{1,5}(?:\\w{1,20}\\s{1,5}){0,3}?"
                    + "(?:pay|deposit|transfer|remit)\\b|\\brefundable\\b|\\b(?:security|caution)\\s{1,5}"
                    + "(?:deposit|amount|money)\\b|\\bdeduct\\w{0,4}\\b|\\badjust\\w{0,4}\\s{1,5}(?:against|from)\\b",
            FLAGS);
    /** CR1: money the employer pays or covers, actively or passively. */
    private static final Pattern EMPLOYER_PAYS = Pattern.compile(
            "\\b(?:we|company|employer|organi[sz]ation|firm)\\s{1,5}(?:will|shall|would)\\s{1,5}(?:also\\s{1,5})?"
                    + "(?:pay|reimburse|refund|transfer|credit|deposit|bear|cover|sponsor)\\b"
                    + "|\\b(?:borne|paid|reimbursed|refunded|covered|sponsored)\\s{1,5}"
                    + "(?:(?:fully|entirely|completely)\\s{1,5})?by\\s{1,5}(?:(?:the|our)\\s{1,5})?"
                    + "(?:company|us|employer|organi[sz]ation|firm)\\b", FLAGS);
    /** P13: a benefit the employer provides, stated as an amount ("Group medical insurance amount: Rs. 3,00,000"). */
    private static final Pattern EMPLOYER_BENEFIT = Pattern.compile(
            "\\bgroup\\s{1,5}(?:(?:medical|health|life|term|accident)\\s{1,5})?insurance\\b"
                    + "|\\binsurance\\s{1,5}cover(?:age)?\\b|\\bsum\\s{1,5}insured\\b|\\bmediclaim\\b"
                    + "|\\bcost\\s{1,5}to\\s{1,5}(?:the\\s{1,5})?company\\b|\\bctc\\b", FLAGS);
    /**
     * P13: a penalty for leaving early (service bond, liquidated damages, notice-period buyout). Common in genuine
     * Indian offers and conditional, so not a request, unless the same clause asks for it up front.
     */
    private static final Pattern PENALTY = Pattern.compile(
            "\\bliquidated\\s{1,5}damages\\b|\\bservice\\s{1,5}(?:agreement|bond|contract)\\b|\\bbond\\s{1,5}period\\b"
                    + "|\\bnotice[-\\s]period\\s{1,5}(?:buy[-\\s]?out|recovery|pay)\\b|\\bbuy[-\\s]?out\\b"
                    + "|\\b(?:if|in\\s{1,5}case|should)\\s{1,5}you\\s{1,5}(?:resign|leave|quit|abscond|discontinue"
                    + "|exit|break)\\b|\\bearly\\s{1,5}(?:resignation|exit|termination|separation)\\b|\\brecoverable\\b",
            FLAGS);
    private static final Pattern UP_FRONT = Pattern.compile(
            "\\brefundable\\b|\\bbefore\\s{1,5}(?:(?:the|your)\\s{1,5})?(?:joining|onboarding|training)\\b"
                    + "|\\bbefore\\s{1,5}you\\s{1,5}join\\b|\\bin\\s{1,5}advance\\b|\\badvance\\b|\\bup[- ]?front\\b"
                    + "|\\bto\\s{1,5}(?:confirm|secure|block|reserve)\\b|\\b(?:security|caution)\\s{1,5}deposit\\b",
            FLAGS);
    private static final Pattern QUALIFIED_FEE = Pattern.compile(
            "\\b(?:refundable|security|caution|interview|registration|processing|training|joining|onboarding"
                    + "|verification|documentation|document|application|laptop|kit|uniform|medical|certification"
                    + "|placement|consultancy|service|enrol?lment|insurance)\\s+(?:deposit|fees?|charges?|amount"
                    + "|money|payment|cost)\\b", FLAGS);
    private static final Pattern REFUNDABLE = Pattern.compile("\\brefundable\\b", FLAGS);
    private static final Pattern PAY_AMOUNT = Pattern.compile(
            "\\b(?:pay|transfer|send|remit|deposit)\\s+(?:(?:an?|the|us|only|just)\\s+)?(?:(?:amount|sum)\\s+of\\s+)?"
                    + "(?:" + AMOUNT_TEXT + ")", FLAGS);
    private static final Pattern FEE_NOUN = Pattern.compile("\\b(?:deposit|fees?|charges)\\b", FLAGS);
    private static final Pattern PAY_WORD = Pattern.compile(
            "\\b(?:pay|paying|payment|transfer|remit|send|submit|make|deposit)\\b", FLAGS);
    private static final Pattern HINGLISH_PAY = Pattern.compile(
            "\\bjama\\s+kar\\w*|\\bbhej(?:o|na|ni|ne|en|ein|iye|enge|dena|dein)\\b"
                    + "|\\bbhar(?:o|na|ni|ne|en|ein|iye|enge)\\b|\\bbhugtan\\b"
                    + "|\\b(?:pay|deposit|transfer)\\s+kar\\w*", FLAGS);
    /** CR1: a money word or an amount; a bare number ("15 November tak", "2 din mein") is not money. */
    private static final Pattern HINGLISH_MONEY = Pattern.compile(
            "\\b(?:paise|paisa|rupay\\w*|rupai\\w*|rupees?|rs|fees?|shulk|charges?|deposit|amount|rashi)\\b|₹|"
                    + AMOUNT_TEXT, FLAGS);
    /** "bhugtan" means payment, so with it any number is an amount ("2000 ka bhugtan karein"). */
    private static final Pattern HINGLISH_PAYMENT_WITH_NUMBER = Pattern.compile("\\bbhugtan\\b.{0,40}\\d|\\d.{0,40}"
            + "\\bbhugtan\\b", FLAGS);

    @Override
    public CheckCode code() {
        return CheckCode.C1;
    }

    @Override
    public CheckResult evaluate(Letter letter) {
        Optional<String> asked = moneyRequest(List.of(letter.text()));
        if (asked.isPresent()) {
            return CheckResult.override(CheckCode.C1, TRIGGERED, Evidence.snippet(asked.get()), 1);
        }
        // R-A: sentences the AI pointed at (already found in the letter) get the same rule, D-09 included. They can
        // only add this finding; a miss here changes nothing.
        Optional<String> aiAsked = moneyRequest(letter.ai().paymentMentions());
        if (aiAsked.isPresent()) {
            return new CheckResult(CheckCode.C1, CheckOutcome.TRIGGERED, 0, true, TRIGGERED_AI,
                    Evidence.snippet(aiAsked.get()), 1, true);
        }
        return CheckResult.passed(CheckCode.C1, PASSED, 1);
    }

    /** The first clause that asks for money, sentence by sentence (a penalty clause needs its whole sentence). */
    private static Optional<String> moneyRequest(List<String> texts) {
        for (String text : texts) {
            for (String sentence : Clauses.sentences(text)) {
                for (String clause : Clauses.of(sentence)) {
                    if (asksForMoney(clause, sentence)) {
                        return Optional.of(clause);
                    }
                }
            }
        }
        return Optional.empty();
    }

    static boolean asksForMoney(String clause, String sentence) {
        Amounts amounts = amounts(clause);
        boolean negated = amounts.zero() || NEGATION.matcher(clause).find();
        if (negated && !amounts.nonZero()) {
            return false; // D-09: "No registration fee is charged", "Registration fee: N/A"
        }
        boolean imperative = IMPERATIVE_PAY.matcher(clause).find();
        if (WARNING.matcher(clause).find() && !amounts.nonZero() && !imperative) {
            return false; // P13: "Beware of fraudsters who demand a security deposit"
        }
        boolean candidatePays = imperative || CANDIDATE_PAYS.matcher(clause).find();
        if ((EMPLOYER_PAYS.matcher(clause).find() || EMPLOYER_BENEFIT.matcher(clause).find()) && !candidatePays) {
            return false; // CR1: money the employer pays or a benefit it gives ("borne by the company")
        }
        if (PENALTY.matcher(sentence).find() && !imperative && !UP_FRONT.matcher(clause).find()) {
            return false; // P13: a service bond or liquidated damages on early resignation
        }
        if (QUALIFIED_FEE.matcher(clause).find() || REFUNDABLE.matcher(clause).find()) {
            return true;
        }
        if (PAY_AMOUNT.matcher(clause).find()) {
            return true;
        }
        if (FEE_NOUN.matcher(clause).find() && (amounts.nonZero() || payWordBesidesFeeNoun(clause))) {
            return true;
        }
        return HINGLISH_PAY.matcher(clause).find() && (HINGLISH_MONEY.matcher(clause).find()
                || HINGLISH_PAYMENT_WITH_NUMBER.matcher(clause).find());
    }

    /** "pay the deposit" counts, but the single word "deposit" is not both the noun and the paying word. */
    private static boolean payWordBesidesFeeNoun(String clause) {
        List<Integer> nounStarts = new ArrayList<>();
        Matcher noun = FEE_NOUN.matcher(clause);
        while (noun.find()) {
            nounStarts.add(noun.start());
        }
        Matcher pay = PAY_WORD.matcher(clause);
        while (pay.find()) {
            if (!nounStarts.contains(pay.start())) {
                return true;
            }
        }
        return false;
    }

    private static Amounts amounts(String clause) {
        boolean zero = false;
        boolean nonZero = false;
        Matcher matcher = AMOUNT.matcher(clause);
        while (matcher.find()) {
            if (matcher.group().chars().anyMatch(ch -> ch >= '1' && ch <= '9')) {
                nonZero = true;
            } else {
                zero = true;
            }
        }
        return new Amounts(zero, nonZero);
    }

    private record Amounts(boolean zero, boolean nonZero) {
    }
}
