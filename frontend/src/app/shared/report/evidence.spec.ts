import { ComponentFixture, TestBed } from '@angular/core/testing';
import { claimsSafety, fakeEvidence, fakeMarker } from '../../../testing/staff-fakes';
import { Evidence } from './evidence';
import { Marker, ReportEvidence } from './evidence-models';

describe('Evidence', () => {
  let fixture: ComponentFixture<Evidence>;
  let reverted: Marker[];

  function page(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  async function show(evidence: ReportEvidence = fakeEvidence(), revertable = false) {
    fixture = TestBed.createComponent(Evidence);
    fixture.componentRef.setInput('evidence', evidence);
    fixture.componentRef.setInput('revertable', revertable);
    reverted = [];
    fixture.componentInstance.revert.subscribe((marker) => reverted.push(marker));
    await fixture.whenStable();
  }

  function markerRows(): HTMLElement[] {
    return Array.from(page().querySelectorAll<HTMLElement>('.marker'));
  }

  function button(root: ParentNode, label: string): HTMLButtonElement | undefined {
    return Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find((b) =>
      b.textContent?.trim().startsWith(label),
    );
  }

  it('band_wordShapeColour_withVerbatimMessageAndScore', async () => {
    await show();

    const band = page().querySelector('app-risk-band')!;
    expect(band.textContent).toContain('High risk');
    expect(band.textContent).toContain('Strong scam indicators. Do not pay anything.');
    expect(band.textContent).toContain('55');
    expect(band.querySelector('[data-shape="hexagon"]')).not.toBeNull();
  });

  it('signals_showEvidenceSourceKindAndAiLabel', async () => {
    await show();

    const text = page().textContent ?? '';
    expect(text).toContain('pay a refundable security deposit of Rs. 5,000 to [phone]');
    expect(text).toContain('AI-extracted');
    expect(page().querySelector('app-source-kind')).not.toBeNull();
  });

  it('markers_confirmedAndReportedLookDifferent', async () => {
    await show();

    const [domain, phone] = markerRows();
    expect(domain.textContent).toContain('northwind-careers.example');
    expect(domain.querySelector('app-report-chip.report-chip--reported')?.textContent).toContain(
      'Reported as a scam by 2 users (not verified)',
    );
    expect(domain.querySelector('app-report-chip.report-chip--confirmed')).toBeNull();
    expect(phone.textContent).toContain('Phone number');
    expect(phone.querySelector('app-report-chip.report-chip--confirmed')?.textContent).toContain(
      'Confirmed scam',
    );
  });

  it('phoneAndUpiMarkers_showMaskedHintAndWhoSuppliedIt', async () => {
    await show(
      fakeEvidence({
        markers: [
          fakeMarker({
            id: 'm-phone',
            type: 'PHONE',
            domain: null,
            source: 'TYPED',
            hint: '••••1234',
          }),
          fakeMarker({
            id: 'm-upi',
            type: 'UPI',
            domain: null,
            source: 'LETTER',
            hint: '••••ar@okaxis',
          }),
        ],
      }),
    );

    const [phone, upi] = markerRows();
    expect(phone.querySelector('.marker__value')?.textContent?.trim()).toBe('••••1234');
    expect(phone.querySelector('.marker__source')?.textContent?.trim()).toBe(
      'typed by the student',
    );
    expect(upi.querySelector('.marker__value')?.textContent?.trim()).toBe('••••ar@okaxis');
    expect(upi.querySelector('.marker__source')?.textContent?.trim()).toBe('in the letter');
  });

  it('domainAndWordingMarkers_noSourceLabel', async () => {
    await show(
      fakeEvidence({
        markers: [
          fakeMarker({ source: null, hint: null }),
          fakeMarker({ id: 'm-sim', type: 'SIMHASH', domain: null, source: null, hint: null }),
        ],
      }),
    );

    const [domain, wording] = markerRows();
    expect(domain.querySelector('.marker__value')?.textContent?.trim()).toBe(
      'northwind-careers.example',
    );
    expect(domain.querySelector('.marker__source')).toBeNull();
    expect(wording.textContent).toContain('Letter wording');
    expect(wording.querySelector('.marker__value')).toBeNull();
    expect(wording.querySelector('.marker__source')).toBeNull();
  });

  it('olderMarkersWithoutSourceOrHint_stillShownByKind', async () => {
    const {
      source: _source,
      hint: _hint,
      ...older
    } = fakeMarker({
      id: 'm-old',
      type: 'PHONE',
      domain: null,
    });
    await show(fakeEvidence({ markers: [older] }));

    const [phone] = markerRows();
    expect(phone.textContent).toContain('Phone number');
    expect(phone.querySelector('.marker__value')).toBeNull();
    expect(phone.querySelector('.marker__source')).toBeNull();
    expect(phone.querySelector('app-report-chip')).not.toBeNull();
  });

  it('rejectedMarker_shownAsNotCounted', async () => {
    await show(fakeEvidence({ markers: [fakeMarker({ status: 'REJECTED', reporterCount: 0 })] }));

    expect(markerRows()[0].textContent).toContain('Not counted');
    expect(markerRows()[0].querySelector('app-report-chip')).toBeNull();
  });

  it('noMarkers_saysSo', async () => {
    await show(fakeEvidence({ markers: [] }));

    expect(page().textContent).toContain('No scam markers');
  });

  it('partialAndOverride_explained', async () => {
    await show(fakeEvidence({ partial: true, hardOverride: true }));

    expect(page().textContent).toContain('Partial report');
    expect(page().textContent).toContain('Why High');
    expect(page().textContent).toContain('Money request');
  });

  it('notRevertable_noRevertButtons', async () => {
    await show(fakeEvidence(), false);

    expect(button(page(), 'Revert')).toBeUndefined();
  });

  it('revert_onlyOnConfirmed_needsConfirmStep', async () => {
    await show(fakeEvidence(), true);
    const [domain, phone] = markerRows();
    expect(button(domain, 'Revert')).toBeUndefined();

    button(phone, 'Revert')!.click();
    await fixture.whenStable();
    expect(reverted).toEqual([]);
    const confirm = phone.querySelector('app-confirm-step')!;
    expect(confirm.textContent).toContain('no longer show');
    expect(document.activeElement?.textContent?.trim()).toBe('Revert marker');

    button(confirm, 'Revert marker')!.click();
    await fixture.whenStable();

    expect(reverted.map((marker) => marker.id)).toEqual(['00000000-0000-0000-0000-0000000000a2']);
  });

  it('revert_cancel_returnsFocusToRevertButton', async () => {
    await show(fakeEvidence(), true);
    const phone = markerRows()[1];
    button(phone, 'Revert')!.click();
    await fixture.whenStable();

    button(phone.querySelector('app-confirm-step')!, 'Cancel')!.click();
    await fixture.whenStable();

    expect(phone.querySelector('app-confirm-step')).toBeNull();
    expect(document.activeElement?.textContent?.trim()).toContain('Revert');
    expect(reverted).toEqual([]);
  });

  it('revertingMarker_buttonBusyAndDisabled', async () => {
    await show(fakeEvidence(), true);
    fixture.componentRef.setInput('revertingId', '00000000-0000-0000-0000-0000000000a2');
    await fixture.whenStable();

    const revert = button(markerRows()[1], 'Revert')!;
    expect(revert.disabled).toBe(true);
    expect(revert.getAttribute('aria-busy')).toBe('true');
  });

  it('copy_neverClaimsSafety', async () => {
    await show(fakeEvidence({ band: 'LOW', bandMessage: null, score: 5 }), true);

    expect(claimsSafety(page().textContent ?? '')).toBe(false);
  });
});
