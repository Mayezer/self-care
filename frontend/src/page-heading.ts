import { ChangeDetectionStrategy, Component, input } from '@angular/core';

@Component({
  selector: 'header[appPageHeading]',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'page-heading' },
  template: `
    <span class="page-heading-icon" aria-hidden="true">
      @if (appPageHeading() === 'account-access') {
        <img src="/design/themes/acesso.svg" alt="" width="40" height="40">
      } @else {
        <span class="material-symbol">{{ appPageHeading() }}</span>
      }
    </span>
    <div class="page-heading-copy"><ng-content /></div>
    <div class="page-heading-actions"><ng-content select="[pageHeadingAction]" /></div>
  `,
})
export class PageHeading {
  readonly appPageHeading = input.required<string>();
}
