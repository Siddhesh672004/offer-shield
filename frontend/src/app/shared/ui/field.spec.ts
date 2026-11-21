import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Field, fieldDescribedBy } from './field';

@Component({
  imports: [Field],
  template: `
    <app-field
      label="Roll number"
      fieldId="roll-no"
      hint="As printed on your college ID card."
      [error]="error()"
    >
      <input id="roll-no" class="input" [attr.aria-describedby]="describedBy()" />
    </app-field>
  `,
})
class Host {
  readonly error = signal<string | null>(null);

  describedBy(): string | null {
    return fieldDescribedBy('roll-no', { hint: true, error: this.error() !== null });
  }
}

describe('Field', () => {
  async function render(error: string | null = null): Promise<HTMLElement> {
    const fixture = TestBed.createComponent(Host);
    fixture.componentInstance.error.set(error);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('field_labelPointsAtControl', async () => {
    const root = await render();

    expect(root.querySelector('label')?.getAttribute('for')).toBe('roll-no');
    expect(root.querySelector('#roll-no-hint')?.textContent).toContain(
      'As printed on your college ID card.',
    );
    expect(root.querySelector('#roll-no-error')).toBeNull();
  });

  it('field_error_isShownWithConventionalId', async () => {
    const root = await render('Enter your roll number.');

    expect(root.querySelector('#roll-no-error')?.textContent).toContain('Enter your roll number.');
    expect(root.querySelector('#roll-no')?.getAttribute('aria-describedby')).toBe(
      'roll-no-hint roll-no-error',
    );
  });

  it('fieldDescribedBy_nothingToDescribe_isNull', () => {
    expect(fieldDescribedBy('roll-no', { hint: false, error: false })).toBeNull();
  });
});
