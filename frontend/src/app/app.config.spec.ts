import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { appConfig } from './app.config';
import { FullPageLoad } from './core/browser/full-page-load';

describe('appConfig', () => {
  afterEach(() => sessionStorage.clear());

  it('staleLazyChunk_reloadsTargetPage', async () => {
    sessionStorage.clear();
    const load = vi.fn();
    TestBed.configureTestingModule({
      providers: [...appConfig.providers, { provide: FullPageLoad, useValue: { load } }],
    });
    const router = TestBed.inject(Router);
    router.resetConfig([
      {
        path: 'join',
        loadComponent: () =>
          Promise.reject(new TypeError('Failed to fetch dynamically imported module: /chunk-X.js')),
      },
    ]);

    await router.navigateByUrl('/join').catch(() => undefined);

    expect(load).toHaveBeenCalledExactlyOnceWith('/join');
  });
});
