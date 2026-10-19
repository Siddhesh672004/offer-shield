import {
  HttpClient,
  HttpContext,
  HttpErrorResponse,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { fakeStudent } from '../../../testing/fakes';
import { Session, SESSION_PROBE } from '../auth/session';
import { unauthorizedInterceptor } from './unauthorized.interceptor';

const unauthorized = { status: 401, statusText: 'Unauthorized' };

describe('unauthorizedInterceptor', () => {
  let client: HttpClient;
  let http: HttpTestingController;
  let navigate: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([unauthorizedInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    client = TestBed.inject(HttpClient);
    http = TestBed.inject(HttpTestingController);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
  });

  afterEach(() => http.verify());

  async function failWith(
    url: string,
    status: { status: number; statusText: string },
    context?: HttpContext,
  ) {
    const call = firstValueFrom(client.get(url, { context }));
    http.expectOne(url).flush({ status: status.status }, status);
    return call.then(
      () => undefined,
      (error: unknown) => error,
    );
  }

  it('api401_navigatesToLoginSayingSessionEnded', async () => {
    await failWith('/api/memberships/me', unauthorized);

    expect(navigate).toHaveBeenCalledWith('/login?session=ended');
  });

  it('api401_errorStillReachesCaller', async () => {
    const error = await failWith('/api/colleges', unauthorized);

    expect(error).toBeInstanceOf(HttpErrorResponse);
    expect((error as HttpErrorResponse).status).toBe(401);
  });

  it('api401_marksSessionSignedOut', async () => {
    const session = TestBed.inject(Session);
    const loaded = session.load();
    http.expectOne('/api/auth/me').flush(fakeStudent);
    await loaded;

    await failWith('/api/colleges', unauthorized);

    expect(session.signedIn()).toBe(false);
  });

  it('sessionProbe401_doesNotNavigate', async () => {
    await failWith('/api/auth/me', unauthorized, new HttpContext().set(SESSION_PROBE, true));

    expect(navigate).not.toHaveBeenCalled();
  });

  it('api403_doesNotNavigate', async () => {
    await failWith('/api/memberships', { status: 403, statusText: 'Forbidden' });

    expect(navigate).not.toHaveBeenCalled();
  });

  it('nonApi401_doesNotNavigate', async () => {
    await failWith('/assets/config.json', unauthorized);

    expect(navigate).not.toHaveBeenCalled();
  });

  it('lookalikeApiPrefix401_doesNotNavigate', async () => {
    await failWith('/apiary/hive', unauthorized);

    expect(navigate).not.toHaveBeenCalled();
  });
});
