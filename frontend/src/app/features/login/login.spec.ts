import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { fakeStudent } from '../../../testing/fakes';
import { ProbeAnswer, answerSessionProbe } from '../../../testing/session-helpers';
import { Announcer } from '../../core/a11y/announcer';
import { FullPageLoad } from '../../core/browser/full-page-load';
import { Login } from './login';

@Component({ template: '<h1>Your offers</h1>' })
class AppStub {}

@Component({ template: '<h1>Join my college</h1>' })
class JoinStub {}

const UNREACHABLE = "We can't reach OfferShield right now. Try again in a minute.";

describe('Login', () => {
  let load: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    load = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter(
          [
            { path: 'login', component: Login },
            { path: 'app', component: AppStub },
            { path: 'join', component: JoinStub },
          ],
          withComponentInputBinding(),
        ),
        { provide: FullPageLoad, useValue: { load } },
      ],
    });
  });

  afterEach(() => TestBed.inject(HttpTestingController).verify());

  async function openLogin(answer: ProbeAnswer | null, url = '/login'): Promise<HTMLElement> {
    if (answer) {
      await answerSessionProbe(answer);
    }
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url);
    await harness.fixture.whenStable();
    return harness.routeNativeElement as HTMLElement;
  }

  function signInLink(page: HTMLElement): HTMLAnchorElement | null {
    return page.querySelector('a[href="/oauth2/authorization/google"]');
  }

  function retryButton(page: HTMLElement): HTMLButtonElement | undefined {
    return Array.from(page.querySelectorAll('button')).find(
      (button) => button.textContent?.trim() === 'Try again',
    );
  }

  it('loginFailed_showsMessage', async () => {
    const page = await openLogin('signed-out', '/login?login=failed');

    expect(page.textContent).toContain("Sign-in didn't complete. Try again.");
    expect(TestBed.inject(Announcer).message()).toBe("Sign-in didn't complete. Try again.");
  });

  it('noFlag_noFailureMessage', async () => {
    const page = await openLogin('signed-out');

    expect(page.textContent).not.toContain("Sign-in didn't complete");
    expect(page.textContent).not.toContain('Your session ended');
  });

  it('sessionEnded_showsMessageAndSignIn', async () => {
    const page = await openLogin('signed-out', '/login?session=ended');

    expect(page.textContent).toContain('Your session ended. Sign in again.');
    expect(TestBed.inject(Announcer).message()).toBe('Your session ended. Sign in again.');
    expect(signInLink(page)).not.toBeNull();
  });

  it('signIn_isFullPageNavigationToGoogle', async () => {
    const page = await openLogin('signed-out');

    const link = Array.from(page.querySelectorAll('a')).find(
      (anchor) => anchor.textContent?.trim() === 'Sign in with Google',
    );
    expect(link?.getAttribute('href')).toBe('/oauth2/authorization/google');
  });

  it('sessionUnknown_showsCheckingInsteadOfSignIn', async () => {
    const page = await openLogin(null);

    expect(page.textContent).toContain('Checking your sign-in…');
    expect(signInLink(page)).toBeNull();
  });

  it('alreadySignedIn_goesToApp', async () => {
    await openLogin(fakeStudent);

    await vi.waitFor(() => expect(TestBed.inject(Router).url).toBe('/app'));
  });

  it('alreadySignedInWithSafeNext_goesToNext', async () => {
    await openLogin(fakeStudent, '/login?unreachable=1&next=%2Fjoin');

    await vi.waitFor(() => expect(TestBed.inject(Router).url).toBe('/join'));
  });

  it('alreadySignedInWithUnsafeNext_goesToApp', async () => {
    await openLogin(fakeStudent, '/login?next=%2F%2Fevil.example%2Fjoin');

    await vi.waitFor(() => expect(TestBed.inject(Router).url).toBe('/app'));
  });

  it('serverUnreachable_explainsWithRetryAndNoSignIn', async () => {
    const page = await openLogin('unreachable', '/login?unreachable=1&next=%2Fjoin');

    expect(page.textContent).toContain(UNREACHABLE);
    expect(TestBed.inject(Announcer).message()).toBe(UNREACHABLE);
    expect(signInLink(page)).toBeNull();
    expect(retryButton(page)).toBeDefined();
  });

  it('retry_reloadsNextPage', async () => {
    const page = await openLogin('unreachable', '/login?unreachable=1&next=%2Fjoin%3Fref%3Dmail');

    retryButton(page)!.click();

    expect(load).toHaveBeenCalledExactlyOnceWith('/join?ref=mail');
  });

  it.each([
    ['protocol-relative', '%2F%2Fevil.example%2Fjoin'],
    ['absolute', 'https%3A%2F%2Fevil.example%2Fjoin'],
    ['backslash', '%2F%5Cevil.example'],
  ])('retry_%sNext_reloadsStartPageInstead', async (_label, next) => {
    const page = await openLogin('unreachable', `/login?unreachable=1&next=${next}`);

    retryButton(page)!.click();

    expect(load).toHaveBeenCalledExactlyOnceWith('/');
  });

  it.each([
    ['signed-out' as const, '/login?login=failed&session=ended'],
    ['unreachable' as const, '/login?unreachable=1&next=%2Fapp'],
  ])('copy_%s_neverPromisesSafety', async (answer, url) => {
    const page = await openLogin(answer, url);

    expect(page.textContent).not.toMatch(/\b(verified|safe|legit|guaranteed)\b/i);
  });
});
