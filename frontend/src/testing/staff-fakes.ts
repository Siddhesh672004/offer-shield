import type { ReportSignal } from '../app/shared/report/report-models';
import type { Marker, ReportEvidence } from '../app/shared/report/evidence-models';
import type { ReviewCaseDetail } from '../app/features/tpo/reviews/reviews-api';

/** Fake TPO/admin evidence for tests only (CLAUDE.md: test data is fake). */
export function fakeSignal(overrides: Partial<ReportSignal> = {}): ReportSignal {
  return {
    code: 'C9',
    title: 'Pressure language',
    outcome: 'TRIGGERED',
    points: 10,
    hardOverride: false,
    explanation: 'The letter pushes you to act fast.',
    evidence: 'confirm within 24 hours or the seat goes to another candidate',
    sourceKind: 1,
    aiExtracted: false,
    ...overrides,
  };
}

export function fakeMarker(overrides: Partial<Marker> = {}): Marker {
  return {
    id: '00000000-0000-0000-0000-0000000000a1',
    type: 'DOMAIN',
    status: 'UNVERIFIED',
    domain: 'northwind-careers.example',
    reporterCount: 2,
    version: 4,
    confirmedAt: null,
    ...overrides,
  };
}

export function fakeEvidence(overrides: Partial<ReportEvidence> = {}): ReportEvidence {
  return {
    band: 'HIGH',
    bandMessage: 'Strong scam indicators. Do not pay anything.',
    score: 55,
    hardOverride: false,
    partial: false,
    senderDomain: 'northwind-careers.example',
    signals: [
      fakeSignal({
        code: 'C1',
        title: 'Money request',
        points: 0,
        hardOverride: true,
        aiExtracted: true,
        explanation: 'The letter asks you to pay money to get or keep the job.',
        evidence: 'pay a refundable security deposit of Rs. 5,000 to [phone]',
      }),
      fakeSignal(),
      fakeSignal({
        code: 'C2',
        title: 'Free email sender',
        outcome: 'PASSED',
        points: 0,
        evidence: null,
        explanation: 'The sender does not use a free email service.',
      }),
    ],
    markers: [
      fakeMarker(),
      fakeMarker({
        id: '00000000-0000-0000-0000-0000000000a2',
        type: 'PHONE',
        status: 'CONFIRMED',
        domain: null,
        reporterCount: 1,
        version: 7,
        confirmedAt: '2026-10-19T09:00:00Z',
      }),
    ],
    completedAt: '2026-10-20T06:31:00Z',
    ...overrides,
  };
}

/** An OPEN review case in the TPO's queue, version 2, with a stored PDF and the evidence. */
export function fakeCase(overrides: Partial<ReviewCaseDetail> = {}): ReviewCaseDetail {
  return {
    id: 'c1',
    submissionId: 'sub-1',
    status: 'OPEN',
    note: null,
    studentName: 'Asha Test',
    studentEmail: 'asha.test@example.com',
    createdAt: '2026-10-19T05:00:00Z',
    decidedAt: null,
    version: 2,
    pdfAvailable: true,
    evidence: fakeEvidence(),
    ...overrides,
  };
}

/** The forbidden trust words of CLAUDE.md, outside the mandated "(not verified)". */
export function claimsSafety(text: string): boolean {
  const cleaned = text.replaceAll('(not verified)', '');
  return /\b(verified|safe|safely|legit|legitimate|guaranteed)\b/i.test(cleaned);
}
