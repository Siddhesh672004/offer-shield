import { TestBed } from '@angular/core/testing';
import { ReportSignal } from './report-models';
import { SignalList } from './signal-list';

function signal(overrides: Partial<ReportSignal>): ReportSignal {
  return {
    code: 'C9',
    title: 'Pressure language',
    outcome: 'PASSED',
    points: 0,
    hardOverride: false,
    explanation: 'No pressure phrases were found.',
    evidence: null,
    sourceKind: 1,
    aiExtracted: false,
    ...overrides,
  };
}

describe('SignalList', () => {
  async function render(signals: ReportSignal[]): Promise<HTMLElement> {
    const fixture = TestBed.createComponent(SignalList);
    fixture.componentRef.setInput('signals', signals);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('groups_flagsFirst_unavailableNext_othersFolded', async () => {
    const list = await render([
      signal({ code: 'C2', title: 'Free email sender' }),
      signal({ code: 'C5', title: 'Young domain', outcome: 'UNAVAILABLE', sourceKind: 2 }),
      signal({
        code: 'C1',
        title: 'Money request',
        outcome: 'TRIGGERED',
        hardOverride: true,
        evidence: 'pay a deposit of Rs. 5,000',
      }),
    ]);

    const headings = Array.from(list.querySelectorAll('h3')).map((h) => h.textContent?.trim());
    expect(headings).toEqual(['Red flags (1)', "Checks that couldn't run (1)"]);
    expect(list.querySelector('.signal--triggered')?.textContent).toContain('Sets High');
    expect(list.querySelector('.signal--unavailable')?.textContent).toContain('Not counted');
    const folded = list.querySelector('details')!;
    expect(folded.open).toBe(false);
    expect(folded.textContent).toContain('Free email sender');
  });

  it('everySignal_showsSourceKind_andAiTagWhenAiExtracted', async () => {
    const list = await render([
      signal({
        code: 'C3',
        title: 'Domain mismatch',
        outcome: 'TRIGGERED',
        points: 20,
        sourceKind: 3,
        aiExtracted: true,
      }),
      signal({ code: 'C12', title: 'Reported by users (not verified)', sourceKind: 5 }),
    ]);

    const items = Array.from(list.querySelectorAll('li'));
    expect(items[0].textContent).toContain('Source 3: curated lists');
    expect(items[0].textContent).toContain('AI-extracted');
    expect(items[0].textContent).toContain('+20 points');
    expect(items[1].textContent).toContain('Source 5: user reports');
    expect(items[1].textContent).not.toContain('AI-extracted');
  });

  it('evidence_isTextNotHtml', async () => {
    const list = await render([
      signal({ outcome: 'TRIGGERED', points: 10, evidence: 'join <b>now</b> or lose the seat' }),
    ]);

    const quote = list.querySelector('blockquote')!;
    expect(quote.querySelector('b')).toBeNull();
    expect(quote.textContent).toBe('join <b>now</b> or lose the seat');
  });

  it('noFlags_saysSo', async () => {
    const list = await render([signal({})]);

    expect(list.textContent).toContain('None of the checks found a red flag.');
  });
});
