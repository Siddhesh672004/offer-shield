import { HttpErrorResponse } from '@angular/common/http';
import { isSessionEnded, problemCode, problemDetail, readProblem } from './problem';

function httpError(status: number, body: unknown): HttpErrorResponse {
  return new HttpErrorResponse({ status, error: body, url: '/api/memberships' });
}

const conflictBody = {
  type: 'about:blank',
  title: 'Conflict',
  status: 409,
  detail: 'You already have a join request.',
  instance: '/api/memberships',
  errorCode: 'MEMBERSHIP_EXISTS',
  requestId: 'req-1',
};

describe('problem', () => {
  it('readProblem_problemBody_returnsFields', () => {
    expect(readProblem(httpError(409, conflictBody))).toEqual(conflictBody);
  });

  it('problemCode_problemBody_returnsErrorCode', () => {
    expect(problemCode(httpError(409, conflictBody))).toBe('MEMBERSHIP_EXISTS');
  });

  it('problemDetail_problemBody_returnsDetail', () => {
    expect(problemDetail(httpError(409, conflictBody), 'fallback')).toBe(
      'You already have a join request.',
    );
  });

  it('problemDetail_textBody_returnsFallback', () => {
    expect(problemDetail(httpError(502, 'Bad Gateway'), 'Try again.')).toBe('Try again.');
  });

  it('problemCode_nonStringCode_returnsUndefined', () => {
    expect(problemCode(httpError(400, { errorCode: 42 }))).toBeUndefined();
  });

  it('readProblem_notAnHttpError_returnsUndefined', () => {
    expect(readProblem(new Error('boom'))).toBeUndefined();
  });

  it('isSessionEnded_401_true', () => {
    expect(isSessionEnded(httpError(401, { errorCode: 'UNAUTHORIZED' }))).toBe(true);
  });

  it('isSessionEnded_403_false', () => {
    expect(isSessionEnded(httpError(403, { errorCode: 'FORBIDDEN' }))).toBe(false);
  });

  it('isSessionEnded_notAnHttpError_false', () => {
    expect(isSessionEnded(new Error('boom'))).toBe(false);
  });
});
