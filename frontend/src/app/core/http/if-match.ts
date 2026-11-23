import { HttpHeaders } from '@angular/common/http';

/**
 * `If-Match: "<version>"` for a decision (the ETag form the API sends back). A stale version
 * answers 409, a missing header 428.
 */
export function ifMatch(version: number): HttpHeaders {
  return new HttpHeaders({ 'If-Match': `"${version}"` });
}
