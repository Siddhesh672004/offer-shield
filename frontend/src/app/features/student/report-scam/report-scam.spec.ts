import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { fakeStudent } from '../../../../testing/fakes';
import { claimsSafety } from '../../../../testing/staff-fakes';
import { answerSessionProbe } from '../../../../testing/session-helpers';
import { Announcer } from '../../../core/a11y/announcer';
import { ScamReport } from '../scam-report-api';
import { Submission, SubmissionStatus } from '../submission-api';
import { ReportScam } from './report-scam';

const ID = '00000000-0000-0000-0000-00000000005a';
const FAKE_STORY =
  'The fake recruiter asked me to pay Rs. 3,000 for a laptop kit before the joining date.';

function letter(status: SubmissionStatus = 'READY'): Submission {
  return {
    id: ID,
    status,
    sourceType: 'TEXT',
    onCampus: false,
    pdfStored: false,
    createdAt: '2026-10-20T06:30:00Z',
    updatedAt: '2026-10-20T06:30:05Z',
    expiresAt: '2026-11-19T06:30:00Z',
  };
}

function scamReport(overrides: Partial<ScamReport> = {}): ScamReport {
  return {
    id: '00000000-0000-0000-0000-0000000000s1',
    submissionId: ID,
    status: 'OPEN',
    amountAsked: 3000,
    createdAt: '2026-10-20T07:00:00Z',
    decidedAt: null,
    ...overrides,
  };
}

describe('ReportScam', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<ReportScam>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function page(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function announced(): string {
    return TestBed.inject(Announcer).message();
  }

  /** Renders without waiting for the resources (whenStable would wait for their answers). */
  async function render(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve));
    await new Promise((resolve) => setTimeout(resolve));
  }

  async function settle(): Promise<void> {
    await fixture.whenStable();
    await render();
    await fixture.whenStable();
  }

  async function open(row: Submission = letter(), mine: ScamReport[] = []): Promise<void> {
    await answerSessionProbe(fakeStudent);
    fixture = TestBed.createComponent(ReportScam);
    fixture.componentRef.setInput('id', ID);
    await render();
    http.expectOne(`/api/submissions/${ID}`).flush(row);
    http.expectOne('/api/scam-reports').flush(mine);
    await settle();
  }

  function type(selector: string, value: string): void {
    const control = page().querySelector<HTMLInputElement | HTMLTextAreaElement>(selector)!;
    control.value = value;
    control.dispatchEvent(new Event('input'));
  }

  async function send(description = FAKE_STORY, amount = ''): Promise<void> {
    type('#scam-description', description);
    type('#scam-amount', amount);
    await fixture.whenStable();
    page().querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    await fixture.whenStable();
  }

  it('submit_postsDescriptionAndAmount_showsWaitingForReview', async () => {
    await open();

    await send(FAKE_STORY, '3,000');
    const post = http.expectOne((request) => request.method === 'POST');
    expect(post.request.url).toBe('/api/scam-reports');
    expect(post.request.body).toEqual({
      submissionId: ID,
      description: FAKE_STORY,
      amountAsked: 3000,
    });
    post.flush(scamReport(), { status: 201, statusText: 'Created' });
    await settle();

    expect(page().textContent).toContain('Waiting for review');
    expect(page().textContent).toContain('₹3,000');
    expect(page().querySelector('#scam-description')).toBeNull();
    expect(announced()).toContain('Scam report sent');
  });

  it('markersRecordedZero_saysNoMarkersCouldBeRecorded', async () => {
    await open();

    await send();
    http
      .expectOne((request) => request.method === 'POST')
      .flush(scamReport({ markersRecorded: 0 }), { status: 201, statusText: 'Created' });
    await settle();

    const note = page().querySelector('.scam-no-markers');
    expect(note?.textContent).toContain('No scam markers could be recorded for this letter');
    expect(announced()).toContain('No scam markers could be recorded for this letter');
    expect(page().textContent).toContain('Waiting for review');
    expect(claimsSafety(page().textContent ?? '')).toBe(false);
  });

  it.each([[2], [undefined]])('markersRecorded_%s_noMarkerNote', async (markersRecorded) => {
    await open();

    await send();
    const created = scamReport();
    http
      .expectOne((request) => request.method === 'POST')
      .flush(markersRecorded === undefined ? created : { ...created, markersRecorded }, {
        status: 201,
        statusText: 'Created',
      });
    await settle();

    expect(page().querySelector('.scam-no-markers')).toBeNull();
    expect(page().textContent).not.toContain('No scam markers');
    expect(announced()).toBe('Scam report sent. An admin will review it.');
  });

  it('409_reportNotReady_showsDetailWithTryAgain_thatSendsAgain', async () => {
    await open();

    await send();
    http
      .expectOne((request) => request.method === 'POST')
      .flush(
        {
          status: 409,
          errorCode: 'REPORT_NOT_READY',
          detail: 'This report is still being prepared. Try again in a minute.',
        },
        { status: 409, statusText: 'Conflict' },
      );
    await settle();

    const alert = page().querySelector('.scam-error')!;
    expect(alert.textContent).toContain(
      'This report is still being prepared. Try again in a minute.',
    );
    expect(announced()).toContain('still being prepared');
    expect(page().querySelector('#scam-description')).not.toBeNull();

    const retry = Array.from(alert.querySelectorAll('button')).find(
      (button) => button.textContent?.trim() === 'Try again',
    )!;
    retry.click();
    await fixture.whenStable();
    const again = http.expectOne((request) => request.method === 'POST');
    expect(again.request.body).toEqual({
      submissionId: ID,
      description: FAKE_STORY,
      amountAsked: null,
    });
    again.flush(scamReport(), { status: 201, statusText: 'Created' });
    await settle();

    expect(page().textContent).toContain('Waiting for review');
  });

  it('mineLoadFails_warnsInsteadOfShowingTheForm_tryAgainReloads', async () => {
    await answerSessionProbe(fakeStudent);
    fixture = TestBed.createComponent(ReportScam);
    fixture.componentRef.setInput('id', ID);
    await render();
    http.expectOne(`/api/submissions/${ID}`).flush(letter());
    http
      .expectOne('/api/scam-reports')
      .flush('Bad Gateway', { status: 502, statusText: 'Bad Gateway' });
    await settle();

    expect(page().querySelector('#scam-description')).toBeNull();
    const warning = page().querySelector('.scam-mine-error')!;
    expect(warning.textContent).toContain("Couldn't check your earlier scam reports");

    Array.from(warning.querySelectorAll('button'))
      .find((button) => button.textContent?.trim() === 'Try again')!
      .click();
    await render();
    http.expectOne('/api/scam-reports').flush([]);
    await settle();

    expect(page().querySelector('.scam-mine-error')).toBeNull();
    expect(page().querySelector('#scam-description')).not.toBeNull();
  });

  it('blankAmount_sentAsNull', async () => {
    await open();

    await send(FAKE_STORY, '');
    const post = http.expectOne((request) => request.method === 'POST');
    expect(post.request.body).toEqual({
      submissionId: ID,
      description: FAKE_STORY,
      amountAsked: null,
    });
    post.flush(scamReport({ amountAsked: null }), { status: 201, statusText: 'Created' });
    await settle();
  });

  it('409_showsExistingReport', async () => {
    await open();

    await send();
    http
      .expectOne((request) => request.method === 'POST')
      .flush(
        { status: 409, errorCode: 'SCAM_REPORT_EXISTS', detail: 'x' },
        { status: 409, statusText: 'Conflict' },
      );
    const reload = await vi.waitFor(() => http.expectOne('/api/scam-reports'));
    reload.flush([scamReport({ status: 'REJECTED', decidedAt: '2026-10-21T06:30:00Z' })]);
    await settle();

    expect(page().textContent).toContain('You already reported this letter');
    expect(page().textContent).toContain('Not confirmed');
    expect(page().querySelector('#scam-description')).toBeNull();
    expect(announced()).toContain('already reported');
  });

  it('429_showsRetryAfter', async () => {
    await open();

    await send();
    http
      .expectOne((request) => request.method === 'POST')
      .flush(
        { status: 429, errorCode: 'SCAM_REPORT_LIMIT', detail: 'x' },
        { status: 429, statusText: 'Too Many Requests', headers: { 'Retry-After': '10000' } },
      );
    await settle();

    const alert = page().querySelector('.scam-error');
    expect(alert?.textContent).toContain('5 scams a day');
    expect(alert?.textContent).toContain('about 3 hours');
    expect(announced()).toContain('5 scams a day');
    expect(page().querySelector('#scam-description')).not.toBeNull();
  });

  it('existingReport_shownInsteadOfForm', async () => {
    await open(letter(), [scamReport({ status: 'CONFIRMED', decidedAt: '2026-10-21T06:30:00Z' })]);

    expect(page().textContent).toContain('Confirmed by an admin');
    expect(page().querySelector('#scam-description')).toBeNull();
  });

  it('descriptionRequired_noRequest', async () => {
    await open();

    await send('   ');
    await settle();

    http.expectNone((request) => request.method === 'POST');
    expect(page().querySelector('#scam-description-error')?.textContent).toContain(
      'Describe what happened',
    );
  });

  it.each(['abc', '-5', '100000001', '12.5'])('amount_%s_refused', async (amount) => {
    await open();

    await send(FAKE_STORY, amount);
    await settle();

    http.expectNone((request) => request.method === 'POST');
    expect(page().querySelector('#scam-amount-error')).not.toBeNull();
  });

  it('notAnalysedYet_noForm', async () => {
    await open(letter('QUEUED'));

    expect(page().querySelector('#scam-description')).toBeNull();
    expect(page().textContent).toContain('once its check has finished');
  });

  it('copy_neverClaimsSafety', async () => {
    await open(letter(), [scamReport({ status: 'REJECTED', decidedAt: '2026-10-21T06:30:00Z' })]);

    const shown = (page().textContent ?? '').replace(/\(not\s+verified\)/g, '');
    expect(shown).not.toMatch(/\b(verified|safe|safely|legit|legitimate|guaranteed)\b/i);
  });
});
