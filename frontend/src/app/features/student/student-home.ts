import { Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Session } from '../../core/auth/session';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Notice } from '../../shared/ui/notice';
import { PageHeader } from '../../shared/ui/page-header';
import { MySubmissions } from './my-submissions';

/**
 * /app landing page: "My submissions" (each letter and its report) with the upload action for
 * student accounts, and the college status beside it.
 */
@Component({
  selector: 'app-student-home',
  imports: [Badge, Button, MySubmissions, Notice, PageHeader, RouterLink],
  template: `
    <app-page-header heading="Your offers">
      <p>Upload an offer letter and read its risk report, with the evidence behind every flag.</p>
      @if (canUpload()) {
        <a appButton pageHeaderActions routerLink="/app/upload">Check an offer letter</a>
      }
    </app-page-header>

    @if (me() && !canUpload()) {
      <app-notice tone="info">
        <p>Checking offer letters is for student accounts. TPO and admin accounts can't upload.</p>
      </app-notice>
    }

    <div class="home">
      <app-my-submissions [canUpload]="canUpload()" />

      @if (me(); as me) {
        @if (me.role !== 'ADMIN') {
          <section class="card college" aria-labelledby="college-heading">
            <div class="card__header">
              <h2 id="college-heading" class="card__title">Your college</h2>
              @if (me.role === 'USER') {
                @switch (me.membershipStatus) {
                  @case ('PENDING') {
                    <app-badge tone="warn" icon="clock">Pending</app-badge>
                  }
                  @case ('APPROVED') {
                    <app-badge tone="success" icon="check">Member</app-badge>
                  }
                  @case ('REJECTED') {
                    <app-badge tone="danger" icon="cross">Not approved</app-badge>
                  }
                  @default {
                    <app-badge>Not linked</app-badge>
                  }
                }
              }
            </div>
            @if (me.role === 'TPO') {
              <p>You're the TPO for {{ collegeName() }}.</p>
            } @else {
              @switch (me.membershipStatus) {
                @case ('PENDING') {
                  <p>Your request to join {{ collegeName() }} is waiting for your TPO.</p>
                  <a class="link-action" routerLink="/join">See your request</a>
                }
                @case ('APPROVED') {
                  <p>You're a member of {{ collegeName() }}.</p>
                }
                @case ('REJECTED') {
                  <p>Your request to join {{ collegeName() }} wasn't approved.</p>
                  <a class="link-action" routerLink="/join">See the reason or send a new request</a>
                }
                @default {
                  <p>
                    You're checking offers on your own. Join your college so your TPO can review an
                    offer with you.
                  </p>
                  <a appButton variant="secondary" routerLink="/join">Join my college</a>
                }
              }
            }
          </section>
        }
      }
    </div>
  `,
  styles: `
    .home {
      display: grid;
      gap: var(--space-5);
      align-items: start;
    }
    @media (min-width: 60rem) {
      .home {
        grid-template-columns: minmax(0, 1fr) 20rem;
      }
    }
    .college p {
      color: var(--color-text-secondary);
    }
  `,
})
export class StudentHome {
  private readonly session = inject(Session);

  protected readonly me = this.session.me;
  protected readonly collegeName = computed(() => this.me()?.college?.name ?? 'your college');
  /** Only student accounts may upload (`POST /api/submissions` is USER only). */
  protected readonly canUpload = computed(() => this.me()?.role === 'USER');
}
