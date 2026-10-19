import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { fakeStudent, fakeTpo } from '../../../testing/fakes';
import { answerSessionProbe } from '../../../testing/session-helpers';
import { authGuard } from './auth.guard';
import { PathChoice, choosePathGuard } from './path-choice';

@Component({ template: '<h1>Choose path</h1>' })
class StartStub {}

@Component({ template: '<h1>Student area</h1>' })
class AppStub {}

describe('choosePathGuard', () => {
  let http: HttpTestingController;
  let router: Router;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([
          { path: 'login', component: StartStub },
          { path: 'start', component: StartStub },
          { path: 'app', canMatch: [authGuard, choosePathGuard], component: AppStub },
        ]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
  });

  afterEach(() => {
    http.verify();
    vi.restoreAllMocks();
    localStorage.clear();
  });

  async function open(url: string): Promise<string> {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url);
    return router.url;
  }

  it('newStudent_redirectsToStart', async () => {
    await answerSessionProbe(fakeStudent);

    expect(await open('/app')).toBe('/start');
  });

  it('studentWhoChoseOwnPath_opensApp', async () => {
    await answerSessionProbe(fakeStudent);
    TestBed.inject(PathChoice).chooseOwnPath(fakeStudent.userId);

    expect(await open('/app')).toBe('/app');
  });

  it('choiceOfAnotherUser_stillRedirects', async () => {
    await answerSessionProbe(fakeStudent);
    TestBed.inject(PathChoice).chooseOwnPath('someone-else');

    expect(await open('/app')).toBe('/start');
  });

  it('studentWithJoinRequest_opensApp', async () => {
    await answerSessionProbe({ ...fakeStudent, membershipStatus: 'PENDING' });

    expect(await open('/app')).toBe('/app');
  });

  it('tpo_opensApp', async () => {
    await answerSessionProbe(fakeTpo);

    expect(await open('/app')).toBe('/app');
  });

  it('signedOut_leftToAuthGuard', async () => {
    await answerSessionProbe('signed-out');

    expect(await open('/app')).toBe('/login');
  });

  it('chooseOwnPath_storageBlocked_remembersForThisVisit', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    const choice = TestBed.inject(PathChoice);

    choice.chooseOwnPath(fakeStudent.userId);

    expect(choice.hasChosenOwnPath(fakeStudent.userId)).toBe(true);
  });
});
