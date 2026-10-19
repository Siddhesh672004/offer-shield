import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Notice } from './notice';

@Component({
  imports: [Notice],
  template: `<app-notice tone="danger">Sign-in didn't complete. Try again.</app-notice>`,
})
class Host {}

describe('Notice', () => {
  it('notice_projectsText', async () => {
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();

    expect(fixture.nativeElement.textContent).toContain("Sign-in didn't complete. Try again.");
  });

  it('notice_dangerTone_marksToneAndShowsShape', async () => {
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();
    const notice: HTMLElement = fixture.nativeElement.querySelector('app-notice');

    expect(notice.classList).toContain('notice--danger');
    expect(notice.querySelector('svg[aria-hidden="true"]')).not.toBeNull();
  });
});
