import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Announcer } from '../../../core/a11y/announcer';
import { ExplainSummary } from './explain';

const ID = '00000000-0000-0000-0000-00000000005a';
const URL = `/api/reports/${ID}/explain`;

describe('ExplainSummary', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<ExplainSummary>;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ExplainSummary);
    fixture.componentRef.setInput('submissionId', ID);
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  function page(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    await fixture.whenStable();
  }

  async function explain(): Promise<void> {
    page().querySelector<HTMLButtonElement>('.explain__button')!.click();
    await fixture.whenStable();
  }

  it('aiWritten_shownAsPlainTextWithLabel', async () => {
    await explain();
    const post = http.expectOne(URL);
    expect(post.request.method).toBe('POST');
    post.flush({
      submissionId: ID,
      explanation: 'This letter shows strong scam signs.\nNever pay money to get a job.',
      aiWritten: true,
    });
    await settle();

    const summary = page().querySelector('.explain__text')!;
    expect(summary.textContent).toBe(
      'This letter shows strong scam signs.\nNever pay money to get a job.',
    );
    expect(summary.classList).toContain('plain-text');
    expect(page().textContent).toContain('AI-written summary');
    expect(TestBed.inject(Announcer).message()).toContain('Summary ready');
  });

  it('template_labelledAiUnavailable', async () => {
    await explain();
    http.expectOne(URL).flush({
      submissionId: ID,
      explanation:
        'Strong scam indicators. Do not pay anything.\nWhat the checks found:\n- Money request: …',
      aiWritten: false,
    });
    await settle();

    expect(page().textContent).toContain('Summary from the checks (AI unavailable)');
    expect(page().textContent).not.toContain('AI-written summary');
  });

  it('markupInText_renderedAsText', async () => {
    await explain();
    http.expectOne(URL).flush({
      submissionId: ID,
      explanation: 'Look at <b>this</b> carefully before you reply to the recruiter.',
      aiWritten: true,
    });
    await settle();

    const summary = page().querySelector('.explain__text')!;
    expect(summary.querySelector('b')).toBeNull();
    expect(summary.textContent).toContain('<b>this</b>');
  });

  it('error_showsMessage_buttonStays', async () => {
    await explain();
    http.expectOne(URL).flush('Bad Gateway', { status: 502, statusText: 'Bad Gateway' });
    await settle();

    expect(page().textContent).toContain("Couldn't get the summary");
    expect(page().querySelector('.explain__button')).not.toBeNull();
  });

  it('429_explainLimit_showsTheWait_buttonStays', async () => {
    await explain();
    http
      .expectOne(URL)
      .flush(
        { status: 429, errorCode: 'EXPLAIN_LIMIT', detail: 'x' },
        { status: 429, statusText: 'Too Many Requests', headers: { 'Retry-After': '290' } },
      );
    await settle();

    const error = page().querySelector('.explain__error')!.textContent ?? '';
    expect(error).toContain('10 summaries every 10 minutes');
    expect(error).toContain('Try again in about 5 minutes.');
    expect(TestBed.inject(Announcer).message()).toContain('about 5 minutes');
    expect(page().querySelector('.explain__button')).not.toBeNull();
  });

  it('429_withoutRetryAfter_saysAFewMinutes', async () => {
    await explain();
    http
      .expectOne(URL)
      .flush(
        { status: 429, errorCode: 'EXPLAIN_LIMIT', detail: 'x' },
        { status: 429, statusText: 'Too Many Requests' },
      );
    await settle();

    expect(page().querySelector('.explain__error')?.textContent).toContain(
      'Try again in a few minutes.',
    );
  });

  it('429_otherLimit_saysTooManyRequestsWithTheWait', async () => {
    await explain();
    http
      .expectOne(URL)
      .flush(
        { status: 429, errorCode: 'RATE_LIMITED', detail: 'x' },
        { status: 429, statusText: 'Too Many Requests', headers: { 'Retry-After': '30' } },
      );
    await settle();

    expect(page().querySelector('.explain__error')?.textContent).toContain(
      'Too many requests at once. Try again in 30 seconds.',
    );
  });

  it('clickTwiceWhileLoading_sendsOnce', async () => {
    const button = page().querySelector<HTMLButtonElement>('.explain__button')!;
    button.click();
    button.click();
    await fixture.whenStable();

    const posts = http.match(URL);
    expect(posts).toHaveLength(1);
    posts[0].flush({
      submissionId: ID,
      explanation: 'A short summary of the checks.',
      aiWritten: true,
    });
  });
});
