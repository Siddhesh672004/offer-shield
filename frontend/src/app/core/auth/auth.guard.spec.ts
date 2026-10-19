import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { fakeStudent } from '../../../testing/fakes';
import { answerSessionProbe } from '../../../testing/session-helpers';
import { authGuard } from './auth.guard';

@Component({ template: '<h1>Login page</h1>' })
class LoginStub {}

@Component({ template: '<h1>Student area</h1>' })
class AppStub {}

describe('authGuard', () => {
  let http: HttpTestingController;
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([
          { path: 'login', component: LoginStub },
          { path: 'app', canMatch: [authGuard], component: AppStub },
        ]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
  });

  afterEach(() => http.verify());

  it('signedIn_allowsRoute', async () => {
    await answerSessionProbe(fakeStudent);
    const harness = await RouterTestingHarness.create();

    await harness.navigateByUrl('/app');

    expect(router.url).toBe('/app');
    expect(harness.routeNativeElement?.textContent).toContain('Student area');
  });

  it('signedOut_redirectsToLogin', async () => {
    await answerSessionProbe('signed-out');
    const harness = await RouterTestingHarness.create();

    await harness.navigateByUrl('/app');

    expect(router.url).toBe('/login');
  });

  it('signedOutAfterFailedLogin_redirectsToLoginWithFlag', async () => {
    await answerSessionProbe('signed-out');
    const harness = await RouterTestingHarness.create();

    await harness.navigateByUrl('/app?login=failed');

    expect(router.url).toBe('/login?login=failed');
  });

  it('serverUnreachable_redirectsToLoginKeepingTarget', async () => {
    await answerSessionProbe('unreachable');
    const harness = await RouterTestingHarness.create();

    const navigation = harness.navigateByUrl('/app');
    // The guard asks again because the first answer was not a real "signed out".
    const retry = await vi.waitFor(() => http.expectOne('/api/auth/me'));
    retry.flush('Bad Gateway', { status: 502, statusText: 'Bad Gateway' });
    await navigation;

    expect(router.url).toBe('/login?unreachable=1&next=%2Fapp');
  });

  it('networkError_redirectsToLoginKeepingDeepLinkWithQuery', async () => {
    const harness = await RouterTestingHarness.create();

    const navigation = harness.navigateByUrl('/app?ref=mail');
    const probe = await vi.waitFor(() => http.expectOne('/api/auth/me'));
    probe.error(new ProgressEvent('error'));
    await navigation;

    const loginUrl = router.parseUrl(router.url);
    expect(loginUrl.root.children['primary']?.toString()).toBe('login');
    expect(loginUrl.queryParams).toEqual({ unreachable: '1', next: '/app?ref=mail' });
  });

  it('firstNavigation_waitsForSessionProbe', async () => {
    const harness = await RouterTestingHarness.create();

    const navigation = harness.navigateByUrl('/app');
    const probe = await vi.waitFor(() => http.expectOne('/api/auth/me'));
    probe.flush(fakeStudent);
    await navigation;

    expect(router.url).toBe('/app');
  });
});
