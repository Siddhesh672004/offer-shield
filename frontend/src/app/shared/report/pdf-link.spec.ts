import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, Subject } from 'rxjs';
import { Announcer } from '../../core/a11y/announcer';
import { PdfLink as PdfLinkData } from './evidence-models';
import { PdfLinkButton } from './pdf-link';

const LINK: PdfLinkData = {
  url: 'https://res.cloudinary.example/fake/private/letter.pdf?signature=fake',
  expiresAt: '2026-10-20T07:05:00Z',
};

describe('PdfLinkButton', () => {
  let fixture: ComponentFixture<PdfLinkButton>;
  let answers: Subject<PdfLinkData>;
  let calls: number;

  function page(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  async function show(available = true): Promise<void> {
    answers = new Subject<PdfLinkData>();
    calls = 0;
    fixture = TestBed.createComponent(PdfLinkButton);
    fixture.componentRef.setInput('available', available);
    fixture.componentRef.setInput('fetch', (): Observable<PdfLinkData> => {
      calls++;
      return answers;
    });
    await fixture.whenStable();
  }

  /** The click handler awaits the answer outside Angular's view, so let it finish first. */
  async function settle(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve));
    await fixture.whenStable();
  }

  function getButton(): HTMLButtonElement | null {
    return page().querySelector<HTMLButtonElement>('button');
  }

  it('notAvailable_saysNoPdfStored_noButton', async () => {
    await show(false);

    expect(page().textContent).toContain('No PDF stored for this letter');
    expect(getButton()).toBeNull();
  });

  it('get_showsNewTabLinkWithNoopener_focusesIt', async () => {
    await show();

    getButton()!.click();
    await fixture.whenStable();
    expect(calls).toBe(1);
    expect(getButton()!.disabled).toBe(true);

    answers.next(LINK);
    answers.complete();
    await settle();

    const link = page().querySelector<HTMLAnchorElement>('a')!;
    expect(link.getAttribute('href')).toBe(LINK.url);
    expect(link.getAttribute('target')).toBe('_blank');
    expect(link.getAttribute('rel')).toBe('noopener noreferrer');
    expect(link.textContent).toContain('opens in a new tab');
    expect(document.activeElement).toBe(link);
    expect(page().textContent).toContain('Works until');
    expect(TestBed.inject(Announcer).message()).toContain('PDF link ready');
  });

  it('404PdfNotAvailable_saysNoPdfStored', async () => {
    await show();

    getButton()!.click();
    answers.error(
      new HttpErrorResponse({
        status: 404,
        error: { status: 404, errorCode: 'PDF_NOT_AVAILABLE', detail: 'x' },
      }),
    );
    await settle();

    expect(page().textContent).toContain('No PDF stored for this letter');
    expect(page().querySelector('a')).toBeNull();
  });

  it('otherError_offersTryAgain', async () => {
    await show();

    getButton()!.click();
    answers.error(new HttpErrorResponse({ status: 502 }));
    await settle();

    expect(page().textContent).toContain("Couldn't get the PDF link");
    expect(getButton()!.disabled).toBe(false);
  });

  it('getNewLink_asksAgain', async () => {
    await show();
    getButton()!.click();
    answers.next(LINK);
    await settle();
    expect(page().querySelector('a')).not.toBeNull();

    getButton()!.click();
    await fixture.whenStable();

    expect(calls).toBe(2);
  });
});
