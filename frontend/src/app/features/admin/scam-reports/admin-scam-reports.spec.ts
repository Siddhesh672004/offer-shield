import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { claimsSafety, fakeEvidence, fakeMarker } from '../../../../testing/staff-fakes';
import { Announcer } from '../../../core/a11y/announcer';
import { AdminScamReports } from './admin-scam-reports';
import { AdminScamReport } from './scam-reports-api';

const STORY =
  'They asked me to pay Rs. 3,000 for a <b>laptop kit</b>.\nThen they stopped answering.';

function report(overrides: Partial<AdminScamReport> = {}): AdminScamReport {
  return {
    id: 's1',
    submissionId: 'sub-1',
    status: 'OPEN',
    description: STORY,
    amountAsked: 3000,
    createdAt: '2026-10-19T06:00:00Z',
    decidedAt: null,
    version: 5,
    pdfAvailable: true,
    evidence: fakeEvidence(),
    ...overrides,
  };
}

const FIRST = report();
const SECOND = report({
  id: 's2',
  submissionId: 'sub-2',
  description: 'A fake recruiter wanted a training fee.',
  amountAsked: null,
  createdAt: '2026-10-19T08:00:00Z',
  version: 0,
  evidence: fakeEvidence({
    band: 'MEDIUM',
    score: 35,
    markers: [fakeMarker({ reporterCount: 1 })],
  }),
});

describe('AdminScamReports', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<AdminScamReports>;

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

  async function render(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve));
    await new Promise((resolve) => setTimeout(resolve));
  }

  async function settle(): Promise<void> {
    await render();
    await fixture.whenStable();
  }

  function listRequest(status = 'OPEN'): TestRequest {
    return http.expectOne(
      (request) =>
        request.method === 'GET' &&
        request.url === '/api/admin/scam-reports' &&
        request.params.get('status') === status,
    );
  }

  async function open(list: AdminScamReport[] = [FIRST, SECOND], status?: string): Promise<void> {
    fixture = TestBed.createComponent(AdminScamReports);
    if (status) {
      fixture.componentRef.setInput('status', status);
    }
    await render();
    listRequest(status === 'CONFIRMED' || status === 'REJECTED' ? status : 'OPEN').flush(list);
    await settle();
  }

  function card(id: string): HTMLElement {
    return page().querySelector<HTMLElement>(`#report-${id}`)!;
  }

  function button(root: ParentNode, label: string): HTMLButtonElement | undefined {
    return Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find((item) =>
      item.textContent?.replace(/\s+/g, ' ').trim().startsWith(label),
    );
  }

  function post(): TestRequest {
    return http.expectOne((request) => request.method === 'POST');
  }

  function problem(status: number, errorCode: string) {
    return [
      { status, errorCode, detail: 'Fake detail.' },
      { status, statusText: 'Error' },
    ] as const;
  }

  it('open_listsReportsWithPlainTextDescriptionAmountAndEvidence', async () => {
    await open();

    const first = card('s1');
    expect(first.querySelector('.report__description')?.textContent).toBe(STORY);
    expect(first.querySelector('b')).toBeNull();
    expect(first.textContent).toContain('₹3,000');
    expect(first.textContent).toContain('Strong scam indicators. Do not pay anything.');
    expect(first.textContent).toContain('pay a refundable security deposit');
    expect(card('s2').textContent).toContain('None given');
    const current = page().querySelector('nav a[aria-current="page"]');
    expect(current?.textContent?.trim()).toBe('Open');
    expect(button(first, 'Confirm scam')).toBeDefined();
  });

  it('statusFilter_requestsThatStatus_decidedHaveNoDecisionButtons', async () => {
    await open(
      [report({ status: 'CONFIRMED', decidedAt: '2026-10-20T05:00:00Z', version: 6 })],
      'CONFIRMED',
    );

    expect(page().querySelector('nav a[aria-current="page"]')?.textContent?.trim()).toBe(
      'Confirmed',
    );
    expect(button(card('s1'), 'Confirm scam')).toBeUndefined();
    expect(button(card('s1'), 'Reject')).toBeUndefined();
    expect(card('s1').textContent).toContain('20 Oct 2026');
  });

  it('unknownStatus_fallsBackToOpen', async () => {
    await open([FIRST], 'EVERYTHING');

    expect(page().querySelector('nav a[aria-current="page"]')?.textContent?.trim()).toBe('Open');
  });

  it('wipedDescriptionAndDeletedEvidence_explained', async () => {
    await open([report({ description: null, evidence: null, pdfAvailable: false })]);

    const first = card('s1');
    expect(first.textContent).toContain('description was deleted 30 days after the decision');
    expect(first.textContent).toContain("This letter's report was deleted after 30 days");
    expect(first.textContent).toContain('No PDF stored for this letter');
  });

  it('unverifiedMarkers_neverShownAsConfirmed', async () => {
    await open();

    const second = card('s2');
    expect(second.querySelector('.report-chip--confirmed')).toBeNull();
    expect(second.querySelector('.report-chip--reported')?.textContent).toContain(
      'Reported as a scam by 1 user (not verified)',
    );
    expect(second.textContent).toContain('claims until you confirm them');
  });

  it('confirm_sendsIfMatchVersion_refreshes_announces_focusesNext', async () => {
    await open();

    button(card('s1'), 'Confirm scam')!.click();
    const request = post();
    expect(request.request.url).toBe('/api/admin/scam-reports/s1/decision');
    expect(request.request.headers.get('If-Match')).toBe('"5"');
    expect(request.request.body).toEqual({ decision: 'CONFIRM' });
    request.flush(report({ status: 'CONFIRMED', version: 6 }));
    await settle();
    listRequest().flush([SECOND]);
    await settle();

    expect(page().querySelector('#report-s1')).toBeNull();
    expect(announced()).toContain('Report confirmed');
    expect(document.activeElement?.id).toBe('report-s2-heading');
  });

  async function confirmFirst(answer: AdminScamReport): Promise<string> {
    await open();
    button(card('s1'), 'Confirm scam')!.click();
    post().flush(answer);
    await settle();
    listRequest().flush([SECOND]);
    await settle();
    return page().querySelector('#reports-outcome')?.textContent ?? '';
  }

  it('confirm_zeroMarkersRecorded_saysSo_notThatLettersAreFlagged', async () => {
    const notice = await confirmFirst(
      report({ status: 'CONFIRMED', version: 6, markersRecorded: 0 }),
    );

    expect(notice).toContain('Report confirmed.');
    expect(notice).toContain('No scam markers could be recorded for this letter');
    expect(notice).not.toContain('now show');
    expect(notice).toContain('The reporter will get an email.');
    expect(announced()).toContain('No scam markers could be recorded for this letter');
    expect(claimsSafety(notice)).toBe(false);
  });

  it.each([[3], [undefined]])(
    'confirm_markersRecorded_%s_saysMatchingLettersShowConfirmed',
    async (count) => {
      const answer = report({ status: 'CONFIRMED', version: 6 });
      const notice = await confirmFirst(
        count === undefined ? answer : { ...answer, markersRecorded: count },
      );

      expect(notice).toContain('now show “Confirmed scam”');
      expect(notice).not.toContain('No scam markers');
    },
  );

  it('reject_zeroMarkersRecorded_keepsTheRejectMessage', async () => {
    await open();

    button(card('s1'), 'Reject')!.click();
    await settle();
    button(card('s1').querySelector('app-confirm-step')!, 'Reject report')!.click();
    post().flush(report({ status: 'REJECTED', version: 6, markersRecorded: 0 }));
    await settle();
    listRequest().flush([SECOND]);
    await settle();

    const notice = page().querySelector('#reports-outcome')?.textContent ?? '';
    expect(notice).toContain('Report rejected');
    expect(notice).not.toContain('No scam markers');
  });

  it('evidence_phoneAndUpiMarkers_showMaskedHintAndWhoSuppliedIt', async () => {
    await open([
      report({
        evidence: fakeEvidence({
          markers: [
            fakeMarker({
              id: 'm1',
              type: 'PHONE',
              domain: null,
              source: 'TYPED',
              hint: '••••1234',
            }),
            fakeMarker({
              id: 'm2',
              type: 'UPI',
              domain: null,
              source: 'LETTER',
              hint: '••••ar@okaxis',
            }),
          ],
        }),
      }),
    ]);

    const text = card('s1').textContent ?? '';
    expect(text).toContain('••••1234');
    expect(text).toContain('typed by the student');
    expect(text).toContain('••••ar@okaxis');
    expect(text).toContain('in the letter');
  });

  it('decision_inFlight_disablesDecisionButtons', async () => {
    await open();

    button(card('s1'), 'Confirm scam')!.click();
    await fixture.whenStable();

    expect(button(card('s1'), 'Confirm scam')!.disabled).toBe(true);
    expect(button(card('s2'), 'Confirm scam')!.disabled).toBe(true);
    expect(button(card('s2'), 'Reject')!.disabled).toBe(true);
    post().flush(report({ status: 'CONFIRMED', version: 6 }));
    await settle();
    listRequest().flush([SECOND]);
    await settle();
  });

  it('reject_needsConfirmStep_thenSendsReject', async () => {
    await open();

    button(card('s1'), 'Reject')!.click();
    await settle();
    http.expectNone((request) => request.method === 'POST');
    const confirm = card('s1').querySelector('app-confirm-step')!;
    expect(confirm.textContent).toContain("can't be undone");
    expect(document.activeElement?.textContent?.trim()).toBe('Reject report');

    button(confirm, 'Reject report')!.click();
    const request = post();
    expect(request.request.headers.get('If-Match')).toBe('"5"');
    expect(request.request.body).toEqual({ decision: 'REJECT' });
    request.flush(report({ status: 'REJECTED', version: 6 }));
    await settle();
    listRequest().flush([SECOND]);
    await settle();

    expect(announced()).toContain('Report rejected');
  });

  it('reject_cancel_keepsReport_focusReturns', async () => {
    await open();

    button(card('s1'), 'Reject')!.click();
    await settle();
    button(card('s1').querySelector('app-confirm-step')!, 'Cancel')!.click();
    await settle();

    expect(card('s1').querySelector('app-confirm-step')).toBeNull();
    expect(document.activeElement?.textContent).toContain('Reject');
  });

  it('decision_409_someoneElseDecided_refreshes', async () => {
    await open();

    button(card('s1'), 'Confirm scam')!.click();
    post().flush(...problem(409, 'CONFLICT'));
    await settle();
    listRequest().flush([SECOND]);
    await settle();

    const notice = page().querySelector('#reports-outcome')!;
    expect(notice.textContent).toContain('Someone else decided this report first');
    expect(notice.textContent).toContain('refreshed');
    expect(announced()).toContain('Someone else decided this report first');
    expect(page().querySelector('#report-s1')).toBeNull();
    expect(document.activeElement).toBe(notice);
  });

  it('decision_409_alreadyDecided_handledTheSameWay', async () => {
    await open();

    button(card('s1'), 'Confirm scam')!.click();
    post().flush(...problem(409, 'ALREADY_DECIDED'));
    await settle();
    listRequest().flush([SECOND]);
    await settle();

    expect(page().querySelector('#reports-outcome')?.textContent).toContain(
      'Someone else decided this report first',
    );
  });

  it('decision_409_evidenceDeleted_explainsRejectStillPossible', async () => {
    await open();

    button(card('s1'), 'Confirm scam')!.click();
    post().flush(...problem(409, 'EVIDENCE_DELETED'));
    await settle();
    listRequest().flush([report({ evidence: null }), SECOND]);
    await settle();

    const notice = page().querySelector('#reports-outcome')!.textContent;
    expect(notice).toContain('deleted after 30 days');
    expect(notice).toContain('You can still reject it');
  });

  it('revert_confirmStep_sendsMarkerVersion_refreshes', async () => {
    await open([report({ status: 'CONFIRMED', decidedAt: '2026-10-20T05:00:00Z' })], 'CONFIRMED');

    button(card('s1'), 'Revert')!.click();
    await settle();
    http.expectNone((request) => request.method === 'POST');
    button(card('s1').querySelector('app-confirm-step')!, 'Revert marker')!.click();
    const request = post();
    expect(request.request.url).toBe(
      '/api/admin/indicators/00000000-0000-0000-0000-0000000000a2/revert',
    );
    expect(request.request.headers.get('If-Match')).toBe('"7"');
    request.flush(fakeMarker({ id: '00000000-0000-0000-0000-0000000000a2', version: 8 }));
    await settle();
    listRequest('CONFIRMED').flush([
      report({ status: 'CONFIRMED', evidence: fakeEvidence({ markers: [fakeMarker()] }) }),
    ]);
    await settle();

    expect(announced()).toContain('Marker reverted');
    expect(card('s1').querySelector('.report-chip--confirmed')).toBeNull();
    expect(document.activeElement?.id).toBe('report-s1-heading');
  });

  it('revert_409_someoneElseChangedIt_refreshes', async () => {
    await open();

    button(card('s1'), 'Revert')!.click();
    await settle();
    button(card('s1').querySelector('app-confirm-step')!, 'Revert marker')!.click();
    post().flush(...problem(409, 'CONFLICT'));
    await settle();
    listRequest().flush([FIRST, SECOND]);
    await settle();

    expect(page().querySelector('#reports-outcome')?.textContent).toContain(
      'Someone else changed this marker first',
    );
  });

  it('revert_409_notConfirmed_explains', async () => {
    await open();

    button(card('s1'), 'Revert')!.click();
    await settle();
    button(card('s1').querySelector('app-confirm-step')!, 'Revert marker')!.click();
    post().flush(...problem(409, 'NOT_CONFIRMED'));
    await settle();
    listRequest().flush([FIRST, SECOND]);
    await settle();

    expect(page().querySelector('#reports-outcome')?.textContent).toContain('no longer confirmed');
  });

  it('pdfLink_usesAdminEndpoint', async () => {
    await open();

    button(card('s1'), 'Get the PDF link')!.click();
    http
      .expectOne('/api/admin/scam-reports/s1/pdf-link')
      .flush({ url: 'https://res.cloudinary.example/fake.pdf', expiresAt: '2026-10-20T07:05:00Z' });
    await settle();

    const link = card('s1').querySelector<HTMLAnchorElement>('a[target="_blank"]')!;
    expect(link.getAttribute('rel')).toBe('noopener noreferrer');
  });

  it('otherStatusTab_clearsTheMessage', async () => {
    await open();
    button(card('s1'), 'Confirm scam')!.click();
    post().flush(...problem(409, 'CONFLICT'));
    await settle();
    listRequest().flush([SECOND]);
    await settle();
    expect(page().querySelector('#reports-outcome')).not.toBeNull();

    fixture.componentRef.setInput('status', 'REJECTED');
    await render();
    listRequest('REJECTED').flush([]);
    await settle();

    expect(page().querySelector('#reports-outcome')).toBeNull();
  });

  it('emptyOpen_showsNothingToReview', async () => {
    await open([]);

    expect(page().textContent).toContain('No open reports');
  });

  it('copy_neverClaimsSafety', async () => {
    await open();

    expect(claimsSafety(page().textContent ?? '')).toBe(false);
  });
});
