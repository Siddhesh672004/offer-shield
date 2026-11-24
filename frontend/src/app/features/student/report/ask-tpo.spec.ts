import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Announcer } from '../../../core/a11y/announcer';
import { ReviewCase } from '../review-api';
import { AskTpo } from './ask-tpo';

const ID = '00000000-0000-0000-0000-00000000005a';

function reviewCase(overrides: Partial<ReviewCase> = {}): ReviewCase {
  return {
    id: '00000000-0000-0000-0000-0000000000r1',
    submissionId: ID,
    status: 'OPEN',
    note: null,
    createdAt: '2026-10-20T06:30:00Z',
    decidedAt: null,
    ...overrides,
  };
}

describe('AskTpo', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<AskTpo>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function page(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  /** Renders without waiting for the cases resource (whenStable would wait for its answer). */
  async function render(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve));
    await new Promise((resolve) => setTimeout(resolve));
  }

  async function settle(): Promise<void> {
    await fixture.whenStable();
    await render();
    await fixture.whenStable();
  }

  async function open(canAsk: boolean, mine: ReviewCase[]): Promise<void> {
    fixture = TestBed.createComponent(AskTpo);
    fixture.componentRef.setInput('submissionId', ID);
    fixture.componentRef.setInput('canAsk', canAsk);
    await render();
    http.expectOne('/api/reviews').flush(mine);
    await settle();
  }

  function askButton(): HTMLButtonElement | null {
    return page().querySelector<HTMLButtonElement>('.ask-tpo__button');
  }

  it('ask_postsSubmissionId_showsWaiting', async () => {
    await open(true, []);

    askButton()!.click();
    await settle();
    const post = http.expectOne('/api/reviews');
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({ submissionId: ID });
    post.flush(reviewCase(), { status: 201, statusText: 'Created' });
    await settle();

    expect(page().textContent).toContain('Waiting for your TPO');
    expect(askButton()).toBeNull();
    expect(TestBed.inject(Announcer).message()).toContain('Sent to your TPO');
  });

  it('409_showsExistingCase', async () => {
    await open(true, []);

    askButton()!.click();
    await fixture.whenStable();
    http
      .expectOne((request) => request.method === 'POST')
      .flush(
        { status: 409, errorCode: 'REVIEW_EXISTS', detail: 'x' },
        { status: 409, statusText: 'Conflict' },
      );
    const reload = await vi.waitFor(() => http.expectOne((r) => r.method === 'GET'));
    reload.flush([reviewCase({ status: 'INCONCLUSIVE', decidedAt: '2026-10-21T06:30:00Z' })]);
    await settle();

    expect(page().textContent).toContain('You already asked your TPO');
    expect(page().textContent).toContain('No conclusion');
    expect(askButton()).toBeNull();
  });

  it('existingDecidedCase_showsStatusAndNoteAsText', async () => {
    await open(true, [
      reviewCase({
        status: 'CONFIRMED_SCAM',
        note: 'Do not pay.\nThe company has no such opening.',
        decidedAt: '2026-10-21T06:30:00Z',
      }),
    ]);

    expect(page().textContent).toContain('Marked as a scam');
    const note = page().querySelector('.ask-tpo__note')!;
    expect(note.classList).toContain('plain-text');
    expect(note.textContent).toBe('Do not pay.\nThe company has no such opening.');
    expect(askButton()).toBeNull();
  });

  it('otherLettersCase_ignored', async () => {
    await open(true, [reviewCase({ submissionId: 'another-letter' })]);

    expect(askButton()).not.toBeNull();
  });

  it('notEligible_noButton', async () => {
    await open(false, []);

    expect(askButton()).toBeNull();
    expect(page().textContent?.trim()).toBe('');
  });

  it('404_explainsWhoCanAsk', async () => {
    await open(true, []);

    askButton()!.click();
    await fixture.whenStable();
    http
      .expectOne((request) => request.method === 'POST')
      .flush({ status: 404, errorCode: 'NOT_FOUND' }, { status: 404, statusText: 'Not Found' });
    await settle();

    expect(page().textContent).toContain('approved member');
  });

  it('409_reportNotReady_showsDetail_buttonSaysTryAgain_andAsksAgain', async () => {
    await open(true, []);

    askButton()!.click();
    await fixture.whenStable();
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

    expect(page().querySelector('.ask-tpo__error')?.textContent).toBe(
      'This report is still being prepared. Try again in a minute.',
    );
    expect(TestBed.inject(Announcer).message()).toContain('still being prepared');
    expect(askButton()?.textContent?.trim()).toBe('Try again');

    askButton()!.click();
    await fixture.whenStable();
    const again = http.expectOne((request) => request.method === 'POST');
    expect(again.request.body).toEqual({ submissionId: ID });
    again.flush(reviewCase(), { status: 201, statusText: 'Created' });
    await settle();

    expect(page().textContent).toContain('Waiting for your TPO');
  });

  it('mineLoadFails_warnsInsteadOfOfferingToAsk_tryAgainReloads', async () => {
    fixture = TestBed.createComponent(AskTpo);
    fixture.componentRef.setInput('submissionId', ID);
    fixture.componentRef.setInput('canAsk', true);
    await render();
    http.expectOne('/api/reviews').flush('Bad Gateway', { status: 502, statusText: 'Bad Gateway' });
    await settle();

    expect(askButton()).toBeNull();
    const warning = page().querySelector('.ask-tpo__load-error')!;
    expect(warning.textContent).toContain("Couldn't check whether you already asked your TPO");

    Array.from(page().querySelectorAll('button'))
      .find((button) => button.textContent?.trim() === 'Try again')!
      .click();
    await render();
    http.expectOne('/api/reviews').flush([reviewCase({ status: 'OPEN' })]);
    await settle();

    expect(page().querySelector('.ask-tpo__load-error')).toBeNull();
    expect(page().textContent).toContain('Waiting for your TPO');
  });

  it('mineLoadFails_evenWhenNotEligible_stillWarns', async () => {
    fixture = TestBed.createComponent(AskTpo);
    fixture.componentRef.setInput('submissionId', ID);
    fixture.componentRef.setInput('canAsk', false);
    await render();
    http.expectOne('/api/reviews').error(new ProgressEvent('error'));
    await settle();

    expect(page().querySelector('.ask-tpo__load-error')).not.toBeNull();
    expect(askButton()).toBeNull();
  });

  it('copy_genuineDecision_neverClaimsSafety', async () => {
    await open(true, [
      reviewCase({ status: 'CONFIRMED_GENUINE', decidedAt: '2026-10-21T06:30:00Z' }),
    ]);

    expect(page().textContent).not.toMatch(
      /\b(verified|safe|safely|legit|legitimate|guaranteed)\b/i,
    );
    expect(page().textContent).toContain('never pay money to get a job');
  });
});
