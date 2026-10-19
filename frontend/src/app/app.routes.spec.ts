import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { fakeAdmin, fakeStudent, fakeTpo } from '../testing/fakes';
import { ProbeAnswer, answerSessionProbe } from '../testing/session-helpers';
import { routes } from './app.routes';

describe('app routes', () => {
  let router: Router;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter(routes, withComponentInputBinding()),
      ],
    });
    router = TestBed.inject(Router);
  });

  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    localStorage.clear();
  });

  async function open(answer: ProbeAnswer, url: string): Promise<{ url: string; heading: string }> {
    await answerSessionProbe(answer);
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url);
    const heading = harness.routeNativeElement?.querySelector('h1')?.textContent?.trim() ?? '';
    return { url: router.url, heading };
  }

  it('tpoArea_tpo_opensTpoDesk', async () => {
    expect(await open(fakeTpo, '/tpo')).toEqual({ url: '/tpo', heading: 'TPO desk' });
  });

  it('tpoArea_student_showsNotFound', async () => {
    expect((await open(fakeStudent, '/tpo')).heading).toBe('Page not found');
  });

  it('adminArea_admin_opensAdmin', async () => {
    expect(await open(fakeAdmin, '/admin')).toEqual({ url: '/admin', heading: 'Admin' });
  });

  it('adminArea_tpo_showsNotFound', async () => {
    expect((await open(fakeTpo, '/admin')).heading).toBe('Page not found');
  });

  it('studentArea_approvedStudent_opensYourOffers', async () => {
    const approved = { ...fakeStudent, membershipStatus: 'APPROVED' as const };

    expect(await open(approved, '/app')).toEqual({ url: '/app', heading: 'Your offers' });
  });

  it('root_signedIn_redirectsToStudentArea', async () => {
    expect((await open(fakeTpo, '/')).url).toBe('/app');
  });

  it('unknownPath_showsNotFound', async () => {
    expect((await open(fakeStudent, '/no-such-page')).heading).toBe('Page not found');
  });

  it('root_signedOut_endsAtLogin', async () => {
    expect(await open('signed-out', '/')).toEqual({
      url: '/login',
      heading: 'Check a job offer before you reply',
    });
  });

  it('failedGoogleSignIn_endsAtLoginWithMessage', async () => {
    await answerSessionProbe('signed-out');
    const harness = await RouterTestingHarness.create();

    await harness.navigateByUrl('/app?login=failed');

    expect(router.url).toBe('/login?login=failed');
    expect(harness.routeNativeElement?.textContent).toContain(
      "Sign-in didn't complete. Try again.",
    );
  });

  it('newStudentAfterSignIn_landsOnChoosePath', async () => {
    expect(await open(fakeStudent, '/app')).toEqual({
      url: '/start',
      heading: 'How do you want to use OfferShield?',
    });
  });

  it('choosePath_tpo_showsNotFound', async () => {
    expect((await open(fakeTpo, '/start')).heading).toBe('Page not found');
  });

  it('join_student_opensJoinPage', async () => {
    await answerSessionProbe(fakeStudent);
    const harness = await RouterTestingHarness.create();
    const http = TestBed.inject(HttpTestingController);

    await harness.navigateByUrl('/join');
    http
      .expectOne('/api/memberships/me')
      .flush({ status: 404 }, { status: 404, statusText: 'Not Found' });
    http.expectOne((request) => request.url === '/api/colleges').flush([]);
    await harness.fixture.whenStable();

    expect(router.url).toBe('/join');
    expect(harness.routeNativeElement?.querySelector('h1')?.textContent).toBe('Join my college');
  });

  it('join_signedOut_endsAtLogin', async () => {
    expect((await open('signed-out', '/join')).url).toBe('/login');
  });
});
