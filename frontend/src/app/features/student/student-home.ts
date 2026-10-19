import { Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Session } from '../../core/auth/session';

/** /app landing page. Upload, reports and "My submissions" arrive in P12; for now it shows the college status. */
@Component({
  selector: 'app-student-home',
  imports: [RouterLink],
  template: `
    <h1>Your offers</h1>
    <p class="lead">
      This is where you'll upload an offer letter and read its risk report. Uploads aren't open yet.
    </p>

    @if (me(); as me) {
      @if (me.role !== 'ADMIN') {
        <section class="college" aria-labelledby="college-heading">
          <h2 id="college-heading">Your college</h2>
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
                <a class="button" routerLink="/join">Join my college</a>
              }
            }
          }
        </section>
      }
    }
  `,
  styles: `
    .college {
      margin-top: var(--space-6);
      padding-top: var(--space-5);
      border-top: 1px solid var(--color-rule);
    }
  `,
})
export class StudentHome {
  private readonly session = inject(Session);

  protected readonly me = this.session.me;
  protected readonly collegeName = computed(() => this.me()?.college?.name ?? 'your college');
}
