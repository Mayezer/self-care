import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  imports: [RouterLink],
  template: `
    <div class="page-width page-content">
      <section class="card">
        <h1 tabindex="-1">Página não encontrada</h1>
        <p>Confira o endereço ou volte ao cronograma capilar.</p>
        <a routerLink="/">Voltar ao cronograma</a>
      </section>
    </div>
  `,
})
export class NotFound {}
