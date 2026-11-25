import { HttpClient } from '@angular/common/http';
import { Service, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** `KnownCompanyResponse`: a company and its official domains (registrable, lower case, sorted). */
export interface KnownCompany {
  readonly id: string;
  readonly name: string;
  readonly domains: readonly string[];
}

/**
 * The admin-only global known-company list (PRD F8, D-24). Errors carry an `errorCode`:
 * VALIDATION_FAILED, INVALID_DOMAIN (400), FREE_MAIL_DOMAIN, TOO_MANY_DOMAINS (422),
 * COMPANY_EXISTS, DOMAIN_TAKEN, LAST_DOMAIN (409), or 404 for a company that is gone.
 */
@Service()
export class KnownCompaniesApi {
  private readonly http = inject(HttpClient);

  list(): Observable<KnownCompany[]> {
    return this.http.get<KnownCompany[]>('/api/known-companies');
  }

  create(name: string, domains: readonly string[]): Observable<KnownCompany> {
    return this.http.post<KnownCompany>('/api/known-companies', { name, domains });
  }

  remove(company: KnownCompany): Observable<unknown> {
    return this.http.delete(`/api/known-companies/${encodeURIComponent(company.id)}`);
  }

  addDomain(company: KnownCompany, domain: string): Observable<KnownCompany> {
    return this.http.post<KnownCompany>(
      `/api/known-companies/${encodeURIComponent(company.id)}/domains`,
      { domain },
    );
  }

  removeDomain(company: KnownCompany, domain: string): Observable<KnownCompany> {
    return this.http.delete<KnownCompany>(
      `/api/known-companies/${encodeURIComponent(company.id)}/domains/${encodeURIComponent(domain)}`,
    );
  }
}
