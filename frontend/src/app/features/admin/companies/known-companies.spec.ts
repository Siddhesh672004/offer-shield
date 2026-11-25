import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { claimsSafety } from '../../../../testing/staff-fakes';
import { Announcer } from '../../../core/a11y/announcer';
import { KnownCompany } from './companies-api';
import { KnownCompanies } from './known-companies';

const NORTHWIND: KnownCompany = {
  id: 'k1',
  name: 'Northwind Analytics',
  domains: ['northwind-analytics.example', 'northwind.example'],
};
const CONTOSO: KnownCompany = {
  id: 'k2',
  name: 'Contoso Fake Ltd',
  domains: ['contoso-fake.example'],
};
const FABRIKAM: KnownCompany = { id: 'k3', name: 'Fabrikam Fake', domains: ['fabrikam.example'] };

describe('KnownCompanies', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<KnownCompanies>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function page(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function announced(): string {
    return TestBed.inject(Announcer).message();
  }

  async function render(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve));
    await new Promise((resolve) => setTimeout(resolve));
  }

  async function settle(): Promise<void> {
    await render();
    await fixture.whenStable();
  }

  async function open(list: KnownCompany[] = [NORTHWIND, CONTOSO]): Promise<void> {
    fixture = TestBed.createComponent(KnownCompanies);
    await render();
    http.expectOne('/api/known-companies').flush(list);
    await settle();
  }

  function row(id: string): HTMLElement {
    return page().querySelector<HTMLElement>(`#company-${id}`)!;
  }

  function button(root: ParentNode, label: string): HTMLButtonElement | undefined {
    return Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find((item) =>
      item.textContent?.replace(/\s+/g, ' ').trim().startsWith(label),
    );
  }

  function type(selector: string, value: string): void {
    const control = page().querySelector<HTMLInputElement | HTMLTextAreaElement>(selector)!;
    control.value = value;
    control.dispatchEvent(new Event('input'));
  }

  async function create(name: string, domains: string): Promise<void> {
    type('#company-name', name);
    type('#company-domains', domains);
    await fixture.whenStable();
    button(page(), 'Add company')!.click();
    await fixture.whenStable();
  }

  async function addDomain(companyId: string, domain: string): Promise<void> {
    button(row(companyId), 'Add a domain')!.click();
    await settle();
    type('#new-domain', domain);
    await fixture.whenStable();
    button(row(companyId), 'Add domain')!.click();
    await fixture.whenStable();
  }

  function write(method: string): TestRequest {
    return http.expectOne((request) => request.method === method);
  }

  function problem(status: number, errorCode: string, extra: object = {}) {
    return [
      { status, errorCode, detail: 'Fake detail.', ...extra },
      { status, statusText: 'Error' },
    ] as const;
  }

  it('list_showsCompaniesAndTheirDomains', async () => {
    await open();

    expect(row('k1').querySelector('h3')?.textContent).toContain('Northwind Analytics');
    const domains = Array.from(row('k1').querySelectorAll('.domain__name')).map((d) =>
      d.textContent?.trim(),
    );
    expect(domains).toEqual(['northwind-analytics.example', 'northwind.example']);
    expect(page().textContent).toContain('Companies (2)');
  });

  it('lastDomain_hasNoRemoveButton', async () => {
    await open();

    expect(button(row('k1'), 'Remove')).toBeDefined();
    expect(button(row('k2'), 'Remove')).toBeUndefined();
  });

  it('create_postsCleanNameAndDomainsByLine_refreshes', async () => {
    await open();

    await create(
      '  Fabrikam   Fake ',
      'fabrikam.example\n careers.fabrikam.example \n\nFabrikam.example, fabrikam-jobs.example',
    );
    const request = write('POST');
    expect(request.request.url).toBe('/api/known-companies');
    expect(request.request.body).toEqual({
      name: 'Fabrikam Fake',
      domains: ['fabrikam.example', 'careers.fabrikam.example', 'fabrikam-jobs.example'],
    });
    request.flush(FABRIKAM, { status: 201, statusText: 'Created' });
    await settle();
    http.expectOne('/api/known-companies').flush([FABRIKAM, NORTHWIND, CONTOSO]);
    await settle();

    expect(row('k3')).not.toBeNull();
    expect(announced()).toContain('Added Fabrikam Fake');
    expect(page().querySelector<HTMLInputElement>('#company-name')!.value).toBe('');
  });

  it('create_clientChecks_noRequest', async () => {
    await open();

    const eleven = Array.from({ length: 11 }, (_, i) => `fake${i}.example`).join('\n');
    await create('F', eleven);
    await settle();
    expect(page().querySelector('#company-name-error')?.textContent).toContain('2 to 100');
    expect(page().querySelector('#company-domains-error')?.textContent).toContain('up to 10');

    await create('Fabrikam Fake', 'hr@fabrikam.example');
    await settle();
    expect(page().querySelector('#company-domains-error')?.textContent).toContain(
      'not email addresses',
    );

    await create('Fabrikam Fake', '  ');
    await settle();
    expect(page().querySelector('#company-domains-error')?.textContent).toContain('at least one');
    http.expectNone((request) => request.method === 'POST');
  });

  const createErrors: [string, number, string, string, object?][] = [
    [
      'VALIDATION_FAILED',
      400,
      '#company-name-error',
      '2 to 100 characters',
      { violations: [{ field: 'name', message: 'must start with a letter' }] },
    ],
    ['INVALID_DOMAIN', 400, '#company-domains-error', 'public domain name'],
    ['FREE_MAIL_DOMAIN', 422, '#company-domains-error', 'free email service'],
    ['COMPANY_EXISTS', 409, '#company-name-error', 'already on the list'],
    ['DOMAIN_TAKEN', 409, '#company-domains-error', 'already listed for a company'],
  ];

  for (const [code, status, selector, text, extra] of createErrors) {
    it(`create_${code}_shownOnTheField`, async () => {
      await open();

      await create('Fabrikam Fake', 'fabrikam.example');
      write('POST').flush(...problem(status, code, extra));
      await settle();

      expect(page().querySelector(selector)?.textContent).toContain(text);
      expect(announced()).toContain('Check the details');
    });
  }

  it('addDomain_postsToCompany_updatesRow', async () => {
    await open();

    await addDomain('k1', ' northwind-jobs.example ');
    const request = write('POST');
    expect(request.request.url).toBe('/api/known-companies/k1/domains');
    expect(request.request.body).toEqual({ domain: 'northwind-jobs.example' });
    request.flush({
      ...NORTHWIND,
      domains: ['northwind-analytics.example', 'northwind-jobs.example', 'northwind.example'],
    });
    await settle();

    expect(row('k1').querySelectorAll('.domain__name')).toHaveLength(3);
    expect(page().querySelector('#new-domain')).toBeNull();
    expect(announced()).toContain('Added northwind-jobs.example to Northwind Analytics');
  });

  const domainErrors: [string, number, string][] = [
    ['INVALID_DOMAIN', 400, 'public domain name'],
    ['FREE_MAIL_DOMAIN', 422, 'free email service'],
    ['DOMAIN_TAKEN', 409, 'already listed for a company'],
    ['TOO_MANY_DOMAINS', 422, 'at most 10'],
  ];

  for (const [code, status, text] of domainErrors) {
    it(`addDomain_${code}_shownOnTheField`, async () => {
      await open();

      await addDomain('k1', 'northwind-jobs.example');
      write('POST').flush(...problem(status, code));
      await settle();

      expect(page().querySelector('#new-domain-error')?.textContent).toContain(text);
      expect(document.activeElement?.id).toBe('new-domain');
    });
  }

  it('removeDomain_deletes_updatesRow', async () => {
    await open();

    const remove = Array.from(row('k1').querySelectorAll<HTMLButtonElement>('.domain button'))[1];
    remove.click();
    const request = write('DELETE');
    expect(request.request.url).toBe('/api/known-companies/k1/domains/northwind.example');
    request.flush({ ...NORTHWIND, domains: ['northwind-analytics.example'] });
    await settle();

    expect(row('k1').querySelectorAll('.domain__name')).toHaveLength(1);
    expect(announced()).toContain('Removed northwind.example');
  });

  it('removeDomain_lastDomain_explains_refreshes', async () => {
    await open();

    button(row('k1'), 'Remove')!.click();
    write('DELETE').flush(...problem(409, 'LAST_DOMAIN'));
    await settle();
    http
      .expectOne('/api/known-companies')
      .flush([{ ...NORTHWIND, domains: ['northwind.example'] }, CONTOSO]);
    await settle();

    expect(page().querySelector('#companies-outcome')?.textContent).toContain(
      'needs at least one official domain',
    );
    expect(button(row('k1'), 'Remove')).toBeUndefined();
  });

  it('deleteCompany_confirmStep_thenDeletes_focusesNext', async () => {
    await open();

    button(row('k1'), 'Delete')!.click();
    await settle();
    http.expectNone((request) => request.method === 'DELETE');
    const confirm = row('k1').querySelector('app-confirm-step')!;
    expect(confirm.textContent).toContain('Delete Northwind Analytics');
    expect(document.activeElement?.textContent?.trim()).toBe('Delete company');

    button(confirm, 'Delete company')!.click();
    const request = write('DELETE');
    expect(request.request.url).toBe('/api/known-companies/k1');
    request.flush(null, { status: 204, statusText: 'No Content' });
    await settle();

    expect(page().querySelector('#company-k1')).toBeNull();
    expect(announced()).toContain('Deleted Northwind Analytics');
    expect(document.activeElement?.id).toBe('company-k2-name');
  });

  it('deleteCompany_cancel_keepsIt_focusReturns', async () => {
    await open();

    button(row('k1'), 'Delete')!.click();
    await settle();
    button(row('k1').querySelector('app-confirm-step')!, 'Cancel')!.click();
    await settle();

    expect(row('k1').querySelector('app-confirm-step')).toBeNull();
    expect(document.activeElement?.textContent).toContain('Delete');
  });

  it('deleteCompany_404_refreshes', async () => {
    await open();

    button(row('k1'), 'Delete')!.click();
    await settle();
    button(row('k1').querySelector('app-confirm-step')!, 'Delete company')!.click();
    write('DELETE').flush(...problem(404, 'NOT_FOUND'));
    await settle();
    http.expectOne('/api/known-companies').flush([CONTOSO]);
    await settle();

    expect(page().querySelector('#companies-outcome')?.textContent).toContain(
      'no longer on the list',
    );
  });

  it('empty_explainsTheList', async () => {
    await open([]);

    expect(page().textContent).toContain('No known companies yet');
  });

  it('copy_neverClaimsSafety', async () => {
    await open();

    expect(claimsSafety(page().textContent ?? '')).toBe(false);
  });
});
