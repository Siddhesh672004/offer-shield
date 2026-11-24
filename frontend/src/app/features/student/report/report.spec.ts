import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Me } from '../../../core/auth/session';
import { fakeStudent } from '../../../../testing/fakes';
import { answerSessionProbe } from '../../../../testing/session-helpers';
import { Announcer } from '../../../core/a11y/announcer';
import { ReportSignal } from '../../../shared/report/report-models';
import { Report } from '../report-api';
import { Submission, SubmissionStatus } from '../submission-api';
import { ReportPage } from './report-page';

const ID = '00000000-0000-0000-0000-00000000005a';
const STATUS_URL = `/api/submissions/${ID}`;
const REPORT_URL = `/api/reports/${ID}`;

const college = {
  id: '00000000-0000-0000-0000-0000000000c1',
  name: 'Fake Institute of Technology',
};
const approvedStudent: Me = { ...fakeStudent, membershipStatus: 'APPROVED', college };

function submission(status: SubmissionStatus, overrides: Partial<Submission> = {}): Submission {
  return {
    id: ID,
    status,
    sourceType: 'TEXT',
    onCampus: false,
    pdfStored: false,
    createdAt: '2026-10-20T06:30:00Z',
    updatedAt: '2026-10-20T06:30:05Z',
    expiresAt: '2026-11-19T06:30:00Z',
    ...overrides,
  };
}

function signal(overrides: Partial<ReportSignal>): ReportSignal {
  return {
    code: 'C9',
    title: 'Pressure language',
    outcome: 'PASSED',
    points: 0,
    hardOverride: false,
    explanation: 'No pressure phrases were found.',
    evidence: null,
    sourceKind: 1,
    aiExtracted: false,
    ...overrides,
  };
}

/** A fictional deposit letter's report (CLAUDE.md: fake data only). */
function report(overrides: Partial<Report> = {}): Report {
  return {
    submissionId: ID,
    score: 30,
    band: 'HIGH',
    bandMessage: 'Strong scam indicators. Do not pay anything.',
    hardOverride: true,
    partial: false,
    aiUsed: true,
    aiDetails: null,
    senderDomain: 'northwind-careers.example',
    unavailableChecks: [],
    registry: { confirmedScam: false, wordingWarning: false, reportedBy: 0 },
    signals: [
      signal({
        code: 'C1',
        title: 'Money request',
        outcome: 'TRIGGERED',
        hardOverride: true,
        explanation: 'The letter asks you to pay money to get or keep the job.',
        evidence: 'pay a refundable deposit of Rs. 5,000 to [phone]',
      }),
      signal({
        code: 'C5',
        title: 'Young domain',
        outcome: 'TRIGGERED',
        points: 20,
        explanation: 'The sender domain was registered 40 days ago.',
        evidence: 'northwind-careers.example registered 2026-09-10',
        sourceKind: 2,
      }),
      signal({}),
    ],
    createdAt: '2026-10-20T06:30:05Z',
    ...overrides,
  };
}

describe('ReportPage', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<ReportPage>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    fixture?.destroy();
    vi.useRealTimers();
    http.verify();
  });

  function page(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function text(): string {
    return page().textContent ?? '';
  }

  async function tick(ms: number): Promise<void> {
    await vi.advanceTimersByTimeAsync(ms);
    await fixture.whenStable();
  }

  async function open(me: Me = fakeStudent): Promise<void> {
    await answerSessionProbe(me);
    // rxjs timers use setInterval; Angular's own scheduling keeps real timers.
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    fixture = TestBed.createComponent(ReportPage);
    fixture.componentRef.setInput('id', ID);
    await fixture.whenStable();
    await tick(0);
  }

  /**
   * Lets Angular render without waiting for loads: `whenStable` would wait for the cards'
   * resources, which only finish once the test answers them.
   */
  async function render(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve));
    await new Promise((resolve) => setTimeout(resolve));
  }

  /** The cards next to the report load the user's own cases; answer them with none. */
  async function answerSideCalls(): Promise<void> {
    await render();
    http.match('/api/scam-reports').forEach((request) => request.flush([]));
    http.match('/api/reviews').forEach((request) => request.flush([]));
    await fixture.whenStable();
  }

  async function openReport(
    shown: Report,
    me: Me = fakeStudent,
    row: Submission = submission('READY'),
  ): Promise<void> {
    await open(me);
    http.expectOne(STATUS_URL).flush(row);
    await fixture.whenStable();
    http.expectOne(REPORT_URL).flush(shown);
    await answerSideCalls();
  }

  it('stopsOnReady', async () => {
    await open();

    http.expectOne(STATUS_URL).flush(submission('QUEUED'));
    await tick(2_000);
    http.expectOne(STATUS_URL).flush(submission('READY'));
    await fixture.whenStable();
    http.expectOne(REPORT_URL).flush(report());
    await answerSideCalls();
    await tick(20_000);

    http.expectNone(STATUS_URL);
    expect(page().querySelector('app-risk-band')?.textContent).toContain('High risk');
    expect(TestBed.inject(Announcer).message()).toContain('Report ready: High risk');
  });

  it('stopsOnPartial', async () => {
    await open();

    http.expectOne(STATUS_URL).flush(submission('PARTIAL'));
    await fixture.whenStable();
    http.expectOne(REPORT_URL).flush(
      report({
        partial: true,
        aiUsed: false,
        unavailableChecks: ['C5'],
        signals: [
          signal({
            code: 'C5',
            title: 'Young domain',
            outcome: 'UNAVAILABLE',
            explanation: 'The domain registry gave no usable registration date.',
            sourceKind: 2,
          }),
        ],
      }),
    );
    await answerSideCalls();
    await tick(20_000);

    http.expectNone(STATUS_URL);
    const notice = page().querySelector('.partial-notice');
    expect(notice?.textContent).toContain('Young domain');
    expect(text()).toContain('AI unavailable');
  });

  it('stopsOnFailed', async () => {
    await open();

    http.expectOne(STATUS_URL).flush(submission('FAILED'));
    await tick(20_000);

    http.expectNone(STATUS_URL);
    http.expectNone(REPORT_URL);
    expect(text()).toContain("We couldn't check this letter");
  });

  it('waitsForPrevious', async () => {
    await open();

    const slow = http.expectOne(STATUS_URL);
    await tick(6_000);
    // Three ticks passed while the first answer was still on its way: no second request.
    expect(http.match(STATUS_URL)).toHaveLength(0);

    slow.flush(submission('QUEUED'));
    await tick(2_000);
    http.expectOne(STATUS_URL).flush(submission('QUEUED'));
  });

  it('afterThreeMinutes_stillProcessing_stopsPolling', async () => {
    await open();

    for (let elapsed = 0; elapsed < 180_000; elapsed += 2_000) {
      http.match(STATUS_URL).forEach((request) => request.flush(submission('QUEUED')));
      await tick(2_000);
    }
    http.match(STATUS_URL).forEach((request) => request.flush(submission('QUEUED')));
    await tick(10_000);

    http.expectNone(STATUS_URL);
    expect(text()).toContain('Still processing — check My submissions');
    expect(page().querySelector('a[href="/app"]')).not.toBeNull();
    expect(TestBed.inject(Announcer).message()).toContain('Still processing');
  });

  it('transientError_keepsPolling', async () => {
    await open();

    http.expectOne(STATUS_URL).flush('Bad Gateway', { status: 502, statusText: 'Bad Gateway' });
    await tick(2_000);

    http.expectOne(STATUS_URL).flush(submission('QUEUED'));
  });

  /** One failed status check: offline (0) or a 429/502/503/504. */
  function failTransiently(status: 0 | 429 | 502 | 503 | 504): void {
    const request = http.expectOne(STATUS_URL);
    if (status === 0) {
      request.error(new ProgressEvent('error'));
    } else {
      request.flush('Unavailable', { status, statusText: 'Unavailable' });
    }
  }

  it('fiveTransientErrorsInARow_endsInError_notStillProcessing', async () => {
    await open();

    for (const status of [0, 429, 502, 503, 504] as const) {
      failTransiently(status);
      await tick(2_000);
    }
    await tick(10_000);

    http.expectNone(STATUS_URL);
    expect(text()).toContain("Couldn't reach OfferShield");
    expect(text()).toContain('Check your connection and try again.');
    expect(text()).not.toContain('Still processing');
    expect(TestBed.inject(Announcer).message()).toBe(
      "Couldn't reach OfferShield. Check your connection and try again.",
    );
  });

  it('fourTransientErrors_thenAnAnswer_countStartsAgain_keepsPolling', async () => {
    await open();

    for (let round = 0; round < 2; round++) {
      for (let failures = 0; failures < 4; failures++) {
        failTransiently(503);
        await tick(2_000);
      }
      http.expectOne(STATUS_URL).flush(submission('QUEUED'));
      await tick(2_000);
    }

    http.expectOne(STATUS_URL).flush(submission('QUEUED'));
    expect(text()).not.toContain("Couldn't reach OfferShield");
  });

  it('noAnswerByThreeMinutes_endsInError_tryAgainPollsAgain', async () => {
    await open();

    // The first check never answers (exhaustMap skips the ticks meanwhile).
    http.expectOne(STATUS_URL);
    await tick(180_000);
    await tick(10_000);

    http.expectNone(STATUS_URL);
    expect(text()).toContain("Couldn't reach OfferShield");
    expect(text()).not.toContain('Still processing');

    const retry = Array.from(page().querySelectorAll('button')).find(
      (button) => button.textContent?.trim() === 'Try again',
    )!;
    retry.click();
    await tick(0);
    http.expectOne(STATUS_URL).flush(submission('FAILED'));
    await tick(0);
    expect(text()).toContain("We couldn't check this letter");
  });

  it('notFound_showsMessage_stopsPolling', async () => {
    await open();

    http
      .expectOne(STATUS_URL)
      .flush({ status: 404, errorCode: 'NOT_FOUND' }, { status: 404, statusText: 'Not Found' });
    await tick(10_000);

    http.expectNone(STATUS_URL);
    expect(text()).toContain("couldn't find this letter");
  });

  it('deleted_explainsRetention', async () => {
    await open();

    http.expectOne(STATUS_URL).flush(submission('DELETED'));
    await answerSideCalls();

    http.expectNone(REPORT_URL);
    expect(text()).toContain('deleted');
    expect(text()).toContain('30 days');
  });

  it('band_wordShapeAndVerbatimMessage_withOverrideNote', async () => {
    await openReport(report());

    const band = page().querySelector('app-risk-band')!;
    expect(band.textContent).toContain('High risk');
    expect(band.textContent).toContain('Strong scam indicators. Do not pay anything.');
    expect(band.querySelector('svg[data-shape="hexagon"]')).not.toBeNull();
    expect(band.textContent).toContain('30');
    expect(page().querySelector('.override-note')?.textContent).toContain('Money request');
  });

  it.each([
    ['LOW' as const, 'No major red flags found. This does not prove the offer is genuine.'],
    [
      'MEDIUM' as const,
      "Warning signs. Verify through the company's official website or careers page.",
    ],
  ])('band_%s_messageVerbatim_noOverrideNote', async (band, message) => {
    await openReport(report({ band, hardOverride: false, score: band === 'LOW' ? 5 : 30 }));

    expect(page().querySelector('app-risk-band')?.textContent).toContain(message);
    expect(page().querySelector('.override-note')).toBeNull();
  });

  it('trustLevelsDistinct', async () => {
    await openReport(
      report({ registry: { confirmedScam: true, wordingWarning: false, reportedBy: 3 } }),
    );

    const confirmed = page().querySelector('app-report-chip.report-chip--confirmed');
    const reported = page().querySelector('app-report-chip.report-chip--reported');
    expect(confirmed?.textContent?.trim()).toBe('Confirmed scam');
    expect(reported?.textContent?.trim()).toBe('Reported as a scam by 3 users (not verified)');
    expect(confirmed).not.toBe(reported);
  });

  it('reportedOnly_neverShownAsConfirmed', async () => {
    await openReport(
      report({ registry: { confirmedScam: false, wordingWarning: false, reportedBy: 1 } }),
    );

    expect(page().querySelector('app-report-chip.report-chip--reported')?.textContent).toContain(
      'Reported as a scam by 1 user (not verified)',
    );
    expect(page().querySelector('app-report-chip.report-chip--confirmed')).toBeNull();
    expect(text()).not.toContain('Confirmed scam');
  });

  it('wordingWarning_isAWarningNotConfirmed', async () => {
    await openReport(
      report({ registry: { confirmedScam: false, wordingWarning: true, reportedBy: 0 } }),
    );

    expect(text()).toContain('Wording resembles a reported scam');
    expect(page().querySelector('app-report-chip.report-chip--confirmed')).toBeNull();
  });

  it('signals_showEvidenceSourceKindAndAiLabel', async () => {
    await openReport(
      report({
        signals: [
          signal({
            code: 'C1',
            title: 'Money request',
            outcome: 'TRIGGERED',
            hardOverride: true,
            explanation: 'AI-extracted: the letter asks you to pay money.',
            evidence: 'pay Rs. 5,000 security deposit',
            aiExtracted: true,
          }),
        ],
      }),
    );

    const flag = page().querySelector('.signal--triggered')!;
    expect(flag.textContent).toContain('Money request');
    expect(flag.textContent).toContain('pay Rs. 5,000 security deposit');
    expect(flag.textContent).toContain('Source 1: the letter itself');
    expect(flag.textContent).toContain('AI-extracted');
    expect(flag.textContent).toContain('Sets High');
  });

  it('aiDetails_labelledAiExtracted', async () => {
    await openReport(
      report({
        aiDetails: {
          label: 'AI-extracted',
          companyName: 'Fake Northwind Careers Pvt Ltd',
          recruiterName: null,
          ctc: '6,00,000 per annum',
          joiningDate: '1 November 2026',
        },
      }),
    );

    const details = page().querySelector('.ai-details')!;
    expect(details.textContent).toContain('AI-extracted');
    expect(details.textContent).toContain('Fake Northwind Careers Pvt Ltd');
    expect(details.textContent).toContain('6,00,000 per annum');
  });

  it('senderSourceLetter_saysTakenFromTheLetter', async () => {
    await openReport(report({ senderSource: 'LETTER' }));

    const details = page().querySelector('.kv')!;
    expect(details.textContent).toContain('northwind-careers.example');
    expect(details.querySelector('.sender-source')?.textContent?.trim()).toBe(
      'Taken from the letter itself (not typed by you)',
    );
  });

  it.each([['TYPED' as const], [null], [undefined]])(
    'senderSource_%s_noLetterNote',
    async (senderSource) => {
      const shown = report();
      await openReport(senderSource === undefined ? shown : { ...shown, senderSource });

      expect(text()).toContain('northwind-careers.example');
      expect(text()).not.toContain('Taken from the letter itself');
    },
  );

  it('askTpo_hiddenIfIndependent', async () => {
    await openReport(report(), fakeStudent, submission('READY', { onCampus: false }));

    http.expectNone('/api/reviews');
    const buttons = Array.from(page().querySelectorAll('button')).map((b) => b.textContent);
    expect(buttons.join(' ')).not.toContain('Ask my TPO');
  });

  it('askTpo_shownForApprovedOnCampus', async () => {
    await open(approvedStudent);
    http.expectOne(STATUS_URL).flush(submission('READY', { onCampus: true }));
    await fixture.whenStable();
    http.expectOne(REPORT_URL).flush(report());
    await render();
    http.expectOne('/api/reviews').flush([]);
    http.match('/api/scam-reports').forEach((request) => request.flush([]));
    await fixture.whenStable();

    const buttons = Array.from(page().querySelectorAll('button')).map((b) => b.textContent);
    expect(buttons.join(' ')).toContain('Ask my TPO');
  });

  it('reportScam_linkForStudents', async () => {
    await openReport(report());

    expect(page().querySelector(`a[href="/app/submissions/${ID}/report-scam"]`)).not.toBeNull();
  });

  it.each(['LOW', 'MEDIUM', 'HIGH'] as const)('copy_%s_neverClaimsSafety', async (band) => {
    await openReport(
      report({
        band,
        partial: true,
        aiUsed: false,
        registry: { confirmedScam: band === 'HIGH', wordingWarning: true, reportedBy: 2 },
      }),
    );

    const shown = text().replace(/\(not verified\)/g, '');
    expect(shown).not.toMatch(/\b(verified|safe|safely|legit|legitimate|guaranteed)\b/i);
  });
});
