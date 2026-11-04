package com.offershield.analysis.checks;

/**
 * One rule of PRD §7.1 (Strategy pattern): every check is a Spring bean, and the analysis runs all of them. A check
 * never throws for bad input; a lookup that fails becomes {@link CheckOutcome#UNAVAILABLE}.
 */
public interface RiskCheck {

    CheckCode code();

    CheckResult evaluate(Letter letter);
}
