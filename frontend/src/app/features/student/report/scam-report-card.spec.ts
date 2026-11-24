import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ScamReport } from '../scam-report-api';
import { ScamReportCard } from './scam-report-card';

const ID = '00000000-0000-0000-0000-00000000005a';

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

describe('ScamReportCard', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<ScamReportCard>;

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

  /** Renders without waiting for the reports resource (whenStable would wait for its answer). */
  async function render(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve));
    await new Promise((resolve) => setTimeout(resolve));
  }

  async function settle(): Promise<void> {
    await fixture.whenStable();
    await render();
    await fixture.whenStable();
  }

  async function create(canReport: boolean): Promise<void> {
    fixture = TestBed.createComponent(ScamReportCard);
    fixture.componentRef.setInput('submissionId', ID);
    fixture.componentRef.setInput('canReport', canReport);
    await render();
  }

  function reportLink(): HTMLAnchorElement | null {
    return page().querySelector<HTMLAnchorElement>(`a[href="/app/submissions/${ID}/report-scam"]`);
  }

  it('existingReport_showsItsStatus_noLink', async () => {
    await create(true);
    http.expectOne('/api/scam-reports').flush([scamReport()]);
    await settle();

    expect(page().textContent).toContain('Your scam report');
    expect(page().textContent).toContain('Waiting for review');
    expect(reportLink()).toBeNull();
  });

  it('noReport_canReport_linksToTheForm', async () => {
    await create(true);
    http.expectOne('/api/scam-reports').flush([scamReport({ submissionId: 'another-letter' })]);
    await settle();

    expect(reportLink()).not.toBeNull();
  });

  it('loadFails_warnsInsteadOfOfferingToReport_tryAgainReloads', async () => {
    await create(true);
    http
      .expectOne('/api/scam-reports')
      .flush('Bad Gateway', { status: 502, statusText: 'Bad Gateway' });
    await settle();

    expect(reportLink()).toBeNull();
    const warning = page().querySelector('.scam-card__load-error')!;
    expect(warning.textContent).toContain("Couldn't check whether you already reported");

    Array.from(page().querySelectorAll('button'))
      .find((button) => button.textContent?.trim() === 'Try again')!
      .click();
    await render();
    http.expectOne('/api/scam-reports').flush([scamReport({ status: 'REJECTED' })]);
    await settle();

    expect(page().querySelector('.scam-card__load-error')).toBeNull();
    expect(page().textContent).toContain('Not confirmed');
  });

  it('loadFails_notAllowedToReport_stillWarns', async () => {
    await create(false);
    http.expectOne('/api/scam-reports').error(new ProgressEvent('error'));
    await settle();

    expect(page().querySelector('.scam-card__load-error')).not.toBeNull();
  });

  it('sessionEnded_showsNothing', async () => {
    await create(true);
    http
      .expectOne('/api/scam-reports')
      .flush(
        { status: 401, errorCode: 'UNAUTHORIZED' },
        { status: 401, statusText: 'Unauthorized' },
      );
    await settle();

    expect(page().querySelector('.scam-card__load-error')).toBeNull();
  });
});
