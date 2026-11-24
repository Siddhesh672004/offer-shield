import { RiskBand } from '../ui/risk-band';
import { ReportSignal } from './report-models';

/** PRD F7 marker kinds: a sender domain, an HMAC'd phone number or UPI ID, the wording fingerprint. */
export type MarkerType = 'DOMAIN' | 'PHONE' | 'UPI' | 'SIMHASH';

/**
 * 02 §7 trust levels. UNVERIFIED = only user reports (the dashed "reported" chip); CONFIRMED =
 * an admin or TPO confirmed it ("Confirmed scam"); REJECTED = no reporter left, so it counts
 * for nothing.
 */
export type MarkerStatus = 'UNVERIFIED' | 'CONFIRMED' | 'REJECTED';

/** Where a phone or UPI marker's value came from: typed by the student, or found in the letter. */
export type MarkerSource = 'LETTER' | 'TYPED';

/**
 * `IndicatorView`: a registry marker the letter maps to now. `domain` is set only for DOMAIN
 * (phone and UPI markers are HMAC keys). `hint` is a masked phone or UPI ID ("••••1234",
 * "••••ar@okaxis"), never the raw value, and `source` says who supplied it (null for DOMAIN and
 * SIMHASH); both are missing in older answers. `version` goes back as `If-Match` on Revert (D-13).
 */
export interface Marker {
  readonly id: string;
  readonly type: MarkerType;
  readonly status: MarkerStatus;
  readonly domain: string | null;
  readonly reporterCount: number;
  readonly version: number;
  readonly confirmedAt: string | null;
  readonly source?: MarkerSource | null;
  readonly hint?: string | null;
}

/**
 * `ReportEvidence` (D-04): what a TPO (review case) or the admin (scam report) sees about a
 * letter, from the report snapshot. Never the letter text, the HMAC keys or who reported.
 */
export interface ReportEvidence {
  readonly band: RiskBand;
  readonly bandMessage: string | null;
  readonly score: number;
  readonly hardOverride: boolean;
  readonly partial: boolean;
  readonly senderDomain: string | null;
  readonly signals: readonly ReportSignal[];
  readonly markers: readonly Marker[];
  readonly completedAt: string;
}

/** `PdfLink` (D-05): a short-lived signed link to the private PDF. It is a credential: never log it. */
export interface PdfLink {
  readonly url: string;
  readonly expiresAt: string;
}

export const MARKER_LABELS: Readonly<Record<MarkerType, string>> = {
  DOMAIN: 'Sender domain',
  PHONE: 'Phone number',
  UPI: 'UPI ID',
  SIMHASH: 'Letter wording',
};

/**
 * Said when a scam report or a confirm decision recorded no registry marker (`markersRecorded`
 * 0), instead of claiming that matching letters are now flagged.
 */
export const NO_MARKERS_RECORDED =
  'No scam markers could be recorded for this letter (it has no details the shared registry can use).';

/** Shown next to a phone or UPI marker: a typed value is the student's claim, not the letter's. */
export const MARKER_SOURCE_LABELS: Readonly<Record<MarkerSource, string>> = {
  TYPED: 'typed by the student',
  LETTER: 'in the letter',
};
