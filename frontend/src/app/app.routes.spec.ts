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
    const http = TestBed.inject(HttpTestingController);
    let done = false;
    const navigation = harness.navigateByUrl(url).finally(() => (done = true));
    // Pages load data: answer every list with none and an unknown item with 404, during the
    // navigation and once more after it.
    const lists = [
      '/api/submissions',
      '/api/memberships/pending',
      '/api/reviews/queue',
      '/api/admin/colleges',
      '/api/admin/scam-reports',
      '/api/known-companies',
    ];
    const items = ['/api/submissions/', '/api/reviews/'];
    for (let after = 0; !done || after < 3; after += done ? 1 : 0) {
      http.match((request) => lists.includes(request.url)).forEach((request) => request.flush([]));
      http
        .match((request) => items.some((prefix) => request.url.startsWith(prefix)))
        .forEach((request) =>
          request.flush({ status: 404 }, { status: 404, statusText: 'Not Found' }),
        );
      await new Promise((resolve) => setTimeout(resolve));
    }
    await navigation;
    await harness.fixture.whenStable();
    const heading = harness.routeNativeElement?.querySelector('h1')?.textContent?.trim() ?? '';
    return { url: router.url, heading };
  }

  it('tpoArea_tpo_opensPendingMembers', async () => {
    expect(await open(fakeTpo, '/tpo')).toEqual({
      url: '/tpo/members',
      heading: 'Pending members',
    });
  });

  it('tpoReviews_tpo_opensQueue', async () => {
    expect(await open(fakeTpo, '/tpo/reviews')).toEqual({
      url: '/tpo/reviews',
      heading: 'Review queue',
    });
  });

  it('tpoCase_idFromRoute_opensReviewAnOffer', async () => {
    expect(await open(fakeTpo, '/tpo/reviews/c1')).toEqual({
      url: '/tpo/reviews/c1',
      heading: 'Review an offer',
    });
  });

  it('tpoReviews_admin_showsNotFound', async () => {
    expect((await open(fakeAdmin, '/tpo/reviews')).heading).toBe('Page not found');
  });

  it('tpoArea_student_showsNotFound', async () => {
    expect((await open(fakeStudent, '/tpo')).heading).toBe('Page not found');
  });

  it('adminArea_admin_opensColleges', async () => {
    expect(await open(fakeAdmin, '/admin')).toEqual({
      url: '/admin/colleges',
      heading: 'Colleges and TPOs',
    });
  });

  it('adminScamReports_statusFromQuery_opens', async () => {
    expect(await open(fakeAdmin, '/admin/scam-reports?status=CONFIRMED')).toEqual({
      url: '/admin/scam-reports?status=CONFIRMED',
      heading: 'Scam reports',
    });
  });

  it('adminCompanies_admin_opensKnownCompanies', async () => {
    expect(await open(fakeAdmin, '/admin/companies')).toEqual({
      url: '/admin/companies',
      heading: 'Known companies',
    });
  });

  it('adminCompanies_tpo_showsNotFound', async () => {
    expect((await open(fakeTpo, '/admin/companies')).heading).toBe('Page not found');
  });

  it('adminArea_tpo_showsNotFound', async () => {
    expect((await open(fakeTpo, '/admin')).heading).toBe('Page not found');
  });

  it('studentArea_approvedStudent_opensYourOffers', async () => {
    const approved = { ...fakeStudent, membershipStatus: 'APPROVED' as const };

    expect(await open(approved, '/app')).toEqual({ url: '/app', heading: 'Your offers' });
  });

  it('upload_student_opensCheckAnOfferLetter', async () => {
    const approved = { ...fakeStudent, membershipStatus: 'APPROVED' as const };

    expect(await open(approved, '/app/upload')).toEqual({
      url: '/app/upload',
      heading: 'Check an offer letter',
    });
  });

  it('upload_tpo_showsNotFound', async () => {
    expect((await open(fakeTpo, '/app/upload')).heading).toBe('Page not found');
  });

  it('reportScam_admin_showsNotFound', async () => {
    expect((await open(fakeAdmin, '/app/submissions/sub-1/report-scam')).heading).toBe(
      'Page not found',
    );
  });

  it('report_idFromRoute_opensRiskReport', async () => {
    const approved = { ...fakeStudent, membershipStatus: 'APPROVED' as const };

    expect(await open(approved, '/app/submissions/sub-1')).toEqual({
      url: '/app/submissions/sub-1',
      heading: 'Risk report',
    });
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

  it('join_serverUnreachable_endsAtLoginExplainingAndKeepingTarget', async () => {
    const harness = await RouterTestingHarness.create();
    const http = TestBed.inject(HttpTestingController);

    const navigation = harness.navigateByUrl('/join');
    const probe = await vi.waitFor(() => http.expectOne('/api/auth/me'));
    probe.flush('Bad Gateway', { status: 502, statusText: 'Bad Gateway' });
    await navigation;
    await harness.fixture.whenStable();

    expect(router.url).toBe('/login?unreachable=1&next=%2Fjoin');
    expect(harness.routeNativeElement?.textContent).toContain(
      "We can't reach OfferShield right now. Try again in a minute.",
    );
  });
});
