package com.offershield.analysis;

import com.offershield.analysis.checks.CheckCode;
import com.offershield.analysis.checks.CheckOutcome;
import com.offershield.analysis.checks.CheckResult;
import com.offershield.common.Ids;
import com.offershield.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/** One stored signal of a {@link Report} (table {@code report_signal}, PRD §10 "signal"). */
@Entity
@Table(name = "report_signal")
public class ReportSignal extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "check_code", nullable = false, updatable = false, length = 8)
    private CheckCode checkCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, updatable = false, length = 16)
    private CheckOutcome outcome;

    @Column(name = "points", nullable = false, updatable = false)
    private int points;

    @Column(name = "hard_override", nullable = false, updatable = false)
    private boolean hardOverride;

    @Column(name = "explanation", nullable = false, updatable = false, length = 500)
    private String explanation;

    @Column(name = "evidence", updatable = false, length = 200)
    private String evidence;

    @Column(name = "source_kind", nullable = false, updatable = false)
    private int sourceKind;

    @Column(name = "ai_extracted", nullable = false, updatable = false)
    private boolean aiExtracted;

    /** For JPA only. */
    protected ReportSignal() {
    }

    ReportSignal(CheckResult result) {
        super(Ids.newId());
        this.checkCode = result.code();
        this.outcome = result.outcome();
        this.points = result.points();
        this.hardOverride = result.hardOverride();
        this.explanation = result.explanation();
        this.evidence = result.evidence();
        this.sourceKind = result.sourceKind();
        this.aiExtracted = result.aiExtracted();
    }

    public CheckCode getCheckCode() {
        return checkCode;
    }

    public CheckOutcome getOutcome() {
        return outcome;
    }

    public int getPoints() {
        return points;
    }

    public boolean isHardOverride() {
        return hardOverride;
    }

    public String getExplanation() {
        return explanation;
    }

    public String getEvidence() {
        return evidence;
    }

    public int getSourceKind() {
        return sourceKind;
    }

    public boolean isAiExtracted() {
        return aiExtracted;
    }
}
