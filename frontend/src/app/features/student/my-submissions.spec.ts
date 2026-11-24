import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { MySubmissions } from './my-submissions';
import { Submission, SubmissionStatus } from './submission-api';

function row(
  id: string,
  status: SubmissionStatus,
  overrides: Partial<Submission> = {},
): Submission {
  return {
    id,
    status,
    sourceType: 'PDF',
    onCampus: false,
    pdfStored: true,
    createdAt: '2026-10-20T06:30:00Z',
    updatedAt: '2026-10-20T06:30:05Z',
    expiresAt: '2026-11-19T06:30:00Z',
    ...overrides,
  };
}

describe('MySubmissions', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<MySubmissions>;

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

  /** Renders without waiting for the list resource (whenStable would wait for its answer). */
  async function render(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve));
    await new Promise((resolve) => setTimeout(resolve));
  }

  async function open(canUpload = true): Promise<void> {
    fixture = TestBed.createComponent(MySubmissions);
    fixture.componentRef.setInput('canUpload', canUpload);
    await render();
  }

  async function answer(body: string | object, status?: { status: number; statusText: string }) {
    http.expectOne('/api/submissions').flush(body, status);
    await fixture.whenStable();
    await render();
  }

  it('rows_linkToReports_withEveryStatus', async () => {
    await open();
    await answer([
      row('a', 'QUEUED'),
      row('b', 'READY', { sourceType: 'TEXT' }),
      row('c', 'PARTIAL'),
      row('d', 'FAILED'),
      row('e', 'DELETED'),
    ]);

    const rows = Array.from(page().querySelectorAll('.submission'));
    expect(rows).toHaveLength(5);
    expect(rows[0].querySelector('a')?.getAttribute('href')).toBe('/app/submissions/a');
    const labels = rows.map((item) => item.querySelector('app-badge')?.textContent?.trim());
    expect(labels).toEqual([
      'Checking',
      'Report ready',
      'Partial report',
      "Couldn't check",
      'Deleted',
    ]);
    expect(rows[1].textContent).toContain('Pasted text');
    expect(rows[0].textContent).toContain('20 Oct 2026');
  });

  it('empty_offersUpload', async () => {
    await open();
    await answer([]);

    expect(page().textContent).toContain('No letters checked yet');
    expect(page().querySelector('a[href="/app/upload"]')).not.toBeNull();
  });

  it('empty_withoutUpload_forStaff', async () => {
    await open(false);
    await answer([]);

    expect(page().querySelector('a[href="/app/upload"]')).toBeNull();
  });

  it('error_tryAgain_reloads', async () => {
    await open();
    await answer('Bad Gateway', { status: 502, statusText: 'Bad Gateway' });

    expect(page().textContent).toContain("Couldn't load your letters");
    page().querySelector<HTMLButtonElement>('.submissions-retry')!.click();
    await render();
    await answer([row('a', 'READY')]);

    expect(page().querySelectorAll('.submission')).toHaveLength(1);
  });
});
