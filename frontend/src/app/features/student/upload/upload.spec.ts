import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { fakeStudent } from '../../../../testing/fakes';
import { answerSessionProbe } from '../../../../testing/session-helpers';
import { Announcer } from '../../../core/a11y/announcer';
import { Upload } from './upload';

/** A fictional letter (CLAUDE.md: test data is fake). */
const FAKE_LETTER =
  'Dear Candidate, Fake Northwind Careers is pleased to offer you the role of trainee analyst. ' +
  'Pay a refundable deposit of Rs. 5,000 to confirm your seat.';

function problem(status: number, errorCode: string, detail = 'x', statusText = 'Error') {
  return { body: { status, errorCode, detail }, options: { status, statusText } };
}

describe('Upload', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<Upload>;

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

  async function open(): Promise<void> {
    await answerSessionProbe(fakeStudent);
    fixture = TestBed.createComponent(Upload);
    await fixture.whenStable();
  }

  async function settle(): Promise<void> {
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    await fixture.whenStable();
  }

  async function choose(mode: 'pdf' | 'text'): Promise<void> {
    page().querySelector<HTMLInputElement>(`input[type="radio"][value="${mode}"]`)!.click();
    await fixture.whenStable();
  }

  function type(selector: string, value: string): void {
    const control = page().querySelector<HTMLInputElement | HTMLTextAreaElement>(selector)!;
    control.value = value;
    control.dispatchEvent(new Event('input'));
  }

  async function attach(file: File): Promise<void> {
    const input = page().querySelector<HTMLInputElement>('#letter-file')!;
    Object.defineProperty(input, 'files', { value: [file], configurable: true });
    input.dispatchEvent(new Event('change'));
    await fixture.whenStable();
  }

  function pdf(name = 'offer.pdf', size = 2048): File {
    const file = new File(['%PDF-1.7 fake'], name, { type: 'application/pdf' });
    Object.defineProperty(file, 'size', { value: size });
    return file;
  }

  async function submit(): Promise<void> {
    await fixture.whenStable();
    page().querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    await settle();
  }

  async function sendText(text = FAKE_LETTER): Promise<TestRequest> {
    await choose('text');
    type('#letter-text', text);
    await submit();
    return http.expectOne('/api/submissions');
  }

  it('textUpload_postsMultipartWithKey_thenOpensReport', async () => {
    await open();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);

    await choose('text');
    type('#letter-text', FAKE_LETTER);
    type('#sender-email', 'hr@northwind-careers.example');
    type('#recruiter-phone', '+91 90000 00000');
    type('#upi-id', 'fake.recruiter@okbank');
    await submit();

    const post = http.expectOne('/api/submissions');
    expect(post.request.method).toBe('POST');
    const body = post.request.body as FormData;
    expect(body.get('text')).toBe(FAKE_LETTER);
    expect(body.get('file')).toBeNull();
    expect(body.get('senderEmail')).toBe('hr@northwind-careers.example');
    expect(body.get('recruiterPhone')).toBe('+91 90000 00000');
    expect(body.get('upiId')).toBe('fake.recruiter@okbank');
    expect(post.request.headers.get('Idempotency-Key')).toMatch(/^[A-Za-z0-9_.:-]{8,100}$/);

    post.flush({ submissionId: 'sub-1' }, { status: 202, statusText: 'Accepted' });
    await settle();

    expect(navigate).toHaveBeenCalledWith('/app/submissions/sub-1');
    expect(announced()).toContain('Checking your letter');
  });

  it('pdfUpload_sendsFileOnly_blankOptionalFieldsLeftOut', async () => {
    await open();
    vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);

    await choose('pdf');
    const file = pdf();
    await attach(file);
    await submit();

    const post = http.expectOne('/api/submissions');
    const body = post.request.body as FormData;
    expect(body.get('file')).toBeInstanceOf(File);
    expect(body.get('text')).toBeNull();
    expect(body.has('senderEmail')).toBe(false);
    expect(body.has('recruiterPhone')).toBe(false);
    expect(body.has('upiId')).toBe(false);
    post.flush({ submissionId: 'sub-2' }, { status: 202, statusText: 'Accepted' });
  });

  it('retry_reusesKey', async () => {
    await open();

    const first = await sendText();
    const key = first.request.headers.get('Idempotency-Key');
    first.error(new ProgressEvent('error'), { status: 0, statusText: 'Unknown Error' });
    await settle();
    expect(page().textContent).toContain("Couldn't reach OfferShield");

    await submit();
    const second = http.expectOne('/api/submissions');
    expect(second.request.headers.get('Idempotency-Key')).toBe(key);
    second.flush(
      problem(503, 'STORAGE_UNAVAILABLE').body,
      problem(503, 'STORAGE_UNAVAILABLE').options,
    );
    await settle();

    await submit();
    expect(http.expectOne('/api/submissions').request.headers.get('Idempotency-Key')).toBe(key);
  });

  it('changedLetter_getsNewKey', async () => {
    await open();

    const first = await sendText();
    const key = first.request.headers.get('Idempotency-Key');
    first.flush(problem(503, 'SERVER_BUSY').body, problem(503, 'SERVER_BUSY').options);
    await settle();

    type('#letter-text', `${FAKE_LETTER} Join within 24 hours.`);
    await submit();

    const second = http.expectOne('/api/submissions');
    expect(second.request.headers.get('Idempotency-Key')).not.toBe(key);
    second.flush({ submissionId: 'sub-3' }, { status: 202, statusText: 'Accepted' });
  });

  it('doubleSubmit_sendsOnce', async () => {
    await open();
    await choose('text');
    type('#letter-text', FAKE_LETTER);
    await fixture.whenStable();

    const button = page().querySelector<HTMLButtonElement>('button[type="submit"]')!;
    button.click();
    button.click();
    await settle();

    const posts = http.match('/api/submissions');
    expect(posts).toHaveLength(1);
    expect(button.disabled).toBe(true);
    posts[0].flush({ submissionId: 'sub-4' }, { status: 202, statusText: 'Accepted' });
  });

  it('422_shown', async () => {
    await open();
    await choose('pdf');
    await attach(pdf());
    await submit();

    const reply = problem(422, 'TOO_MANY_PAGES', 'The PDF has more than 10 pages.');
    http.expectOne('/api/submissions').flush(reply.body, reply.options);
    await settle();

    const error = page().querySelector('#letter-file-error');
    expect(error?.textContent).toContain('more than 10 pages');
    expect(page().querySelector('#letter-file')?.getAttribute('aria-invalid')).toBe('true');
    expect(announced()).toContain('more than 10 pages');
  });

  it.each([
    ['TEXT_TOO_SHORT', 'paste the text instead'],
    ['PDF_ENCRYPTED', 'password-protected'],
    ['PDF_UNREADABLE', "couldn't read this PDF"],
    ['PDF_TIMEOUT', 'took too long'],
    ['PDF_TOO_COMPLEX', 'too large or complex'],
    ['TEXT_TOO_LONG', 'over 50,000 characters'],
  ])('422_%s_explained', async (code, words) => {
    await open();
    await choose('pdf');
    await attach(pdf());
    await submit();

    const reply = problem(422, code);
    http.expectOne('/api/submissions').flush(reply.body, reply.options);
    await settle();

    expect(page().querySelector('#letter-file-error')?.textContent?.toLowerCase()).toContain(
      words.toLowerCase(),
    );
  });

  it('422_offersPasteInstead', async () => {
    await open();
    await choose('pdf');
    await attach(pdf());
    await submit();
    const reply = problem(422, 'TEXT_TOO_SHORT');
    http.expectOne('/api/submissions').flush(reply.body, reply.options);
    await settle();

    page().querySelector<HTMLButtonElement>('.paste-instead')!.click();
    await fixture.whenStable();

    expect(page().querySelector('#letter-text')).not.toBeNull();
  });

  it('413_withoutBody_shownAsTooLarge', async () => {
    await open();
    await choose('pdf');
    await attach(pdf());
    await submit();

    // Caddy's request_body limit answers 413 with no problem body.
    http
      .expectOne('/api/submissions')
      .flush('', { status: 413, statusText: 'Request Entity Too Large' });
    await settle();

    expect(page().querySelector('#letter-file-error')?.textContent).toContain('larger than 5 MB');
  });

  it('415_shownAsNotPdf', async () => {
    await open();
    await choose('pdf');
    await attach(pdf('offer.pdf'));
    await submit();

    const reply = problem(415, 'UNSUPPORTED_FILE_TYPE', 'Only PDF files are accepted.');
    http.expectOne('/api/submissions').flush(reply.body, reply.options);
    await settle();

    expect(page().querySelector('#letter-file-error')?.textContent).toContain("isn't a PDF");
  });

  it('429_showsRetryAfter', async () => {
    await open();

    const post = await sendText();
    post.flush(
      { status: 429, errorCode: 'UPLOAD_LIMIT', detail: 'x' },
      { status: 429, statusText: 'Too Many Requests', headers: { 'Retry-After': '420' } },
    );
    await settle();

    const alert = page().querySelector('.upload-error');
    expect(alert?.textContent).toContain('5 letters every 10 minutes');
    expect(alert?.textContent).toContain('about 7 minutes');
    expect(announced()).toContain('about 7 minutes');
  });

  it('429_uploadAttemptLimit_saysTooManyUnreadableLetters_withTheWait', async () => {
    await open();
    await choose('pdf');
    await attach(pdf());
    await submit();

    http.expectOne('/api/submissions').flush(
      {
        status: 429,
        errorCode: 'UPLOAD_ATTEMPT_LIMIT',
        detail: "Too many letters that couldn't be read. Try again in a few minutes.",
      },
      { status: 429, statusText: 'Too Many Requests', headers: { 'Retry-After': '60' } },
    );
    await settle();

    const alert = page().querySelector('.upload-error');
    expect(alert?.textContent).toContain("Too many letters that couldn't be read");
    expect(alert?.textContent).toContain('about 1 minute');
    expect(alert?.textContent).toContain('paste');
    expect(alert?.textContent).not.toContain('5 letters every 10 minutes');
    expect(page().querySelector('#letter-file-error')).toBeNull();
    expect(announced()).toContain("Too many letters that couldn't be read");
  });

  it('409_uploadInProgress_saysWaitForIt_notUnderTheFile', async () => {
    await open();
    await choose('pdf');
    await attach(pdf());
    await submit();

    const reply = problem(
      409,
      'UPLOAD_IN_PROGRESS',
      'Another letter is still being read. Wait for it to finish.',
      'Conflict',
    );
    http.expectOne('/api/submissions').flush(reply.body, reply.options);
    await settle();

    expect(page().querySelector('.upload-error')?.textContent).toContain(
      'Another letter is still being read. Wait for it to finish.',
    );
    expect(page().querySelector('#letter-file-error')).toBeNull();
    expect(announced()).toContain('Another letter is still being read');
  });

  it('navigationFails_afterAccepted_linksToTheReport_noSendError', async () => {
    await open();
    vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockRejectedValue(
      new TypeError('Failed to fetch dynamically imported module: /chunk-fake.js'),
    );

    const post = await sendText();
    post.flush({ submissionId: 'sub-1' }, { status: 202, statusText: 'Accepted' });
    await settle();

    expect(page().querySelector('.upload-error')).toBeNull();
    expect(page().textContent).not.toContain("Couldn't send your letter");
    const link = page().querySelector<HTMLAnchorElement>('.upload-sent a');
    expect(link?.getAttribute('href')).toBe('/app/submissions/sub-1');
    expect(page().querySelector('.upload-sent')?.textContent).toContain('Letter sent');
    expect(announced()).toContain('Letter sent');
    expect(document.activeElement).toBe(link);
    expect(page().querySelector<HTMLButtonElement>('button[type="submit"]')!.disabled).toBe(false);
  });

  it('navigationCancelled_afterAccepted_linksToTheReport', async () => {
    await open();
    vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(false);

    const post = await sendText();
    post.flush({ submissionId: 'sub-5' }, { status: 202, statusText: 'Accepted' });
    await settle();

    expect(page().querySelector('.upload-error')).toBeNull();
    expect(page().querySelector('.upload-sent a')?.getAttribute('href')).toBe(
      '/app/submissions/sub-5',
    );
  });

  it('validationFailed_mapsToFields', async () => {
    await open();

    const post = await sendText();
    post.flush(
      {
        status: 400,
        errorCode: 'VALIDATION_FAILED',
        violations: [{ field: 'upiId', message: 'must be a UPI ID like name@bank' }],
      },
      { status: 400, statusText: 'Bad Request' },
    );
    await settle();

    expect(page().querySelector('#upi-id-error')?.textContent).toContain('name@bank');
  });

  it('tooLargeFile_refusedBeforeSending', async () => {
    await open();
    await choose('pdf');
    await attach(pdf('big.pdf', 6 * 1024 * 1024));
    await submit();

    http.expectNone('/api/submissions');
    expect(page().querySelector('#letter-file-error')?.textContent).toContain('larger than 5 MB');
  });

  it('nothingChosen_showsFieldError_noRequest', async () => {
    await open();
    await choose('text');
    await submit();

    http.expectNone('/api/submissions');
    expect(page().querySelector('#letter-text-error')?.textContent).toContain('Paste');
    expect(announced()).toContain('Check the details');
  });

  it('invalidOptionalFields_blockSubmit', async () => {
    await open();
    await choose('text');
    type('#letter-text', FAKE_LETTER);
    type('#sender-email', 'not-an-email');
    type('#upi-id', 'nobank');
    await submit();

    http.expectNone('/api/submissions');
    expect(page().querySelector('#sender-email-error')).not.toBeNull();
    expect(page().querySelector('#upi-id-error')?.textContent).toContain('name@bank');
  });

  it('textCounter_showsIndianGrouping', async () => {
    await open();
    await choose('text');

    expect(page().textContent).toContain('20,000');
  });

  it('copy_neverClaimsSafety', async () => {
    await open();

    const text = (page().textContent ?? '').replace(/\(not verified\)/g, '');
    expect(text).not.toMatch(/\b(verified|safe|safely|legit|legitimate|guaranteed)\b/i);
  });
});
