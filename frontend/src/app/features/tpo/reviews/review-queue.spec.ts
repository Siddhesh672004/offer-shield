import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { claimsSafety, fakeCase, fakeEvidence, fakeMarker } from '../../../../testing/staff-fakes';
import { ReviewQueue } from './review-queue';
import { ReviewCaseDetail } from './reviews-api';

describe('ReviewQueue', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<ReviewQueue>;

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

  async function render(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve));
    await new Promise((resolve) => setTimeout(resolve));
  }

  async function open(
    body: ReviewCaseDetail[] | string,
    status?: { status: number; statusText: string },
  ): Promise<void> {
    fixture = TestBed.createComponent(ReviewQueue);
    await render();
    http.expectOne('/api/reviews/queue').flush(body, status);
    await render();
    await fixture.whenStable();
  }

  function rows(): HTMLElement[] {
    return Array.from(page().querySelectorAll<HTMLElement>('.case'));
  }

  it('rows_linkToCases_withStudentDateAndBand', async () => {
    await open([
      fakeCase(),
      fakeCase({
        id: 'c2',
        studentName: 'Rahul Fake',
        studentEmail: 'rahul.fake@example.com',
        evidence: fakeEvidence({ band: 'MEDIUM', score: 40, markers: [] }),
      }),
    ]);

    expect(rows()).toHaveLength(2);
    const link = rows()[0].querySelector('a')!;
    expect(link.getAttribute('href')).toBe('/tpo/reviews/c1');
    expect(link.textContent).toContain('Asha Test');
    expect(rows()[0].textContent).toContain('asha.test@example.com');
    expect(rows()[0].textContent).toContain('19 Oct 2026');
    expect(rows()[0].querySelector('app-risk-band')?.textContent).toContain('High risk');
    expect(rows()[1].querySelector('app-risk-band')?.textContent).toContain('Medium risk');
    expect(page().textContent).toContain('2 offers waiting');
  });

  it('rows_trustLevelsDistinct', async () => {
    await open([
      fakeCase(),
      fakeCase({
        id: 'c2',
        evidence: fakeEvidence({ markers: [fakeMarker({ reporterCount: 3 })] }),
      }),
    ]);

    expect(rows()[0].querySelector('.report-chip--confirmed')?.textContent).toContain(
      'Confirmed scam',
    );
    expect(rows()[1].querySelector('.report-chip--confirmed')).toBeNull();
    expect(rows()[1].querySelector('.report-chip--reported')?.textContent).toContain(
      'Reported as a scam by 3 users (not verified)',
    );
  });

  it('reportDeleted_rowSaysSo', async () => {
    await open([fakeCase({ evidence: null })]);

    expect(rows()[0].textContent).toContain('Report deleted');
    expect(rows()[0].querySelector('app-risk-band')).toBeNull();
  });

  it('empty_showsNothingToReview', async () => {
    await open([]);

    expect(page().textContent).toContain('No offers to review');
    expect(rows()).toHaveLength(0);
  });

  it('loadError_tryAgain_reloads', async () => {
    await open('Bad Gateway', { status: 502, statusText: 'Bad Gateway' });

    expect(page().textContent).toContain("Couldn't load the review queue");
    page().querySelector<HTMLButtonElement>('button')!.click();
    await render();
    http.expectOne('/api/reviews/queue').flush([fakeCase()]);
    await render();
    await fixture.whenStable();

    expect(rows()).toHaveLength(1);
  });

  it('copy_neverClaimsSafety', async () => {
    await open([fakeCase()]);

    expect(claimsSafety(page().textContent ?? '')).toBe(false);
  });
});
