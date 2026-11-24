import { SourceKind } from '../ui/source-kind';

/** PRD §10 `signal.outcome`. UNAVAILABLE (a lookup failed) makes the report PARTIAL (D-31). */
export type CheckOutcome = 'TRIGGERED' | 'PASSED' | 'UNAVAILABLE' | 'NOT_APPLICABLE';

/**
 * One check's result, as both the owner's report (`SignalResponse`) and the TPO/admin evidence
 * (`EvidenceSignal`, D-04) send it. `evidence` is null unless the check fired, and its contact
 * details are masked. `aiExtracted` marks values the AI found (label them "AI-extracted").
 */
export interface ReportSignal {
  readonly code: string;
  readonly title: string;
  readonly outcome: CheckOutcome;
  readonly points: number;
  /** This check forced the band to High whatever the points (only C1, C10, C11). */
  readonly hardOverride: boolean;
  readonly explanation: string;
  readonly evidence: string | null;
  readonly sourceKind: SourceKind;
  readonly aiExtracted: boolean;
}

/** The titles of the checks that forced High (to explain a High band with a low score). */
export function overrideTitles(signals: readonly ReportSignal[]): string[] {
  return signals
    .filter((signal) => signal.outcome === 'TRIGGERED' && signal.hardOverride)
    .map((signal) => signal.title);
}

/** The titles of the named checks, in the order given; an unknown code is shown as itself. */
export function checkTitles(codes: readonly string[], signals: readonly ReportSignal[]): string[] {
  return codes.map((code) => signals.find((signal) => signal.code === code)?.title ?? code);
}
