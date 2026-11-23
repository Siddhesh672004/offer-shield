import { Component, input } from '@angular/core';

/**
 * The top of every page: the page's only `<h1>` (the shell focuses it after each route
 * change), an optional description (projected), and optional actions (`pageHeaderActions`).
 *
 *   <app-page-header heading="Your offers">
 *     <p>Upload an offer letter and read its risk report.</p>
 *     <a appButton pageHeaderActions routerLink="/app/upload">Upload a letter</a>
 *   </app-page-header>
 */
@Component({
  selector: 'app-page-header',
  host: { class: 'page-header' },
  template: `
    <div class="page-header__text">
      <h1>{{ heading() }}</h1>
      <div class="page-header__description"><ng-content /></div>
    </div>
    <div class="page-header__actions"><ng-content select="[pageHeaderActions]" /></div>
  `,
  styles: `
    :host {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      justify-content: space-between;
      gap: var(--space-4) var(--space-6);
      margin-bottom: var(--space-6);
    }
    .page-header__text {
      flex: 1 1 28rem;
      min-width: 0;
    }
    h1 {
      margin-bottom: var(--space-2);
    }
    .page-header__description {
      color: var(--color-text-secondary);
      font-size: var(--text-md);
    }
    .page-header__description:empty,
    .page-header__actions:empty {
      display: none;
    }
    .page-header__actions {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-2);
    }
  `,
})
export class PageHeader {
  readonly heading = input.required<string>();
}
