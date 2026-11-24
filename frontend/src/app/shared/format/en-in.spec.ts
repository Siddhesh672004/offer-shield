import { formatDate, formatDateTime, formatNumber, formatRupees, formatWait } from './en-in';

describe('en-IN formatting', () => {
  // Midday UTC, so the calendar day is the same in India and in UTC.
  const noonUtc = '2026-10-20T06:30:00Z';

  it('formatDate_iso_dayMonthYear', () => {
    expect(formatDate(noonUtc)).toBe('20 Oct 2026');
  });

  it('formatDateTime_iso_includesTime', () => {
    const text = formatDateTime(noonUtc);

    expect(text).toMatch(/^20 Oct 2026, \d{1,2}:\d{2} (am|pm)$/);
  });

  it('formatDate_nullOrInvalid_empty', () => {
    expect(formatDate(null)).toBe('');
    expect(formatDateTime('not a date')).toBe('');
  });

  it('formatRupees_indianGrouping', () => {
    expect(formatRupees(300000)).toBe('₹3,00,000');
    expect(formatRupees(5000)).toBe('₹5,000');
  });

  it('formatNumber_indianGrouping', () => {
    expect(formatNumber(20000)).toBe('20,000');
    expect(formatNumber(1500000)).toBe('15,00,000');
  });

  it.each([
    [1, '1 second'],
    [45, '45 seconds'],
    [60, 'about 1 minute'],
    [61, 'about 2 minutes'],
    [420, 'about 7 minutes'],
    [3600, 'about 1 hour'],
    [10_000, 'about 3 hours'],
  ])('formatWait_%i_%s', (seconds, text) => {
    expect(formatWait(seconds)).toBe(text);
  });
});
