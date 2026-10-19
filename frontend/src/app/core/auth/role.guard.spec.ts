import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { fakeAdmin, fakeStudent, fakeTpo } from '../../../testing/fakes';
import { answerSessionProbe } from '../../../testing/session-helpers';
import { roleGuard } from './role.guard';

@Component({ template: '<h1>TPO desk</h1>' })
class TpoStub {}

@Component({ template: '<h1>Admin</h1>' })
class AdminStub {}

@Component({ template: '<h1>Page not found</h1>' })
class NotFoundStub {}

describe('roleGuard', () => {
  let http: HttpTestingController;
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([
          { path: 'tpo', canMatch: [roleGuard('TPO')], component: TpoStub },
          { path: 'admin', canMatch: [roleGuard('ADMIN')], component: AdminStub },
          { path: '**', component: NotFoundStub },
        ]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
  });

  afterEach(() => http.verify());

  it('tpo_matchesTpoRoute', async () => {
    await answerSessionProbe(fakeTpo);
    const harness = await RouterTestingHarness.create();

    const page = await harness.navigateByUrl('/tpo');

    expect(page).toBeInstanceOf(TpoStub);
  });

  it('student_doesNotMatchTpoRoute', async () => {
    await answerSessionProbe(fakeStudent);
    const harness = await RouterTestingHarness.create();

    const page = await harness.navigateByUrl('/tpo');

    expect(page).toBeInstanceOf(NotFoundStub);
  });

  it('tpo_doesNotMatchAdminRoute', async () => {
    await answerSessionProbe(fakeTpo);
    const harness = await RouterTestingHarness.create();

    const page = await harness.navigateByUrl('/admin');

    expect(page).toBeInstanceOf(NotFoundStub);
  });

  it('admin_matchesAdminRoute', async () => {
    await answerSessionProbe(fakeAdmin);
    const harness = await RouterTestingHarness.create();

    const page = await harness.navigateByUrl('/admin');

    expect(page).toBeInstanceOf(AdminStub);
    expect(router.url).toBe('/admin');
  });

  it('signedOut_doesNotMatch', async () => {
    await answerSessionProbe('signed-out');
    const harness = await RouterTestingHarness.create();

    const page = await harness.navigateByUrl('/admin');

    expect(page).toBeInstanceOf(NotFoundStub);
  });
});
