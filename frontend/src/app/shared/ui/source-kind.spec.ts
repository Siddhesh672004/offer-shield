import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { SourceKind, SourceKindTag } from './source-kind';

@Component({
  imports: [SourceKindTag],
  template: `<app-source-kind [kind]="kind()" [aiExtracted]="ai()" />`,
})
class Host {
  readonly kind = signal<SourceKind>(1);
  readonly ai = signal(false);
}

describe('SourceKindTag', () => {
  async function render(kind: SourceKind, ai = false): Promise<HTMLElement> {
    const fixture = TestBed.createComponent(Host);
    fixture.componentInstance.kind.set(kind);
    fixture.componentInstance.ai.set(ai);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it.each([
    [1 as const, 'Source 1: the letter itself'],
    [2 as const, 'Source 2: public registries'],
    [3 as const, 'Source 3: curated lists'],
    [4 as const, 'Source 4: confirmed reports'],
    [5 as const, 'Source 5: user reports'],
  ])('sourceKind_%s_showsNumberAndPlainName', async (kind, text) => {
    const root = await render(kind);

    expect(root.textContent).toContain(text);
    expect(root.textContent).not.toContain('AI-extracted');
  });

  it('sourceKind_aiExtracted_isLabelled', async () => {
    const root = await render(1, true);

    expect(root.textContent).toContain('AI-extracted');
  });
});
