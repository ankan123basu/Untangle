import { describe, expect, it } from 'vitest';
import { isStaleBuildError } from './stale-build';

/** A tab from before a deploy asks for chunks that are gone; we must recognise that error. */
describe('stale build detection', () => {
  it('recognises missing-chunk errors from every major browser', () => {
    expect(
      isStaleBuildError(
        new TypeError('Failed to fetch dynamically imported module: /assets/board-page-abc.js'),
      ),
    ).toBe(true); // Chrome
    expect(isStaleBuildError(new TypeError('Importing a module script failed.'))).toBe(true); // Safari
    expect(isStaleBuildError(new TypeError('error loading dynamically imported module'))).toBe(
      true,
    ); // Firefox
  });

  it('leaves ordinary errors alone', () => {
    expect(isStaleBuildError(new Error('Cannot read properties of undefined'))).toBe(false);
    expect(isStaleBuildError(null)).toBe(false);
  });
});
