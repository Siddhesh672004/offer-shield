package com.offershield.analysis;

import com.offershield.analysis.checks.CheckOutcome;
import com.offershield.analysis.scoring.Band;
import com.offershield.common.SenderSource;
import com.offershield.common.events.AnalysisCompletedEvent;
import com.offershield.common.persistence.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The risk report of one submission (PRD §7, §10). Its ID is the submission's ID, so a submission has at most one
 * report. Written once by the analysis consumer (with {@code ai_used} and the "AI-extracted" details from P7); read by
 * the owner (PRD §11, D-04); {@code explanation} is filled once by "Explain simply". Signals are kept in check order.
 * From P9 it also keeps the letter's registry keys (SimHash, phone/UPI HMAC keys, never the values) and the C12
 * reporter count, so a re-sent {@code analysis.completed} (D-28) carries what the first one did.
 */
@Entity
@Table(name = "report")
public class Report extends BaseEntity {

    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private UUID ownerUserId;

    @Column(name = "college_id", updatable = false)
    private UUID collegeId;

    @Column(name = "score", nullable = false)
    private int score;

    @Enumerated(EnumType.STRING)
    @Column(name = "band", nullable = false, length = 8)
    private Band band;

    @Column(name = "hard_override", nullable = false)
    private boolean hardOverride;

    @Column(name = "partial", nullable = false)
    private boolean partial;

    @Column(name = "ai_used", nullable = false)
    private boolean aiUsed;

    @Column(name = "ai_company_name", length = AiDetails.MAX_LENGTH)
    private String aiCompanyName;

    @Column(name = "ai_recruiter_name", length = AiDetails.MAX_LENGTH)
    private String aiRecruiterName;

    @Column(name = "ai_ctc", length = AiDetails.MAX_LENGTH)
    private String aiCtc;

    @Column(name = "ai_joining_date", length = AiDetails.MAX_LENGTH)
    private String aiJoiningDate;

    /** The "Explain simply" text (P7-03), written once, only when the AI wrote it. */
    @Column(name = "explanation", columnDefinition = "TEXT")
    private String explanation;

    @Column(name = "sender_domain", length = 253)
    private String senderDomain;

    /** P13 (S2): TYPED or LETTER, {@code null} without a sender domain or for reports made before P13. */
    @Enumerated(EnumType.STRING)
    @Column(name = "sender_source", length = 8, updatable = false)
    private SenderSource senderSource;

    /** P13 (S2): distinct registrable email domains written in the letter, {@code null} before P13. */
    @Column(name = "letter_email_domains", updatable = false)
    private Integer letterEmailDomains;

    /** The letter's SimHash (P9), {@code null} under 50 words. */
    @Column(name = "simhash", updatable = false)
    private Long simhash;

    /** HMAC keys of the phone numbers found (P9); never the numbers. */
    @Convert(converter = HmacListConverter.class)
    @Column(name = "phone_hmacs", columnDefinition = "json", updatable = false)
    private List<String> phoneHmacs = new ArrayList<>();

    /** HMAC keys of the UPI IDs found (P9). */
    @Convert(converter = HmacListConverter.class)
    @Column(name = "upi_hmacs", columnDefinition = "json", updatable = false)
    private List<String> upiHmacs = new ArrayList<>();

    /** P13 (N2): per phone / UPI key, typed or in the letter, and a masked hint; never the values. */
    @Convert(converter = MarkerHintListConverter.class)
    @Column(name = "marker_hints", columnDefinition = "json", updatable = false)
    private List<AnalysisCompletedEvent.MarkerHint> markerHints = new ArrayList<>();

    @Column(name = "indicators_complete", nullable = false, updatable = false)
    private boolean indicatorsComplete;

    /** Distinct people who reported a matching marker that is not confirmed (C12, D-33.5). */
    @Column(name = "reported_by", nullable = false, updatable = false)
    private int reportedBy;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "report_id", nullable = false, updatable = false)
    private List<ReportSignal> signals = new ArrayList<>();

    /** For JPA only. */
    protected Report() {
    }

    Report(NewReport report) {
        super(report.submissionId());
        this.ownerUserId = report.ownerUserId();
        this.collegeId = report.collegeId();
        this.score = report.score().points();
        this.band = report.score().band();
        this.hardOverride = report.score().hardOverride();
        this.partial = report.partial();
        this.aiUsed = report.aiUsed();
        this.aiCompanyName = report.aiDetails().companyName();
        this.aiRecruiterName = report.aiDetails().recruiterName();
        this.aiCtc = report.aiDetails().ctc();
        this.aiJoiningDate = report.aiDetails().joiningDate();
        this.senderDomain = report.senderDomain();
        this.simhash = report.markers().simhash();
        this.phoneHmacs = new ArrayList<>(report.markers().phoneHmacs());
        this.upiHmacs = new ArrayList<>(report.markers().upiHmacs());
        this.markerHints = new ArrayList<>(report.markers().hints());
        this.senderSource = report.senderSource();
        this.letterEmailDomains = report.letterEmailDomains();
        this.indicatorsComplete = report.markers().complete();
        this.reportedBy = report.reportedBy();
        report.results().forEach(result -> signals.add(new ReportSignal(result)));
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public UUID getCollegeId() {
        return collegeId;
    }

    public int getScore() {
        return score;
    }

    public Band getBand() {
        return band;
    }

    public boolean isHardOverride() {
        return hardOverride;
    }

    public boolean isPartial() {
        return partial;
    }

    public boolean isAiUsed() {
        return aiUsed;
    }

    public String getSenderDomain() {
        return senderDomain;
    }

    public Long getSimhash() {
        return simhash;
    }

    public List<String> getPhoneHmacs() {
        return phoneHmacs == null ? List.of() : List.copyOf(phoneHmacs);
    }

    public List<String> getUpiHmacs() {
        return upiHmacs == null ? List.of() : List.copyOf(upiHmacs);
    }

    public SenderSource getSenderSource() {
        return senderSource;
    }

    public Integer getLetterEmailDomains() {
        return letterEmailDomains;
    }

    public List<AnalysisCompletedEvent.MarkerHint> getMarkerHints() {
        return markerHints == null ? List.of() : List.copyOf(markerHints);
    }

    public boolean isIndicatorsComplete() {
        return indicatorsComplete;
    }

    public int getReportedBy() {
        return reportedBy;
    }

    /** What the AI extraction read in the letter ({@link AiDetails#NONE} when nothing). */
    public AiDetails getAiDetails() {
        return new AiDetails(aiCompanyName, aiRecruiterName, aiCtc, aiJoiningDate);
    }

    public String getExplanation() {
        return explanation;
    }

    /** Signals in check order (C1, C2, … C12). */
    public List<ReportSignal> getSignals() {
        return signals.stream().sorted(Comparator.comparing(ReportSignal::getCheckCode)).toList();
    }

    /** Codes of the checks that could not run, in check order. */
    public List<String> unavailableChecks() {
        return getSignals().stream()
                .filter(signal -> signal.getOutcome() == CheckOutcome.UNAVAILABLE)
                .map(signal -> signal.getCheckCode().name())
                .toList();
    }
}
