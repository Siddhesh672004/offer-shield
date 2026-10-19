import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { fakeStudent } from '../../../testing/fakes';
import { ProbeAnswer, answerSessionProbe } from '../../../testing/session-helpers';
import { Announcer } from '../../core/a11y/announcer';
import { Login } from './login';

describe('Login', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'login', component: Login }], withComponentInputBinding()),
      ],
    });
  });

  afterEach(() => TestBed.inject(HttpTestingController).verify());

  async function openLogin(answer: ProbeAnswer, url = '/login'): Promise<HTMLElement> {
    await answerSessionProbe(answer);
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url, Login);
    await harness.fixture.whenStable();
    return harness.routeNativeElement as HTMLElement;
  }

  it('loginFailed_showsMessage', async () => {
    const page = await openLogin('signed-out', '/login?login=failed');

    expect(page.textContent).toContain("Sign-in didn't complete. Try again.");
    expect(TestBed.inject(Announcer).message()).toBe("Sign-in didn't complete. Try again.");
  });

  it('noFlag_noFailureMessage', async () => {
    const page = await openLogin('signed-out');

    expect(page.textContent).not.toContain("Sign-in didn't complete");
  });

  it('signIn_isFullPageNavigationToGoogle', async () => {
    const page = await openLogin('signed-out');

    const link = Array.from(page.querySelectorAll('a')).find(
      (anchor) => anchor.textContent?.trim() === 'Sign in with Google',
    );
    expect(link?.getAttribute('href')).toBe('/oauth2/authorization/google');
  });

  it('alreadySignedIn_offersToContinue', async () => {
    const page = await openLogin(fakeStudent);

    expect(page.textContent).toContain("You're signed in as Asha Test.");
    expect(page.querySelector('a[href="/app"]')).not.toBeNull();
    expect(page.querySelector('a[href="/oauth2/authorization/google"]')).toBeNull();
  });

  it('serverUnreachable_explains', async () => {
    const page = await openLogin('unreachable');

    expect(page.textContent).toContain("We couldn't reach OfferShield.");
  });

  it('copy_neverPromisesSafety', async () => {
    const page = await openLogin('signed-out', '/login?login=failed');

    expect(page.textContent).not.toMatch(/\b(verified|safe|legit|guaranteed)\b/i);
  });
});
