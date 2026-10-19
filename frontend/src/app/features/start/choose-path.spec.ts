import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { fakeStudent } from '../../../testing/fakes';
import { answerSessionProbe } from '../../../testing/session-helpers';
import { PathChoice } from '../../core/auth/path-choice';
import { ChoosePath } from './choose-path';

@Component({ template: '<h1>Stub</h1>' })
class Stub {}

describe('ChoosePath', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([
          { path: 'start', component: ChoosePath },
          { path: 'join', component: Stub },
          { path: 'app', component: Stub },
        ]),
      ],
    });
  });

  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    localStorage.clear();
  });

  async function openStart(): Promise<{ page: HTMLElement; harness: RouterTestingHarness }> {
    await answerSessionProbe(fakeStudent);
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/start', ChoosePath);
    return { page: harness.routeNativeElement as HTMLElement, harness };
  }

  it('joinMyCollege_linksToJoin', async () => {
    const { page } = await openStart();

    const join = Array.from(page.querySelectorAll('a')).find((a) =>
      a.textContent?.includes('Join my college'),
    );
    expect(join?.getAttribute('href')).toBe('/join');
  });

  it('continueOnMyOwn_remembersChoiceAndOpensApp', async () => {
    const { page, harness } = await openStart();

    const button = Array.from(page.querySelectorAll('button')).find((b) =>
      b.textContent?.includes('Continue on my own'),
    );
    button!.click();
    await harness.fixture.whenStable();

    expect(TestBed.inject(Router).url).toBe('/app');
    expect(TestBed.inject(PathChoice).hasChosenOwnPath(fakeStudent.userId)).toBe(true);
  });
});
