import { Pipe, PipeTransform } from '@angular/core';

/**
 * Dates, rupees and waits in Indian English (02 §9: `en-IN` formats). Built on `Intl`, so no
 * Angular locale data is bundled. Times use the browser's time zone.
 */
const DATE = new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
const DATE_TIME = new Intl.DateTimeFormat('en-IN', {
  day: 'numeric',
  month: 'short',
  year: 'numeric',
  hour: 'numeric',
  minute: '2-digit',
});
const RUPEES = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  maximumFractionDigits: 0,
});
const NUMBER = new Intl.NumberFormat('en-IN');

function parse(iso: string | null | undefined): Date | null {
  if (!iso) {
    return null;
  }
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? null : date;
}

/** "20 Oct 2026"; empty for a missing or invalid date. */
export function formatDate(iso: string | null | undefined): string {
  const date = parse(iso);
  return date ? DATE.format(date) : '';
}

/** "20 Oct 2026, 3:45 pm"; empty for a missing or invalid date. */
export function formatDateTime(iso: string | null | undefined): string {
  const date = parse(iso);
  return date ? DATE_TIME.format(date) : '';
}

/** "₹3,00,000" (Indian digit grouping, whole rupees). */
export function formatRupees(amount: number): string {
  return RUPEES.format(amount);
}

/** "20,000" (Indian digit grouping). */
export function formatNumber(value: number): string {
  return NUMBER.format(value);
}

/** A `Retry-After` wait in words: "45 seconds", "about 7 minutes", "about 3 hours". */
export function formatWait(seconds: number): string {
  const whole = Math.max(1, Math.ceil(seconds));
  if (whole < 60) {
    return `${whole} ${whole === 1 ? 'second' : 'seconds'}`;
  }
  if (whole < 3600) {
    const minutes = Math.ceil(whole / 60);
    return `about ${minutes} ${minutes === 1 ? 'minute' : 'minutes'}`;
  }
  const hours = Math.ceil(whole / 3600);
  return `about ${hours} ${hours === 1 ? 'hour' : 'hours'}`;
}

/** `{{ iso | appDate }}` → "20 Oct 2026". */
@Pipe({ name: 'appDate' })
export class AppDatePipe implements PipeTransform {
  transform(iso: string | null | undefined): string {
    return formatDate(iso);
  }
}

/** `{{ iso | appDateTime }}` → "20 Oct 2026, 3:45 pm". */
@Pipe({ name: 'appDateTime' })
export class AppDateTimePipe implements PipeTransform {
  transform(iso: string | null | undefined): string {
    return formatDateTime(iso);
  }
}

/** `{{ 3000 | appRupees }}` → "₹3,000". */
@Pipe({ name: 'appRupees' })
export class AppRupeesPipe implements PipeTransform {
  transform(amount: number): string {
    return formatRupees(amount);
  }
}
