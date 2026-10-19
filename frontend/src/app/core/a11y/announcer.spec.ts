import { TestBed } from '@angular/core/testing';
import { Announcer } from './announcer';

describe('Announcer', () => {
  it('announce_setsMessage', () => {
    const announcer = TestBed.inject(Announcer);

    announcer.announce('Join request sent.');

    expect(announcer.message()).toBe('Join request sent.');
  });

  it('announce_sameMessageTwice_changesTextSoItIsReadAgain', () => {
    const announcer = TestBed.inject(Announcer);
    announcer.announce('Try again.');
    const first = announcer.message();

    announcer.announce('Try again.');

    expect(announcer.message()).not.toBe(first);
    expect(announcer.message().trim()).toBe('Try again.');
  });
});
