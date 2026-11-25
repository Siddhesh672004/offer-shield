import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { fakeTpo } from '../../../../testing/fakes';
import { answerSessionProbe } from '../../../../testing/session-helpers';
import { claimsSafety } from '../../../../testing/staff-fakes';
import { Announcer } from '../../../core/a11y/announcer';
import { PendingMember } from './members-api';
import { PendingMembers } from './pending-members';

function member(id: string, name: string, version: number): PendingMember {
  return {
    id,
    studentName: name,
    studentEmail: `${name.toLowerCase().replace(' ', '.')}@example.com`,
    rollNo: `FAKE-${version}01`,
    department: 'Computer Engineering',
    requestedAt: '2026-10-19T04:30:00Z',
    version,
  };
}

const ASHA = member('m1', 'Asha Test', 3);
const RAHUL = member('m2', 'Rahul Fake', 0);
const REASON = 'Roll number FAKE-301 is not on our list.';

describe('PendingMembers', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<PendingMembers>;

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

  /** Lets requests and async handlers run (whenStable alone would wait for unanswered ones). */
  async function render(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve));
    await new Promise((resolve) => setTimeout(resolve));
  }

  async function settle(): Promise<void> {
    await render();
    await fixture.whenStable();
  }

  async function open(list: PendingMember[] = [ASHA, RAHUL]): Promise<void> {
    await answerSessionProbe(fakeTpo);
    fixture = TestBed.createComponent(PendingMembers);
    await render();
    http.expectOne('/api/memberships/pending').flush(list);
    await settle();
  }

  function row(name: string): HTMLElement {
    const rows = Array.from(page().querySelectorAll<HTMLElement>('.member'));
    return rows.find((item) => item.textContent?.includes(name))!;
  }

  function button(root: ParentNode, label: string): HTMLButtonElement {
    const found = Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find((item) =>
      item.textContent?.replace(/\s+/g, ' ').trim().startsWith(label),
    );
    if (!found) {
      throw new Error(`No "${label}" button`);
    }
    return found;
  }

  function type(selector: string, value: string): void {
    const control = page().querySelector<HTMLTextAreaElement>(selector)!;
    control.value = value;
    control.dispatchEvent(new Event('input'));
  }

  function decision() {
    return http.expectOne((request) => request.method === 'POST');
  }

  function problem(status: number, errorCode: string) {
    return [
      { status, errorCode, detail: 'Fake detail.' },
      { status, statusText: 'Error' },
    ] as const;
  }

  async function startReject(name = 'Asha Test'): Promise<void> {
    button(row(name), 'Reject').click();
    await fixture.whenStable();
  }

  async function sendReject(reason: string): Promise<void> {
    type('#reject-reason', reason);
    await fixture.whenStable();
    button(page(), 'Reject request').click();
    await fixture.whenStable();
  }

  it('rows_showStudentDetails', async () => {
    await open();

    const asha = row('Asha Test');
    expect(asha.textContent).toContain('asha.test@example.com');
    expect(asha.textContent).toContain('FAKE-301');
    expect(asha.textContent).toContain('Computer Engineering');
    expect(asha.textContent).toContain('19 Oct 2026');
    expect(page().querySelectorAll('.member')).toHaveLength(2);
    expect(page().textContent).toContain(fakeTpo.college!.name);
  });

  it('approve_sendsIfMatchVersion_removesRow_announces_focusesNext', async () => {
    await open();

    button(row('Asha Test'), 'Approve').click();
    const post = decision();
    expect(post.request.url).toBe('/api/memberships/m1/decision');
    expect(post.request.headers.get('If-Match')).toBe('"3"');
    expect(post.request.body).toEqual({ decision: 'APPROVE' });
    post.flush({ id: 'm1', status: 'APPROVED', version: 4 });
    await settle();

    expect(page().querySelectorAll('.member')).toHaveLength(1);
    expect(page().textContent).not.toContain('asha.test@example.com');
    expect(announced()).toContain('Approved Asha Test');
    expect(document.activeElement?.id).toBe('member-m2-name');
  });

  it('approve_inFlight_disablesEveryDecisionButton', async () => {
    await open();

    button(row('Asha Test'), 'Approve').click();
    await fixture.whenStable();

    expect(button(row('Asha Test'), 'Approve').disabled).toBe(true);
    expect(button(row('Rahul Fake'), 'Approve').disabled).toBe(true);
    expect(button(row('Rahul Fake'), 'Reject').disabled).toBe(true);
    button(row('Asha Test'), 'Approve').click();
    decision().flush({ id: 'm1', status: 'APPROVED', version: 4 });
    await settle();
  });

  it('reject_needsReason_noRequestWithoutIt', async () => {
    await open();
    await startReject();
    expect(document.activeElement?.id).toBe('reject-reason');

    await sendReject('   ');
    await settle();

    http.expectNone((request) => request.method === 'POST');
    expect(page().querySelector('#reject-reason-error')?.textContent).toContain('Give a reason');
    expect(page().querySelector('#reject-reason')?.getAttribute('aria-invalid')).toBe('true');
  });

  it('reject_withReason_sendsReasonAndIfMatch', async () => {
    await open();
    await startReject();

    await sendReject(`  ${REASON} `);
    const post = decision();
    expect(post.request.url).toBe('/api/memberships/m1/decision');
    expect(post.request.headers.get('If-Match')).toBe('"3"');
    expect(post.request.body).toEqual({ decision: 'REJECT', reason: REASON });
    post.flush({ id: 'm1', status: 'REJECTED', version: 4 });
    await settle();

    expect(page().querySelectorAll('.member')).toHaveLength(1);
    expect(announced()).toContain("Rejected Asha Test's request");
  });

  it('reject_cancel_returnsFocusToRejectButton', async () => {
    await open();
    await startReject();

    button(page(), 'Cancel').click();
    await settle();

    expect(page().querySelector('#reject-reason')).toBeNull();
    expect(document.activeElement?.textContent).toContain('Reject');
    expect(row('Asha Test').contains(document.activeElement)).toBe(true);
  });

  it('409_someoneElseDecided_refreshesList', async () => {
    await open();

    button(row('Asha Test'), 'Approve').click();
    decision().flush(...problem(409, 'CONFLICT'));
    await settle();
    http.expectOne('/api/memberships/pending').flush([RAHUL]);
    await settle();

    const notice = page().querySelector('#members-outcome')!;
    expect(notice.textContent).toContain('Someone else decided this request first');
    expect(notice.textContent).toContain('refreshed');
    expect(announced()).toContain('Someone else decided this request first');
    expect(page().querySelectorAll('.member')).toHaveLength(1);
    expect(document.activeElement).toBe(notice);
  });

  it('404_requestGone_refreshesList', async () => {
    await open();

    button(row('Asha Test'), 'Approve').click();
    decision().flush(...problem(404, 'NOT_FOUND'));
    await settle();
    http.expectOne('/api/memberships/pending').flush([RAHUL]);
    await settle();

    expect(page().querySelector('#members-outcome')?.textContent).toContain('no longer waiting');
  });

  it('428_asksToReload_keepsRow', async () => {
    await open();

    button(row('Asha Test'), 'Approve').click();
    decision().flush(...problem(428, 'PRECONDITION_REQUIRED'));
    await settle();

    expect(page().querySelector('#members-outcome')?.textContent).toContain('Reload the page');
    expect(page().querySelectorAll('.member')).toHaveLength(2);
    expect(button(row('Asha Test'), 'Approve').disabled).toBe(false);
  });

  it('reasonRequired400_showsFieldError', async () => {
    await open();
    await startReject();

    await sendReject(REASON);
    decision().flush(...problem(400, 'REASON_REQUIRED'));
    await settle();

    expect(page().querySelector('#reject-reason-error')?.textContent).toContain('Give a reason');
    expect(document.activeElement?.id).toBe('reject-reason');
  });

  it('lastRowDecided_showsEmptyState_focusesItsHeading', async () => {
    await open([ASHA]);

    button(row('Asha Test'), 'Approve').click();
    decision().flush({ id: 'm1', status: 'APPROVED', version: 4 });
    await settle();

    expect(page().textContent).toContain('No one is waiting');
    expect(document.activeElement?.textContent).toContain('No one is waiting');
  });

  it('empty_showsNothingWaiting', async () => {
    await open([]);

    expect(page().textContent).toContain('No one is waiting');
    expect(page().querySelector('.member')).toBeNull();
  });

  it('loadError_tryAgain_reloads', async () => {
    await answerSessionProbe(fakeTpo);
    fixture = TestBed.createComponent(PendingMembers);
    await render();
    http
      .expectOne('/api/memberships/pending')
      .flush('Bad Gateway', { status: 502, statusText: 'Bad Gateway' });
    await settle();

    expect(page().textContent).toContain("Couldn't load the requests");
    button(page(), 'Try again').click();
    await render();
    http.expectOne('/api/memberships/pending').flush([ASHA]);
    await settle();

    expect(page().querySelectorAll('.member')).toHaveLength(1);
  });

  it('copy_neverClaimsSafety', async () => {
    await open();
    await startReject();

    expect(claimsSafety(page().textContent ?? '')).toBe(false);
  });
});
