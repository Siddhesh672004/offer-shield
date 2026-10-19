import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { fakeStudent, fakeTpo } from '../testing/fakes';
import { ProbeAnswer } from '../testing/session-helpers';
import { App } from './app';
import { Announcer } from './core/a11y/announcer';

@Component({ template: '<h1>Sign in</h1>' })
class LoginStub {}

@Component({ template: '<h1>Your offers</h1>' })
class AppStub {}

@Component({ template: '<h1>TPO desk</h1>' })
class TpoStub {}

describe('App shell', () => {
  let http: HttpTestingController;
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([
          { path: 'login', component: LoginStub },
          { path: 'app', component: AppStub },
          { path: 'tpo', component: TpoStub },
        ]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
  });

  afterEach(() => http.verify());

  async function createShell(answer: ProbeAnswer): Promise<ComponentFixture<App>> {
    const fixture = TestBed.createComponent(App);
    const probe = http.expectOne('/api/auth/me');
    if (answer === 'signed-out') {
      probe.flush({ status: 401 }, { status: 401, statusText: 'Unauthorized' });
    } else if (answer === 'unreachable') {
      probe.flush('Bad Gateway', { status: 502, statusText: 'Bad Gateway' });
    } else {
      probe.flush(answer);
    }
    await router.navigateByUrl('/app');
    await fixture.whenStable();
    return fixture;
  }

  function signOutButton(fixture: ComponentFixture<App>): HTMLButtonElement | undefined {
    const buttons = Array.from(
      fixture.nativeElement.querySelectorAll('button'),
    ) as HTMLButtonElement[];
    return buttons.find((button) => button.textContent?.trim() === 'Sign out');
  }

  it('signedIn_showsNameAndSignOut', async () => {
    const fixture = await createShell(fakeStudent);

    expect(fixture.nativeElement.querySelector('header').textContent).toContain('Asha Test');
    expect(signOutButton(fixture)).toBeDefined();
  });

  it('signedOut_hidesSignOut', async () => {
    const fixture = await createShell('signed-out');

    expect(signOutButton(fixture)).toBeUndefined();
  });

  it('tpo_seesTpoDeskLink', async () => {
    const fixture = await createShell(fakeTpo);

    const links = Array.from(
      fixture.nativeElement.querySelectorAll('nav a'),
    ) as HTMLAnchorElement[];
    expect(links.map((link) => link.textContent?.trim())).toContain('TPO desk');
  });

  it('student_doesNotSeeTpoDeskLink', async () => {
    const fixture = await createShell(fakeStudent);

    const links = Array.from(
      fixture.nativeElement.querySelectorAll('nav a'),
    ) as HTMLAnchorElement[];
    expect(links.map((link) => link.textContent?.trim())).not.toContain('TPO desk');
  });

  it('signOut_postsLogoutThenOpensLogin', async () => {
    const fixture = await createShell(fakeStudent);

    signOutButton(fixture)!.click();
    const logout = http.expectOne('/api/auth/logout');
    expect(logout.request.method).toBe('POST');
    logout.flush(null, { status: 204, statusText: 'No Content' });
    await vi.waitFor(() => expect(router.url).toBe('/login'));
    await fixture.whenStable();

    expect(signOutButton(fixture)).toBeUndefined();
  });

  it('signOutFails_staysAndExplains', async () => {
    const fixture = await createShell(fakeStudent);

    signOutButton(fixture)!.click();
    http
      .expectOne('/api/auth/logout')
      .flush({ status: 403 }, { status: 403, statusText: 'Forbidden' });
    await vi.waitFor(() =>
      expect(TestBed.inject(Announcer).message()).toContain("Couldn't sign out"),
    );
    await fixture.whenStable();

    expect(router.url).toBe('/app');
    expect(fixture.nativeElement.textContent).toContain("Couldn't sign out. Try again.");
  });

  it('routeChange_movesFocusToHeading', async () => {
    const fixture = await createShell(fakeTpo);

    await router.navigateByUrl('/tpo');
    await fixture.whenStable();

    expect(document.activeElement?.tagName).toBe('H1');
    expect(document.activeElement?.textContent).toBe('TPO desk');
  });

  it('shell_hasExactlyOneStatusRegionThatSpeaksAnnouncements', async () => {
    const fixture = await createShell(fakeStudent);

    TestBed.inject(Announcer).announce('Join request sent.');
    await fixture.whenStable();

    const regions = fixture.nativeElement.querySelectorAll('[role="status"]');
    expect(regions.length).toBe(1);
    expect(regions[0].textContent).toContain('Join request sent.');
  });

  it('skipLink_movesFocusToMain', async () => {
    const fixture = await createShell(fakeStudent);

    (fixture.nativeElement.querySelector('.skip-link') as HTMLAnchorElement).click();

    expect(document.activeElement?.id).toBe('main');
  });
});
