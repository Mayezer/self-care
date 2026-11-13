import { provideHttpClient } from '@angular/common/http';
import { ApplicationConfig } from '@angular/core';
import { provideClientHydration, withNoHttpTransferCache, withNoIncrementalHydration } from '@angular/platform-browser';
import { provideRouter, TitleStrategy, withInMemoryScrolling } from '@angular/router';
import { Agenda } from './agenda';
import { App } from './app';
import { allowGuide, Help, loadGuidesAvailability } from './help';
import { HelpGuide } from './help-guide';
import { HELP_GUIDES } from './help-guides';
import { NotFound } from './not-found';
import { PLANNER_PAGE, PUBLISH_GUIDES } from './publication';
import { CLIENT_PATHS, GUIDE_PREFIX, NOT_FOUND_PATH } from './route-paths';
import { SavedHairPlan } from './saved-hair-plan';
import { SeoTitleStrategy } from './seo-title';

const notFound = { component: NotFound, title: 'Página não encontrada | Agenda de autocuidado' };

export const appConfig: ApplicationConfig = {
  providers: [provideHttpClient(), provideClientHydration(withNoHttpTransferCache(), withNoIncrementalHydration()),
    { provide: TitleStrategy, useClass: SeoTitleStrategy }, provideRouter([
    { path: '', component: App, title: PLANNER_PAGE.title, data: { editorial: PLANNER_PAGE } },
    { path: CLIENT_PATHS.plan, component: SavedHairPlan, title: 'Cronograma salvo | Agenda de autocuidado' },
    { path: CLIENT_PATHS.agenda, component: Agenda, title: 'Agenda | Agenda de autocuidado' },
    { path: CLIENT_PATHS.profile, component: Agenda, data: { view: 'profile' }, title: 'Perfil | Agenda de autocuidado' },
    { path: CLIENT_PATHS.forum, component: Agenda, data: { view: 'forum' }, title: 'Fórum | Agenda de autocuidado' },
    { path: CLIENT_PATHS.hairProfile, component: Agenda, data: { view: 'hair-profile' }, title: 'Meu cabelo | Agenda de autocuidado' },
    { path: CLIENT_PATHS.products, component: Agenda, data: { view: 'hair-products' }, title: 'Produtos · Meu cabelo | Agenda de autocuidado' },
    { path: CLIENT_PATHS.diary, component: Agenda, data: { view: 'hair-diary' }, title: 'Diário capilar | Agenda de autocuidado' },
    { path: CLIENT_PATHS.reminders, component: Agenda, data: { view: 'reminders' }, title: 'Lembretes | Agenda de autocuidado' },
    { path: CLIENT_PATHS.subscription, component: Agenda, data: { view: 'subscription' }, title: 'Assinatura | Agenda de autocuidado' },
    { path: CLIENT_PATHS.newCare, component: Agenda, data: { view: 'new-care' }, title: 'Novo cuidado | Agenda de autocuidado' },
    { path: CLIENT_PATHS.history, component: Agenda, data: { view: 'history' }, title: 'Histórico | Agenda de autocuidado' },
    { path: CLIENT_PATHS.help, component: Help, title: 'Ajuda | Agenda de autocuidado',
      resolve: { guidesAvailable: loadGuidesAvailability } },
    ...HELP_GUIDES.map(guide => ({
      path: GUIDE_PREFIX + guide.id,
      component: HelpGuide,
      canActivate: PUBLISH_GUIDES ? [] : [allowGuide],
      title: guide.title + ' | Agenda de autocuidado',
      data: { guide, editorial: PUBLISH_GUIDES ? {
        path: '/' + GUIDE_PREFIX + guide.id, title: guide.title + ' | Agenda de autocuidado', description: guide.summary,
      } : undefined },
    })),
    { path: NOT_FOUND_PATH, ...notFound },
    { path: '**', ...notFound },
  ], withInMemoryScrolling({ anchorScrolling: 'enabled' }))],
};
