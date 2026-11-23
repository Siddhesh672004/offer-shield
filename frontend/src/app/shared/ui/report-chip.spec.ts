import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ReportChip, ReportStatus } from './report-chip';

@Component({
  imports: [ReportChip],
  template: `<app-report-chip [status]="status()" [reporters]="reporters()" />`,
})
class Host {
  readonly status = signal<ReportStatus>('reported');
  readonly reporters = signal(3);
}

describe('ReportChip', () => {
  async function render(status: ReportStatus, reporters = 3): Promise<HTMLElement> {
    const fixture = TestBed.createComponent(Host);
    fixture.componentInstance.status.set(status);
    fixture.componentInstance.reporters.set(reporters);
    await fixture.whenStable();
    return fixture.nativeElement.querySelector('app-report-chip') as HTMLElement;
  }

  it('reportChip_reported_saysNotVerifiedWithCount', async () => {
    const chip = await render('reported', 3);

    expect(chip.textContent?.trim()).toBe('Reported as a scam by 3 users (not verified)');
    expect(chip.classList).toContain('report-chip--reported');
  });

  it('reportChip_oneReporter_usesSingular', async () => {
    const chip = await render('reported', 1);

    expect(chip.textContent?.trim()).toBe('Reported as a scam by 1 user (not verified)');
  });

  it('reportChip_confirmed_isWordedAndStyledDistinctly', async () => {
    const chip = await render('confirmed');

    expect(chip.textContent?.trim()).toBe('Confirmed scam');
    expect(chip.classList).toContain('report-chip--confirmed');
    expect(chip.classList).not.toContain('report-chip--reported');
  });

  it('reportChip_shapesDiffer', async () => {
    const reported = await render('reported');
    const confirmed = await render('confirmed');

    expect(reported.querySelector('svg')?.getAttribute('data-shape')).toBe('dashed-circle');
    expect(confirmed.querySelector('svg')?.getAttribute('data-shape')).toBe('octagon');
  });
});
