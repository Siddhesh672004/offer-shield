import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { RISK_BAND_MESSAGES, RiskBand, RiskBandIndicator } from './risk-band';

@Component({
  imports: [RiskBandIndicator],
  template: `<app-risk-band [band]="band()" [size]="size()" [score]="score()" />`,
})
class Host {
  readonly band = signal<RiskBand>('LOW');
  readonly size = signal<'sm' | 'lg'>('sm');
  readonly score = signal<number | null>(null);
}

describe('RiskBandIndicator', () => {
  async function render(band: RiskBand, size: 'sm' | 'lg' = 'sm', score: number | null = null) {
    const fixture = TestBed.createComponent(Host);
    fixture.componentInstance.band.set(band);
    fixture.componentInstance.size.set(size);
    fixture.componentInstance.score.set(score);
    await fixture.whenStable();
    return fixture.nativeElement.querySelector('app-risk-band') as HTMLElement;
  }

  it.each([
    ['LOW' as const, 'Low risk', 'circle'],
    ['MEDIUM' as const, 'Medium risk', 'triangle'],
    ['HIGH' as const, 'High risk', 'hexagon'],
  ])('riskBand_%s_showsWordShapeAndColour', async (band, word, shape) => {
    const indicator = await render(band);

    expect(indicator.textContent).toContain(word);
    expect(
      indicator.querySelector(`svg[data-shape="${shape}"][aria-hidden="true"]`),
    ).not.toBeNull();
    expect(indicator.classList).toContain(`risk-band--${band.toLowerCase()}`);
  });

  it('riskBand_large_showsPrdBandMessageVerbatim', async () => {
    const indicator = await render('MEDIUM', 'lg');

    expect(indicator.textContent).toContain(
      "Warning signs. Verify through the company's official website or careers page.",
    );
  });

  it('riskBand_small_hidesMessage', async () => {
    const indicator = await render('HIGH');

    expect(indicator.textContent).not.toContain('Strong scam indicators');
  });

  it('riskBand_score_isShownOutOf100', async () => {
    const indicator = await render('HIGH', 'lg', 86);

    expect(indicator.textContent).toContain('86');
    expect(indicator.textContent).toContain('/ 100');
  });

  it('riskBandMessages_matchPrd72', () => {
    expect(RISK_BAND_MESSAGES).toEqual({
      LOW: 'No major red flags found. This does not prove the offer is genuine.',
      MEDIUM: "Warning signs. Verify through the company's official website or careers page.",
      HIGH: 'Strong scam indicators. Do not pay anything.',
    });
  });

  it('riskBandMessages_neverPromiseSafety', () => {
    for (const message of Object.values(RISK_BAND_MESSAGES)) {
      expect(message).not.toMatch(/\b(verified|safe|legit|guaranteed)\b/i);
    }
  });
});
