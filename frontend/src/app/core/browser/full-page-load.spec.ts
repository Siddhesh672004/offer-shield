import { sameOriginPath } from './full-page-load';

describe('sameOriginPath', () => {
  it.each(['/', '/join', '/app?login=failed', '/tpo/pending#top'])(
    'sameOriginPath_relativePath_%s_kept',
    (path) => {
      expect(sameOriginPath(path)).toBe(path);
    },
  );

  it.each([
    ['protocol-relative', '//evil.example/join'],
    ['absolute URL', 'https://evil.example/join'],
    ['script URL', 'javascript:alert(1)'],
    ['backslash trick', '/\\evil.example'],
    ['tab trick', '/\t/evil.example'],
    ['newline trick', '/\n/evil.example'],
    ['no leading slash', 'join'],
    ['empty', ''],
  ])('sameOriginPath_%s_rejected', (_label, path) => {
    expect(sameOriginPath(path)).toBeNull();
  });

  it('sameOriginPath_notAString_rejected', () => {
    expect(sameOriginPath(undefined)).toBeNull();
    expect(sameOriginPath(42)).toBeNull();
  });
});
