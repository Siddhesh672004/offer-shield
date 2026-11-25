import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { claimsSafety, fakeCase, fakeEvidence, fakeMarker } from '../../../../testing/staff-fakes';
import { Announcer } from '../../../core/a11y/announcer';
import { ReviewCase } from './review-case';
import { ReviewCaseDetail } from './reviews-api';

const NOTE = 'This company has no such opening.\nDo not pay the registration fee.';

describe('ReviewCase', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<ReviewCase>;

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

  async function open(
    body: ReviewCaseDetail | object = fakeCase(),
    status?: { status: number; statusText: string },
  ): Promise<void> {
    fixture = TestBed.createComponent(ReviewCase);
    fixture.componentRef.setInput('id', 'c1');
    await render();
    http.expectOne('/api/reviews/c1').flush(body, status);
    await settle();
  }

  function button(label: string): HTMLButtonElement {
    const found = Array.from(page().querySelectorAll<HTMLButtonElement>('button')).find((item) =>
      item.textContent?.trim().startsWith(label),
    );
    if (!found) {
      throw new Error(`No "${label}" button`);
    }
    return found;
  }

  async function decide(decision: string | null, note = ''): Promise<void> {
    if (decision) {
      page().querySelector<HTMLInputElement>(`input[type="radio"][value="${decision}"]`)!.click();
    }
    const textarea = page().querySelector<HTMLTextAreaElement>('#decision-note')!;
    textarea.value = note;
    textarea.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    button('Send decision').click();
    await fixture.whenStable();
  }

  function decisionRequest() {
    return http.expectOne((request) => request.method === 'POST');
  }

  function conflict(errorCode: string) {
    return [
      { status: 409, errorCode, detail: 'Fake detail.' },
      { status: 409, statusText: 'Conflict' },
    ] as const;
  }

  it('evidence_showsStudentBandSignalsAndMarkers', async () => {
    await open();

    const text = page().textContent ?? '';
    expect(page().querySelector('h1')?.textContent).toContain('Review an offer');
    expect(text).toContain('Asha Test');
    expect(text).toContain('asha.test@example.com');
    expect(text).toContain('Strong scam indicators. Do not pay anything.');
    expect(text).toContain('pay a refundable security deposit of Rs. 5,000 to [phone]');
    expect(text).toContain('AI-extracted');
    expect(page().querySelector('.report-chip--confirmed')?.textContent).toContain(
      'Confirmed scam',
    );
    expect(page().querySelector('.report-chip--reported')?.textContent).toContain(
      'Reported as a scam by 2 users (not verified)',
    );
    expect(page().querySelector('a[href="/tpo/reviews"]')).not.toBeNull();
  });

  it('pdfLink_usesReviewEndpoint_opensInNewTabWithNoopener', async () => {
    await open();

    button('Get the PDF link').click();
    http
      .expectOne('/api/reviews/c1/pdf-link')
      .flush({ url: 'https://res.cloudinary.example/fake.pdf', expiresAt: '2026-10-20T07:05:00Z' });
    await settle();

    const link = page().querySelector<HTMLAnchorElement>('a[target="_blank"]')!;
    expect(link.getAttribute('href')).toBe('https://res.cloudinary.example/fake.pdf');
    expect(link.getAttribute('rel')).toBe('noopener noreferrer');
  });

  it('pdfLink_404_saysNoPdfStored', async () => {
    await open();

    button('Get the PDF link').click();
    http
      .expectOne('/api/reviews/c1/pdf-link')
      .flush(
        { status: 404, errorCode: 'PDF_NOT_AVAILABLE', detail: 'x' },
        { status: 404, statusText: 'Not Found' },
      );
    await settle();

    expect(page().textContent).toContain('No PDF stored for this letter');
  });

  it('decide_sendsIfMatchVersionAndNote_showsDecision', async () => {
    await open();

    await decide('CONFIRMED_SCAM', NOTE);
    const post = decisionRequest();
    expect(post.request.url).toBe('/api/reviews/c1/decision');
    expect(post.request.headers.get('If-Match')).toBe('"2"');
    expect(post.request.body).toEqual({ decision: 'CONFIRMED_SCAM', note: NOTE });
    expect(button('Send decision').disabled).toBe(true);
    post.flush(
      fakeCase({
        status: 'CONFIRMED_SCAM',
        note: NOTE,
        decidedAt: '2026-10-20T08:00:00Z',
        version: 3,
      }),
    );
    await settle();

    expect(page().querySelector('input[type="radio"]')).toBeNull();
    expect(page().textContent).toContain('Marked as a scam');
    expect(page().querySelector('.decided__note')?.textContent).toBe(NOTE);
    expect(announced()).toContain('Decision sent');
    expect(document.activeElement?.id).toBe('decided-heading');
  });

  it('confirmScam_zeroMarkersRecorded_saysNoMarkersCouldBeRecorded', async () => {
    await open();

    await decide('CONFIRMED_SCAM');
    decisionRequest().flush(
      fakeCase({
        status: 'CONFIRMED_SCAM',
        decidedAt: '2026-10-20T08:00:00Z',
        version: 3,
        markersRecorded: 0,
      }),
    );
    await settle();

    const notice = page().querySelector('#case-outcome')!;
    expect(notice.textContent).toContain('No scam markers could be recorded for this letter');
    expect(claimsSafety(notice.textContent ?? '')).toBe(false);
    expect(announced()).toContain('Decision sent: Marked as a scam.');
    expect(announced()).toContain('No scam markers could be recorded for this letter');
    expect(page().querySelector('#decided-heading')).not.toBeNull();
    expect(document.activeElement?.id).toBe('decided-heading');
  });

  it.each([
    ['CONFIRMED_SCAM', 2],
    ['CONFIRMED_SCAM', undefined],
    ['CONFIRMED_GENUINE', 0],
    ['INCONCLUSIVE', 0],
  ] as const)('decision_%s_markersRecorded_%s_noMarkerNote', async (decision, count) => {
    await open();

    await decide(decision);
    const answer = fakeCase({ status: decision, decidedAt: '2026-10-20T08:00:00Z', version: 3 });
    decisionRequest().flush(count === undefined ? answer : { ...answer, markersRecorded: count });
    await settle();

    expect(page().querySelector('#case-outcome')).toBeNull();
    expect(page().textContent).not.toContain('No scam markers could be recorded');
    expect(announced()).not.toContain('No scam markers');
  });

  it('evidence_phoneAndUpiMarkers_showMaskedHintAndWhoSuppliedIt', async () => {
    await open(
      fakeCase({
        evidence: fakeEvidence({
          markers: [
            fakeMarker({
              id: 'm1',
              type: 'PHONE',
              domain: null,
              source: 'LETTER',
              hint: '••••1234',
            }),
            fakeMarker({
              id: 'm2',
              type: 'UPI',
              domain: null,
              source: 'TYPED',
              hint: '••••ar@okaxis',
            }),
          ],
        }),
      }),
    );

    const rows = Array.from(page().querySelectorAll('.marker')).map((row) => row.textContent ?? '');
    expect(rows[0]).toContain('••••1234');
    expect(rows[0]).toContain('in the letter');
    expect(rows[1]).toContain('••••ar@okaxis');
    expect(rows[1]).toContain('typed by the student');
  });

  it('blankNote_sentAsNull', async () => {
    await open();

    await decide('INCONCLUSIVE', '   ');
    const post = decisionRequest();
    expect(post.request.body).toEqual({ decision: 'INCONCLUSIVE', note: null });
    post.flush(fakeCase({ status: 'INCONCLUSIVE', decidedAt: '2026-10-20T08:00:00Z', version: 3 }));
    await settle();
  });

  it('noChoice_showsError_noRequest', async () => {
    await open();

    await decide(null);
    await settle();

    http.expectNone((request) => request.method === 'POST');
    expect(page().textContent).toContain('Choose a decision');
  });

  it('409_someoneElseDecided_refreshesCase', async () => {
    await open();

    await decide('CONFIRMED_GENUINE');
    decisionRequest().flush(...conflict('CONFLICT'));
    await settle();
    http
      .expectOne('/api/reviews/c1')
      .flush(fakeCase({ status: 'INCONCLUSIVE', decidedAt: '2026-10-20T08:00:00Z', version: 3 }));
    await settle();

    const notice = page().querySelector('#case-outcome')!;
    expect(notice.textContent).toContain('Someone else decided this case first');
    expect(notice.textContent).toContain('refreshed');
    expect(announced()).toContain('Someone else decided this case first');
    expect(page().textContent).toContain('No conclusion');
    expect(page().querySelector('input[type="radio"]')).toBeNull();
    expect(document.activeElement).toBe(notice);
  });

  it('409_alreadyDecided_handledTheSameWay', async () => {
    await open();

    await decide('CONFIRMED_SCAM');
    decisionRequest().flush(...conflict('ALREADY_DECIDED'));
    await settle();
    http
      .expectOne('/api/reviews/c1')
      .flush(fakeCase({ status: 'CONFIRMED_SCAM', decidedAt: '2026-10-20T08:00:00Z', version: 3 }));
    await settle();

    expect(page().querySelector('#case-outcome')?.textContent).toContain(
      'Someone else decided this case first',
    );
  });

  it('409_evidenceDeleted_explainsOtherChoices_keepsForm', async () => {
    await open();

    await decide('CONFIRMED_SCAM');
    decisionRequest().flush(...conflict('EVIDENCE_DELETED'));
    await settle();

    expect(page().querySelector('#case-outcome')?.textContent).toContain('deleted after 30 days');
    expect(page().querySelector('input[type="radio"]')).not.toBeNull();
    expect(button('Send decision').disabled).toBe(false);
  });

  it('decidedCase_showsNoteAsPlainText_noForm', async () => {
    await open(
      fakeCase({
        status: 'CONFIRMED_GENUINE',
        note: '<b>Looks fine</b>',
        decidedAt: '2026-10-20T08:00:00Z',
      }),
    );

    expect(page().textContent).toContain('Marked as genuine');
    expect(page().querySelector('.decided__note')?.textContent).toBe('<b>Looks fine</b>');
    expect(page().querySelector('b')).toBeNull();
    expect(page().querySelector('form')).toBeNull();
  });

  it('evidenceDeleted_explains_stillDecidable', async () => {
    await open(fakeCase({ evidence: null, pdfAvailable: false }));

    expect(page().textContent).toContain('deleted after 30 days');
    expect(page().textContent).toContain('No PDF stored for this letter');
    expect(page().querySelector('input[type="radio"]')).not.toBeNull();
  });

  it('otherCase_clearsMessageAndChoice', async () => {
    await open();
    await decide('CONFIRMED_SCAM');
    decisionRequest().flush(...conflict('EVIDENCE_DELETED'));
    await settle();
    expect(page().querySelector('#case-outcome')).not.toBeNull();

    fixture.componentRef.setInput('id', 'c2');
    await render();
    http.expectOne('/api/reviews/c2').flush(fakeCase({ id: 'c2', version: 0 }));
    await settle();

    expect(page().querySelector('#case-outcome')).toBeNull();
    expect(page().querySelector<HTMLInputElement>('input[value="CONFIRMED_SCAM"]')!.checked).toBe(
      false,
    );
  });

  it('notFound_explains', async () => {
    await open(
      { status: 404, errorCode: 'NOT_FOUND', detail: 'x' },
      { status: 404, statusText: 'Not Found' },
    );

    expect(page().textContent).toContain("We couldn't find this case");
    expect(page().querySelector('a[href="/tpo/reviews"]')).not.toBeNull();
  });

  it('copy_neverClaimsSafety', async () => {
    await open();

    expect(claimsSafety(page().textContent ?? '')).toBe(false);
  });
});
