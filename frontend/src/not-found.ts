import { PageHeading } from './page-heading';
import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  imports: [PageHeading, RouterLink],
  template: `
    <div class="page-width page-content">
      <header appPageHeading="help">
        <h1 tabindex="-1">Página não encontrada</h1>
        <p>Confira o endereço ou volte ao cronograma capilar.</p>
        <a pageHeadingAction class="secondary" routerLink="/">Voltar ao cronograma</a>
      </header>
    </div>
  `,
})
export class NotFound {}
