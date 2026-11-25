import { HttpErrorResponse } from '@angular/common/http';
import { Component, ElementRef, Injector, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormField, FormRoot, form, validate } from '@angular/forms/signals';
import { firstValueFrom } from 'rxjs';
import { Announcer } from '../../../core/a11y/announcer';
import { focusAfterRender } from '../../../core/a11y/focus';
import { isSessionEnded, problemCode, problemViolations } from '../../../core/http/problem';
import { filledIn, firstError } from '../../../shared/forms/form-errors';
import { Button } from '../../../shared/ui/button';
import { ConfirmStep } from '../../../shared/ui/confirm-step';
import { EmptyState } from '../../../shared/ui/empty-state';
import { Field, fieldDescribedBy } from '../../../shared/ui/field';
import { Skeleton } from '../../../shared/ui/loading';
import { Notice, NoticeTone } from '../../../shared/ui/notice';
import { PageHeader } from '../../../shared/ui/page-header';
import { KnownCompaniesApi, KnownCompany } from './companies-api';

interface Outcome {
  readonly tone: NoticeTone;
  readonly text: string;
}

const MAX_DOMAINS = 10;
const NAME_PATTERN = /^[\p{L}\p{N}][\p{L}\p{N} &.,'()-]*$/u;
const NAME_RULE = "Use 2 to 100 characters: letters, digits, spaces and & . , ' ( ) -";
const NOT_A_DOMAIN = 'Enter domains only, like example.com: not email addresses, links or paths.';
const REFRESHED = 'The list has been refreshed.';

/** What each domain refusal means (PRD F8, D-08, D-24), by `errorCode`. */
const DOMAIN_PROBLEMS: Readonly<Record<string, string>> = {
  INVALID_DOMAIN:
    'Enter a public domain name such as example.com: no @, http://, path, port or IP address.',
  FREE_MAIL_DOMAIN:
    "A free email service's domain (like gmail.com) can't be a company's official domain.",
  DOMAIN_TAKEN: 'This domain is already listed for a company.',
  TOO_MANY_DOMAINS: `A company can have at most ${MAX_DOMAINS} official domains.`,
};

/** "  Fabrikam   Fake " → "Fabrikam Fake" (the server stores it the same way). */
function cleanName(raw: string): string {
  return raw.trim().replace(/\s+/g, ' ');
}

/** One domain per line (commas and spaces also split); blanks dropped, repeats removed. */
function parseDomains(raw: string): string[] {
  const seen = new Set<string>();
  const domains: string[] = [];
  for (const part of raw.split(/[\s,]+/)) {
    const key = part.toLowerCase();
    if (part !== '' && !seen.has(key)) {
      seen.add(key);
      domains.push(part);
    }
  }
  return domains;
}

function looksLikeDomain(value: string): boolean {
  return !/[@/:]/.test(value);
}

/**
 * /admin/companies (PRD F8, §12 "Global known companies", D-24): genuine employers and their
 * official domains, which power the look-alike checks C3/C4. Add a company with its domains,
 * add or remove a domain (a company keeps at least one), delete a company behind a confirm step.
 * The server validates every domain (SSRF guard rules, no free-mail) and maps each refusal to an
 * `errorCode` shown next to the field.
 */
@Component({
  selector: 'app-known-companies',
  imports: [
    Button,
    ConfirmStep,
    EmptyState,
    Field,
    FormField,
    FormRoot,
    Notice,
    PageHeader,
    Skeleton,
  ],
  templateUrl: './known-companies.html',
  styleUrl: './known-companies.css',
})
export class KnownCompanies {
  private readonly api = inject(KnownCompaniesApi);
  private readonly announcer = inject(Announcer);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  protected readonly maxDomains = MAX_DOMAINS;
  protected readonly companies = rxResource({ stream: () => this.api.list() });
  protected readonly list = computed(() =>
    this.companies.hasValue() ? this.companies.value() : [],
  );
  /** What is being sent ("create", "add:<id>", "remove:<id>:<domain>", "delete:<id>"). */
  protected readonly busy = signal<string | null>(null);
  protected readonly outcome = signal<Outcome | null>(null);
  protected readonly deletingId = signal<string | null>(null);
  protected readonly addingTo = signal<string | null>(null);

  protected readonly createOutcome = signal<Outcome | null>(null);
  private readonly serverErrors = signal<{ name?: string; domains?: string }>({});
  protected readonly companyModel = signal({ name: '', domains: '' });
  protected readonly companyForm = form(
    this.companyModel,
    (path) => {
      filledIn(path.name, 'Enter the company name.');
      validate(path.name, ({ value }) => {
        const name = cleanName(value());
        return name !== '' && (name.length < 2 || name.length > 100 || !NAME_PATTERN.test(name))
          ? { kind: 'pattern', message: NAME_RULE }
          : undefined;
      });
      validate(path.domains, ({ value }) => {
        const domains = parseDomains(value());
        if (domains.length === 0) {
          return { kind: 'required', message: 'Enter at least one official domain.' };
        }
        if (!domains.every(looksLikeDomain)) {
          return { kind: 'domain', message: NOT_A_DOMAIN };
        }
        return domains.length > MAX_DOMAINS
          ? { kind: 'maxItems', message: `Enter up to ${MAX_DOMAINS} domains.` }
          : undefined;
      });
    },
    {
      submission: {
        action: async () => {
          await this.create();
          return undefined;
        },
        onInvalid: () => this.checkFields(),
      },
    },
  );
  protected readonly nameError = computed(
    () => firstError(this.companyForm.name()) ?? this.serverErrors().name ?? null,
  );
  protected readonly domainsError = computed(
    () => firstError(this.companyForm.domains()) ?? this.serverErrors().domains ?? null,
  );

  private readonly serverDomainError = signal<string | null>(null);
  protected readonly domainModel = signal({ domain: '' });
  protected readonly domainForm = form(
    this.domainModel,
    (path) => {
      filledIn(path.domain, 'Enter a domain, like example.com.');
      validate(path.domain, ({ value }) =>
        looksLikeDomain(value().trim()) ? undefined : { kind: 'domain', message: NOT_A_DOMAIN },
      );
    },
    {
      submission: {
        action: async () => {
          await this.addDomain();
          return undefined;
        },
        onInvalid: () => this.focus('#new-domain'),
      },
    },
  );
  protected readonly newDomainError = computed(
    () => firstError(this.domainForm.domain()) ?? this.serverDomainError(),
  );

  protected describedBy(fieldId: string, hint: boolean, error: string | null): string | null {
    return fieldDescribedBy(fieldId, { hint, error: error !== null });
  }

  protected startAdd(company: KnownCompany): void {
    this.addingTo.set(company.id);
    this.serverDomainError.set(null);
    this.domainForm().reset({ domain: '' });
    this.focus('#new-domain');
  }

  protected cancelAdd(company: KnownCompany): void {
    this.addingTo.set(null);
    this.focus(`#company-${company.id} .company__add`);
  }

  protected cancelDelete(company: KnownCompany): void {
    this.deletingId.set(null);
    this.focus(`#company-${company.id} .company__delete`);
  }

  private async create(): Promise<void> {
    if (this.busy() !== null) {
      return;
    }
    this.busy.set('create');
    this.createOutcome.set(null);
    this.serverErrors.set({});
    const name = cleanName(this.companyModel().name);
    try {
      const created = await firstValueFrom(
        this.api.create(name, parseDomains(this.companyModel().domains)),
      );
      this.companyForm().reset({ name: '', domains: '' });
      await this.refresh();
      const text = `Added ${created.name} with ${created.domains.join(', ')}.`;
      this.createOutcome.set({ tone: 'success', text });
      this.announcer.announce(text);
      this.focus('#company-outcome');
    } catch (error) {
      if (!isSessionEnded(error)) {
        this.createFailed(error);
      }
    } finally {
      this.busy.set(null);
    }
  }

  private createFailed(error: unknown): void {
    const code = problemCode(error);
    const errors: { name?: string; domains?: string } = {};
    if (code === 'COMPANY_EXISTS') {
      errors.name = 'This company is already on the list.';
    } else if (code === 'DOMAIN_TAKEN') {
      errors.domains = 'One of these domains is already listed for a company.';
    } else if (code && DOMAIN_PROBLEMS[code]) {
      errors.domains = DOMAIN_PROBLEMS[code];
    } else if (code === 'VALIDATION_FAILED') {
      for (const violation of problemViolations(error)) {
        if (violation.field === 'name') {
          errors.name = NAME_RULE;
        } else if (violation.field.startsWith('domains')) {
          errors.domains = `Enter 1 to ${MAX_DOMAINS} domains, like example.com.`;
        }
      }
    }
    if (errors.name || errors.domains) {
      this.serverErrors.set(errors);
      this.checkFields();
      return;
    }
    const text = failure(error, "Couldn't add the company. Try again.");
    this.createOutcome.set({ tone: 'danger', text });
    this.announcer.announce(text);
    this.focus('#company-outcome');
  }

  private async addDomain(): Promise<void> {
    const company = this.list().find((item) => item.id === this.addingTo());
    if (!company || this.busy() !== null) {
      return;
    }
    this.busy.set(`add:${company.id}`);
    this.serverDomainError.set(null);
    this.outcome.set(null);
    const domain = this.domainModel().domain.trim();
    try {
      this.replace(await firstValueFrom(this.api.addDomain(company, domain)));
      this.addingTo.set(null);
      this.announcer.announce(`Added ${domain} to ${company.name}.`);
      this.focus(`#company-${company.id} .company__add`, `#company-${company.id}-name`);
    } catch (error) {
      if (isSessionEnded(error)) {
        return;
      }
      const code = problemCode(error);
      if (isStatus(error, 404)) {
        this.addingTo.set(null);
        await this.stale(`This company is no longer on the list. ${REFRESHED}`);
      } else {
        this.serverDomainError.set(
          (code && DOMAIN_PROBLEMS[code]) || failure(error, "Couldn't add the domain. Try again."),
        );
        this.announcer.announce(this.serverDomainError() ?? '');
        this.focus('#new-domain');
      }
    } finally {
      this.busy.set(null);
    }
  }

  protected async removeDomain(company: KnownCompany, domain: string): Promise<void> {
    if (this.busy() !== null) {
      return;
    }
    this.busy.set(`remove:${company.id}:${domain}`);
    this.outcome.set(null);
    try {
      this.replace(await firstValueFrom(this.api.removeDomain(company, domain)));
      this.announcer.announce(`Removed ${domain} from ${company.name}.`);
      this.focus(`#company-${company.id}-name`);
    } catch (error) {
      if (isSessionEnded(error)) {
        return;
      }
      if (problemCode(error) === 'LAST_DOMAIN') {
        await this.stale(
          `${company.name} needs at least one official domain. Delete the company instead. ${REFRESHED}`,
        );
      } else if (isStatus(error, 404)) {
        await this.stale(`This domain is no longer on the list. ${REFRESHED}`);
      } else {
        this.show({
          tone: 'danger',
          text: failure(error, "Couldn't remove the domain. Try again."),
        });
      }
    } finally {
      this.busy.set(null);
    }
  }

  protected async deleteCompany(company: KnownCompany): Promise<void> {
    if (this.busy() !== null) {
      return;
    }
    this.deletingId.set(null);
    this.busy.set(`delete:${company.id}`);
    this.outcome.set(null);
    const list = this.list();
    const index = list.findIndex((item) => item.id === company.id);
    const next = list[index + 1] ?? list[index - 1];
    try {
      await firstValueFrom(this.api.remove(company));
      this.companies.set(list.filter((item) => item.id !== company.id));
      this.announcer.announce(`Deleted ${company.name} and its domains.`);
      this.focus(next ? `#company-${next.id}-name` : '.empty-state__heading');
    } catch (error) {
      if (isSessionEnded(error)) {
        return;
      }
      if (isStatus(error, 404)) {
        await this.stale(`This company is no longer on the list. ${REFRESHED}`);
      } else {
        this.show({
          tone: 'danger',
          text: failure(error, "Couldn't delete the company. Try again."),
        });
      }
    } finally {
      this.busy.set(null);
    }
  }

  private replace(updated: KnownCompany): void {
    this.companies.set(this.list().map((item) => (item.id === updated.id ? updated : item)));
  }

  private async stale(text: string): Promise<void> {
    this.show({ tone: 'warn', text });
    await this.refresh();
  }

  private async refresh(): Promise<void> {
    try {
      this.companies.set(await firstValueFrom(this.api.list()));
    } catch (error) {
      if (!isSessionEnded(error)) {
        this.companies.reload();
      }
    }
  }

  private show(outcome: Outcome): void {
    this.outcome.set(outcome);
    this.announcer.announce(outcome.text);
    this.focus('#companies-outcome');
  }

  private checkFields(): void {
    this.announcer.announce('Check the details marked below.');
    this.focus(this.nameError() ? '#company-name' : '#company-domains');
  }

  private focus(...selectors: string[]): void {
    focusAfterRender(this.host.nativeElement, this.injector, ...selectors);
  }
}

function isStatus(error: unknown, status: number): boolean {
  return error instanceof HttpErrorResponse && error.status === status;
}

function failure(error: unknown, fallback: string): string {
  if (isStatus(error, 403)) {
    return 'Only an admin can do this. Reload the page to check your role.';
  }
  if (isStatus(error, 0)) {
    return "Couldn't reach OfferShield. Check your connection and try again.";
  }
  return fallback;
}
