import { ViewportScroller } from '@angular/common';
import { afterNextRender, Component, computed, DOCUMENT, inject, Injector, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';
import { FallingDecor } from './falling-decor';
import { AccountProfileState } from './account-profile-state';

const THEMES = [
  { value: 'light', label: 'Jasmin', image: '/design/themes/jasmin.svg' },
  { value: 'dark', label: 'Noite Intensa', image: '/design/themes/noite.svg' },
  { value: 'pink', label: 'Pétala', image: '/design/themes/petala.svg' },
] as const;

@Component({
  selector: 'app-root',
  imports: [RouterLink, RouterOutlet, FallingDecor],
  templateUrl: './shell.html',
})
export class Shell {
  protected readonly profileState = inject(AccountProfileState);
  private readonly document = inject(DOCUMENT);
  private readonly injector = inject(Injector);
  private readonly router = inject(Router);
  private readonly url = toSignal(this.router.events.pipe(
    filter(event => event instanceof NavigationEnd),
    map(event => event.urlAfterRedirects),
  ), { initialValue: '' });
  private hasPage = false;

  protected readonly themes = THEMES;
  protected readonly theme = signal<typeof THEMES[number]>(THEMES[0]);
  protected readonly page = computed(() => this.url().split(/[/?#]/)[1] ?? '');
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
    afterNextRender(() => {
      // The early theme script sets the palette before paint; sync the hydrated controls afterwards.
      const theme = THEMES.find(({ value }) => value === this.document.documentElement.dataset['theme']);
      if (theme) this.theme.set(theme);
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
      // Keep the active preference when storage is blocked.
    }
  }

  protected closeThemeMenu(menu: HTMLDetailsElement, trigger: HTMLElement): void {
    if (!menu.open) return;
    menu.open = false;
    trigger.focus();
  }

  protected outsideThemeMenu(event: Event, menu: HTMLDetailsElement): void {
    if (menu.open && !event.composedPath().includes(menu)) menu.open = false;
  }

  protected leaveThemeMenu(event: FocusEvent, menu: HTMLDetailsElement): void {
    if (menu.open && event.relatedTarget && !menu.contains(event.relatedTarget as Node)) menu.open = false;
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
