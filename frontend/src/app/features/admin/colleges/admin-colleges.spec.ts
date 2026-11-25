import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { claimsSafety } from '../../../../testing/staff-fakes';
import { Announcer } from '../../../core/a11y/announcer';
import { AdminColleges } from './admin-colleges';
import { AdminCollege } from './colleges-api';

function college(
  id: string,
  name: string,
  city: string | null,
  tpoEmails: string[] = [],
  pendingTpoEmails: string[] = [],
): AdminCollege {
  return { id, name, city, status: 'ACTIVE', tpoEmails, pendingTpoEmails };
}

const TECH = college(
  'c1',
  'Fake Institute of Technology',
  'Pune',
  ['ravi.tpo@example.com'],
  ['meera.new@example.com'],
);
const ARTS = college('c2', 'Fake College of Arts', null);
const LAW = college('c3', 'Fake School of Law', 'Mumbai');

describe('AdminColleges', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<AdminColleges>;

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

  async function open(list: AdminCollege[] = [TECH, ARTS]): Promise<void> {
    fixture = TestBed.createComponent(AdminColleges);
    await render();
    http.expectOne('/api/admin/colleges').flush(list);
    await settle();
  }

  function row(name: string): HTMLElement {
    return Array.from(page().querySelectorAll<HTMLElement>('.college')).find((item) =>
      item.querySelector('h3')?.textContent?.includes(name),
    )!;
  }

  function type(selector: string, value: string): void {
    const control = page().querySelector<HTMLInputElement | HTMLSelectElement>(selector)!;
    control.value = value;
    control.dispatchEvent(new Event('input'));
    control.dispatchEvent(new Event('change'));
  }

  function submit(label: string): void {
    const button = Array.from(page().querySelectorAll<HTMLButtonElement>('button')).find(
      (item) => item.textContent?.trim() === label,
    );
    button!.click();
  }

  async function addCollege(name: string, city = ''): Promise<void> {
    type('#college-name', name);
    type('#college-city', city);
    await fixture.whenStable();
    submit('Add college');
    await fixture.whenStable();
  }

  async function assign(collegeId: string, email: string): Promise<void> {
    type('#assign-college', collegeId);
    type('#assign-email', email);
    await fixture.whenStable();
    submit('Assign TPO');
    await fixture.whenStable();
  }

  function post() {
    return http.expectOne((request) => request.method === 'POST');
  }

  it('list_showsCitiesAndAssignedVsWaitingTpos', async () => {
    await open();

    const tech = row('Fake Institute of Technology');
    expect(tech.textContent).toContain('Pune');
    const tpos = Array.from(tech.querySelectorAll('.tpo'));
    expect(tpos[0].textContent).toContain('ravi.tpo@example.com');
    expect(tpos[0].querySelector('app-badge')?.textContent).toContain('Assigned');
    expect(tpos[1].textContent).toContain('meera.new@example.com');
    expect(tpos[1].querySelector('app-badge')?.textContent).toContain('Waiting for first sign-in');
    expect(row('Fake College of Arts').textContent).toContain('No TPO yet');
    expect(page().querySelectorAll('#assign-college option[value="c2"]')).toHaveLength(1);
  });

  it('addCollege_postsTrimmedNameAndCity_refreshes_preselectsForAssign', async () => {
    await open();

    await addCollege('  Fake School of Law ', ' Mumbai ');
    const request = post();
    expect(request.request.url).toBe('/api/admin/colleges');
    expect(request.request.body).toEqual({ name: 'Fake School of Law', city: 'Mumbai' });
    request.flush({ ...LAW, version: 0 }, { status: 201, statusText: 'Created' });
    await settle();
    http.expectOne('/api/admin/colleges').flush([TECH, ARTS, LAW]);
    await settle();

    expect(row('Fake School of Law')).toBeDefined();
    expect(announced()).toContain('Added Fake School of Law');
    expect(page().querySelector<HTMLSelectElement>('#assign-college')!.value).toBe('c3');
    expect(page().querySelector<HTMLInputElement>('#college-name')!.value).toBe('');
  });

  it('addCollege_blankCity_sentAsNull', async () => {
    await open();

    await addCollege('Fake School of Law', '   ');
    const request = post();
    expect(request.request.body).toEqual({ name: 'Fake School of Law', city: null });
    request.flush({ ...LAW, city: null, version: 0 }, { status: 201, statusText: 'Created' });
    await settle();
    http.expectOne('/api/admin/colleges').flush([TECH, ARTS, LAW]);
    await settle();
  });

  it('addCollege_nameRequired_noRequest', async () => {
    await open();

    await addCollege('  ');
    await settle();

    http.expectNone((request) => request.method === 'POST');
    expect(page().querySelector('#college-name-error')?.textContent).toContain('Enter the name');
    expect(document.activeElement?.id).toBe('college-name');
  });

  it('addCollege_409_nameTaken_shownOnName', async () => {
    await open();

    await addCollege('Fake Institute of Technology');
    post().flush(
      { status: 409, errorCode: 'CONFLICT', detail: 'A college with this name already exists.' },
      { status: 409, statusText: 'Conflict' },
    );
    await settle();

    expect(page().querySelector('#college-name-error')?.textContent).toContain(
      'already on the list',
    );
    expect(page().querySelector('#college-name')?.getAttribute('aria-invalid')).toBe('true');
  });

  it('assignTpo_assigned_showsAssignedNow', async () => {
    await open();

    await assign('c2', ' priya.tpo@example.com ');
    const request = post();
    expect(request.request.url).toBe('/api/admin/colleges/c2/tpos');
    expect(request.request.body).toEqual({ email: 'priya.tpo@example.com' });
    request.flush({ email: 'priya.tpo@example.com', status: 'ASSIGNED' });
    await settle();
    http
      .expectOne('/api/admin/colleges')
      .flush([TECH, { ...ARTS, tpoEmails: ['priya.tpo@example.com'] }]);
    await settle();

    const outcome = page().querySelector('#assign-outcome')!.textContent;
    expect(outcome).toContain('priya.tpo@example.com is now the TPO for Fake College of Arts');
    expect(announced()).toContain('is now the TPO');
    expect(row('Fake College of Arts').textContent).toContain('Assigned');
    expect(page().querySelector<HTMLInputElement>('#assign-email')!.value).toBe('');
  });

  it('assignTpo_pending_showsWaitingForFirstSignIn', async () => {
    await open();

    await assign('c2', 'new.tpo@example.com');
    post().flush({ email: 'new.tpo@example.com', status: 'PENDING' });
    await settle();
    http
      .expectOne('/api/admin/colleges')
      .flush([TECH, { ...ARTS, pendingTpoEmails: ['new.tpo@example.com'] }]);
    await settle();

    expect(page().querySelector('#assign-outcome')!.textContent).toContain(
      'new.tpo@example.com becomes the TPO for Fake College of Arts at their first Google sign-in',
    );
    expect(row('Fake College of Arts').textContent).toContain('Waiting for first sign-in');
  });

  it('assignTpo_invalidEmailOrNoCollege_noRequest', async () => {
    await open();

    await assign('', 'not-an-email');
    await settle();

    http.expectNone((request) => request.method === 'POST');
    expect(page().querySelector('#assign-college-error')?.textContent).toContain(
      'Choose a college',
    );
    expect(page().querySelector('#assign-email-error')?.textContent).toContain('email address');
  });

  it('assignTpo_409_showsServerExplanation', async () => {
    await open();

    await assign('c1', 'meera.new@example.com');
    post().flush(
      {
        status: 409,
        errorCode: 'CONFLICT',
        detail: 'A pending TPO assignment exists for an address that differs only in accents.',
      },
      { status: 409, statusText: 'Conflict' },
    );
    await settle();

    expect(page().querySelector('#assign-outcome')!.textContent).toContain(
      'differs only in accents',
    );
  });

  it('assignTpo_404_collegeGone_refreshes', async () => {
    await open();

    await assign('c2', 'priya.tpo@example.com');
    post().flush(
      { status: 404, errorCode: 'NOT_FOUND', detail: 'College not found.' },
      { status: 404, statusText: 'Not Found' },
    );
    await settle();
    http.expectOne('/api/admin/colleges').flush([TECH]);
    await settle();

    expect(page().querySelector('#assign-outcome')!.textContent).toContain('no longer exists');
    expect(row('Fake College of Arts')).toBeUndefined();
  });

  it('empty_showsNoColleges', async () => {
    await open([]);

    expect(page().textContent).toContain('No colleges yet');
  });

  it('copy_neverClaimsSafety', async () => {
    await open();

    expect(claimsSafety(page().textContent ?? '')).toBe(false);
  });
});
