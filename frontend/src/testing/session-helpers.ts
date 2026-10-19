import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Me, Session } from '../app/core/auth/session';

export type ProbeAnswer = Me | 'signed-out' | 'unreachable';

/** Loads the real Session store, answering its `GET /api/auth/me` probe. */
export async function answerSessionProbe(answer: ProbeAnswer): Promise<void> {
  const session = TestBed.inject(Session);
  const http = TestBed.inject(HttpTestingController);
  const loaded = session.load();
  const probe = http.expectOne('/api/auth/me');
  if (answer === 'signed-out') {
    probe.flush(
      { status: 401, errorCode: 'UNAUTHORIZED' },
      { status: 401, statusText: 'Unauthorized' },
    );
  } else if (answer === 'unreachable') {
    probe.flush('Bad Gateway', { status: 502, statusText: 'Bad Gateway' });
  } else {
    probe.flush(answer);
  }
  await loaded;
}
