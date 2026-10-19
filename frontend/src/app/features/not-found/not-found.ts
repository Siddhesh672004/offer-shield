import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/** Shown for unknown paths and for areas the account can't open (a wrong role doesn't match). */
@Component({
  selector: 'app-not-found',
  imports: [RouterLink],
  template: `
    <h1>Page not found</h1>
    <p>This address doesn't exist, or it isn't available for your account.</p>
    <a class="link-action" routerLink="/">Go to the start page</a>
  `,
})
export class NotFound {}
