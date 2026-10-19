import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { fakeStudent } from '../../../testing/fakes';
import { answerSessionProbe } from '../../../testing/session-helpers';
import { Announcer } from '../../core/a11y/announcer';
import { JoinCollege } from './join-college';
import { CollegeOption, Membership } from './membership-api';

const instituteOfTechnology: CollegeOption = {
  id: '00000000-0000-0000-0000-0000000000c1',
  name: 'Fake Institute of Technology',
  city: 'Pune',
};
const collegeOfCommerce: CollegeOption = {
  id: '00000000-0000-0000-0000-0000000000c2',
  name: 'Fake College of Commerce',
  city: 'Nagpur',
};

const pendingRequest: Membership = {
  id: '00000000-0000-0000-0000-0000000000a1',
  collegeId: instituteOfTechnology.id,
  collegeName: instituteOfTechnology.name,
  rollNo: 'FAKE-001',
  department: 'Computer Engineering',
  status: 'PENDING',
  reason: null,
  version: 0,
};

const notFound = { status: 404, statusText: 'Not Found' };

describe('JoinCollege', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<JoinCollege>;

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

  async function openJoinPage(existing: Membership | null = null): Promise<void> {
    await answerSessionProbe(fakeStudent);
    fixture = TestBed.createComponent(JoinCollege);
    const mine = http.expectOne('/api/memberships/me');
    if (existing) {
      mine.flush(existing);
    } else {
      mine.flush({ status: 404, errorCode: 'NOT_FOUND' }, notFound);
    }
    http
      .expectOne((request) => request.url === '/api/colleges')
      .flush([collegeOfCommerce, instituteOfTechnology]);
    await fixture.whenStable();
  }

  function type(selector: string, value: string): void {
    const input = page().querySelector<HTMLInputElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  async function fillAndSend(collegeId: string | null = instituteOfTechnology.id): Promise<void> {
    if (collegeId) {
      page().querySelector<HTMLInputElement>(`input[type="radio"][value="${collegeId}"]`)!.click();
    }
    type('#roll-no', 'FAKE-001');
    type('#department', 'Computer Engineering');
    await fixture.whenStable();
    page().querySelector<HTMLButtonElement>('button[type="submit"].join-submit')!.click();
  }

  async function settle(): Promise<void> {
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    await fixture.whenStable();
  }

  it('submit_showsPending', async () => {
    await openJoinPage();

    await fillAndSend();
    const post = http.expectOne('/api/memberships');
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({
      collegeId: instituteOfTechnology.id,
      rollNo: 'FAKE-001',
      department: 'Computer Engineering',
    });
    post.flush(pendingRequest, { status: 201, statusText: 'Created' });
    const refreshed = await vi.waitFor(() => http.expectOne('/api/auth/me'));
    refreshed.flush({
      ...fakeStudent,
      membershipStatus: 'PENDING',
      college: { id: instituteOfTechnology.id, name: instituteOfTechnology.name },
    });
    await settle();

    const status = page().querySelector('.request-status');
    expect(status?.textContent).toContain('Pending');
    expect(page().textContent).toContain('Fake Institute of Technology');
    expect(page().querySelector('#roll-no')).toBeNull();
    expect(TestBed.inject(Announcer).message()).toContain('Join request sent');
  });

  it('409_showsExisting', async () => {
    await openJoinPage();

    await fillAndSend();
    http
      .expectOne('/api/memberships')
      .flush(
        { status: 409, errorCode: 'MEMBERSHIP_EXISTS', detail: 'You already have a join request.' },
        { status: 409, statusText: 'Conflict' },
      );
    const mine = await vi.waitFor(() => http.expectOne('/api/memberships/me'));
    mine.flush({
      ...pendingRequest,
      collegeId: collegeOfCommerce.id,
      collegeName: collegeOfCommerce.name,
      status: 'APPROVED',
      version: 1,
    });
    const refreshed = await vi.waitFor(() => http.expectOne('/api/auth/me'));
    refreshed.flush({ ...fakeStudent, membershipStatus: 'APPROVED' });
    await settle();

    expect(page().textContent).toContain('You already have a join request.');
    expect(page().querySelector('.request-status')?.textContent).toContain('Approved');
    expect(page().textContent).toContain('Fake College of Commerce');
    expect(page().querySelector('#roll-no')).toBeNull();
  });

  it('403EmailNotConfirmed_explainsWithoutClaimingVerification', async () => {
    await openJoinPage();

    await fillAndSend();
    http
      .expectOne('/api/memberships')
      .flush(
        { status: 403, errorCode: 'EMAIL_NOT_VERIFIED' },
        { status: 403, statusText: 'Forbidden' },
      );
    await settle();

    expect(page().textContent).toContain("Google hasn't confirmed the email address");
    expect(page().textContent).not.toMatch(/verified/i);
    expect(page().querySelector('#roll-no')).not.toBeNull();
  });

  it('404College_asksToSearchAgain', async () => {
    await openJoinPage();

    await fillAndSend();
    http.expectOne('/api/memberships').flush({ status: 404, errorCode: 'NOT_FOUND' }, notFound);
    await settle();

    expect(page().textContent).toContain("That college isn't available any more.");
  });

  it('noCollegeChosen_showsFieldErrorAndSendsNothing', async () => {
    await openJoinPage();

    await fillAndSend(null);
    await settle();

    http.expectNone('/api/memberships');
    expect(page().textContent).toContain('Choose your college.');
  });

  it('invalidSubmit_movesFocusToFirstProblem', async () => {
    await openJoinPage();

    page().querySelector<HTMLButtonElement>('button[type="submit"].join-submit')!.click();
    await settle();

    const firstRadio = page().querySelector<HTMLInputElement>('input[type="radio"]');
    expect(document.activeElement).toBe(firstRadio);
  });

  it('invalidRollNumberOnly_movesFocusToRollNumber', async () => {
    await openJoinPage();

    page()
      .querySelector<HTMLInputElement>(`input[type="radio"][value="${instituteOfTechnology.id}"]`)!
      .click();
    type('#department', 'Computer Engineering');
    await fixture.whenStable();
    page().querySelector<HTMLButtonElement>('button[type="submit"].join-submit')!.click();
    await settle();

    expect(document.activeElement?.id).toBe('roll-no');
  });

  it('collegeRadios_shareOneGroupName', async () => {
    await openJoinPage();

    const radios = Array.from(page().querySelectorAll<HTMLInputElement>('input[type="radio"]'));
    expect(radios.length).toBe(2);
    expect(radios[0].name).not.toBe('');
    expect(radios.every((radio) => radio.name === radios[0].name)).toBe(true);
  });

  it('blankRollNumber_showsFieldErrorAndSendsNothing', async () => {
    await openJoinPage();

    page()
      .querySelector<HTMLInputElement>(`input[type="radio"][value="${instituteOfTechnology.id}"]`)!
      .click();
    type('#roll-no', '   ');
    type('#department', 'Computer Engineering');
    await fixture.whenStable();
    page().querySelector<HTMLButtonElement>('button[type="submit"].join-submit')!.click();
    await settle();

    http.expectNone('/api/memberships');
    expect(page().textContent).toContain('Enter your roll number.');
  });

  it('pendingRequestOnOpen_showsStatusWithoutForm', async () => {
    await openJoinPage(pendingRequest);

    expect(page().querySelector('.request-status')?.textContent).toContain('Pending');
    expect(page().querySelector('#roll-no')).toBeNull();
  });

  it('rejectedRequestOnOpen_showsReasonAndForm', async () => {
    await openJoinPage({
      ...pendingRequest,
      status: 'REJECTED',
      reason: 'Roll number not on the 2026 list',
    });

    expect(page().querySelector('.request-status')?.textContent).toContain('Not approved');
    expect(page().textContent).toContain('Roll number not on the 2026 list');
    expect(page().querySelector('#roll-no')).not.toBeNull();
  });

  it('search_sendsQueryAndShowsEmptyResult', async () => {
    await openJoinPage();

    type('#college-search', 'zzz');
    page().querySelector<HTMLButtonElement>('.search-submit')!.click();
    const search = http.expectOne((request) => request.url === '/api/colleges');
    expect(search.request.params.get('q')).toBe('zzz');
    search.flush([]);
    await settle();

    expect(page().textContent).toContain('No college matches “zzz”.');
  });
});
