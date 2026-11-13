import { ViewportScroller } from '@angular/common';
import { afterNextRender, Component, computed, DOCUMENT, inject, Injector, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';

const THEMES = [
  { value: 'light', label: 'Jasmin', icon: '🌼' },
  { value: 'dark', label: 'Noite Intensa', icon: '🌙' },
  { value: 'pink', label: 'Pétala', icon: '🌸' },
] as const;

@Component({
  selector: 'app-root',
  imports: [RouterLink, RouterOutlet],
  templateUrl: './shell.html',
})
export class Shell {
  private readonly document = inject(DOCUMENT);
  private readonly injector = inject(Injector);
  private readonly router = inject(Router);
  private readonly url = toSignal(this.router.events.pipe(
    filter(event => event instanceof NavigationEnd),
    map(event => event.urlAfterRedirects),
  ), { initialValue: '' });
  private hasPage = false;

  protected readonly themes = THEMES;
  protected readonly theme = signal(
    THEMES.find(({ value }) => value === this.document.documentElement.getAttribute('data-theme')) ?? THEMES[0],
  );

  protected readonly page = computed(() => this.url().split(/[/?#]/)[1] ?? '');
  protected readonly stacked = computed(() => this.page() === 'novo-cuidado');
  protected readonly backLink = computed(() => this.router.parseUrl(this.url()).queryParams['modo'] === 'realizado'
    ? '/historico' : '/agenda');
  protected readonly pageName = computed(() => {
    if (this.stacked()) return 'Novo Cuidado';
    if (this.page() === 'perfil') return 'Perfil';
    if (this.page() === 'forum') return 'Fórum';
    if (this.page() === 'meu-cabelo') return 'Meu cabelo';
    if (this.page() === 'diario-capilar') return 'Diário capilar';
    if (this.page() === 'configuracoes') return 'Configurações';
    return this.tabs.find(tab => tab.path === '/' + this.page())?.text ?? 'Cronograma';
  });
  protected readonly tabs = [
    { path: '/agenda', text: 'Agenda', icon: 'calendar_today' },
    { path: '/', text: 'Cronograma', icon: 'spa' },
    { path: '/historico', text: 'Histórico', icon: 'history' },
    { path: '/ajuda', text: 'Ajuda', icon: 'help' },
  ];

  constructor() {
    inject(ViewportScroller).setOffset(() => {
      const root = this.document.documentElement;
      const padding = this.document.defaultView?.getComputedStyle(root).scrollPaddingBlockStart;
      return [0, Number.parseFloat(padding ?? '') || 0];
    });
  }

  protected activeTab(path: string): boolean {
    return path === '/' + this.page() || path === '/' && this.page() === 'cronogramas';
  }

  protected changeTheme(value: string): void {
    const theme = THEMES.find(option => option.value === value);
    if (!theme) return;

    this.theme.set(theme);
    this.document.documentElement.dataset['theme'] = theme.value;
    try {
      this.document.defaultView?.localStorage.setItem('autocuidado.theme', theme.value);
    } catch {
      // Keep the active theme when the browser blocks storage.
    }
  }

  protected focusPage(content: HTMLElement): void {
    if (this.hasPage) {
      afterNextRender(() => {
        if (!content.contains(content.ownerDocument.activeElement)) {
          (content.querySelector<HTMLElement>('h1') ?? content).focus();
        }
      }, { injector: this.injector });
    }
    this.hasPage = true;
  }
}
