import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Button } from './button';

@Component({
  imports: [Button],
  template: `
    <button appButton type="button" class="join-submit" [loading]="busy()">Send</button>
    <a appButton variant="secondary" size="lg" href="/somewhere">Open</a>
  `,
})
class Host {
  readonly busy = signal(false);
}

describe('Button', () => {
  async function render() {
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;
    return {
      fixture,
      button: root.querySelector('button')!,
      link: root.querySelector('a')!,
    };
  }

  it('button_default_isPrimaryAndKeepsItsOwnClasses', async () => {
    const { button } = await render();

    expect(button.classList).toContain('button');
    expect(button.classList).toContain('button--primary');
    expect(button.classList).toContain('join-submit');
    expect(button.textContent?.trim()).toBe('Send');
  });

  it('button_onLink_appliesVariantAndSize', async () => {
    const { link } = await render();

    expect(link.classList).toContain('button--secondary');
    expect(link.classList).toContain('button--lg');
    expect(link.getAttribute('href')).toBe('/somewhere');
  });

  it('button_loading_marksBusyAndShowsDecorativeSpinner', async () => {
    const { fixture, button } = await render();

    expect(button.getAttribute('aria-busy')).toBeNull();
    fixture.componentInstance.busy.set(true);
    await fixture.whenStable();

    expect(button.getAttribute('aria-busy')).toBe('true');
    expect(button.querySelector('.button__spinner[aria-hidden="true"]')).not.toBeNull();
    expect(button.textContent?.trim()).toBe('Send');
  });
});
