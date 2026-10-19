import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { fakeStudent } from '../../../testing/fakes';
import { Session, SESSION_PROBE } from './session';

describe('Session', () => {
  let session: Session;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    session = TestBed.inject(Session);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('load_signedIn_exposesMe', async () => {
    const loaded = session.load();
    http.expectOne('/api/auth/me').flush(fakeStudent);

    expect(await loaded).toEqual(fakeStudent);
    expect(session.status()).toBe('signed-in');
    expect(session.signedIn()).toBe(true);
    expect(session.me()?.role).toBe('USER');
  });

  it('load_401_signedOut', async () => {
    const loaded = session.load();
    http
      .expectOne('/api/auth/me')
      .flush(
        { status: 401, errorCode: 'UNAUTHORIZED' },
        { status: 401, statusText: 'Unauthorized' },
      );

    expect(await loaded).toBeNull();
    expect(session.status()).toBe('signed-out');
    expect(session.signedIn()).toBe(false);
  });

  it('load_calledTwice_sendsOneRequest', async () => {
    const first = session.load();
    const second = session.load();
    http.expectOne('/api/auth/me').flush(fakeStudent);

    expect(await first).toEqual(fakeStudent);
    expect(await second).toEqual(fakeStudent);
    expect(await session.load()).toEqual(fakeStudent);
  });

  it('load_marksRequestAsSessionProbe', async () => {
    const loaded = session.load();
    const request = http.expectOne('/api/auth/me');

    expect(request.request.context.get(SESSION_PROBE)).toBe(true);
    request.flush(fakeStudent);
    await loaded;
  });

  it('load_serverUnreachable_reportsUnreachableAndRetriesNextTime', async () => {
    const first = session.load();
    http.expectOne('/api/auth/me').flush('Bad Gateway', { status: 502, statusText: 'Bad Gateway' });

    expect(await first).toBeNull();
    expect(session.status()).toBe('unreachable');

    const second = session.load();
    http.expectOne('/api/auth/me').flush(fakeStudent);
    expect(await second).toEqual(fakeStudent);
    expect(session.status()).toBe('signed-in');
  });

  it('refresh_afterLoad_fetchesAgain', async () => {
    const loaded = session.load();
    http.expectOne('/api/auth/me').flush(fakeStudent);
    await loaded;

    const refreshed = session.refresh();
    http.expectOne('/api/auth/me').flush({ ...fakeStudent, membershipStatus: 'PENDING' });

    expect((await refreshed)?.membershipStatus).toBe('PENDING');
    expect(session.me()?.membershipStatus).toBe('PENDING');
  });

  it('logout_postsAndSignsOut', async () => {
    const loaded = session.load();
    http.expectOne('/api/auth/me').flush(fakeStudent);
    await loaded;

    const loggedOut = session.logout();
    const request = http.expectOne('/api/auth/logout');
    expect(request.request.method).toBe('POST');
    request.flush(null, { status: 204, statusText: 'No Content' });
    await loggedOut;

    expect(session.status()).toBe('signed-out');
    expect(session.me()).toBeNull();
    expect(await session.load()).toBeNull();
  });

  it('logout_sessionAlreadyExpired_signsOut', async () => {
    const loggedOut = session.logout();
    http
      .expectOne('/api/auth/logout')
      .flush({ status: 401 }, { status: 401, statusText: 'Unauthorized' });
    await loggedOut;

    expect(session.status()).toBe('signed-out');
  });

  it('logout_serverError_rejectsAndKeepsSession', async () => {
    const loaded = session.load();
    http.expectOne('/api/auth/me').flush(fakeStudent);
    await loaded;

    const loggedOut = session.logout();
    http
      .expectOne('/api/auth/logout')
      .flush({ status: 403 }, { status: 403, statusText: 'Forbidden' });

    await expect(loggedOut).rejects.toBeTruthy();
    expect(session.signedIn()).toBe(true);
  });

  it('markSignedOut_clearsMe', async () => {
    const loaded = session.load();
    http.expectOne('/api/auth/me').flush(fakeStudent);
    await loaded;

    session.markSignedOut();

    expect(session.me()).toBeNull();
    expect(session.status()).toBe('signed-out');
  });
});
