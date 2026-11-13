// Replaced at build time. The development server and test runner default to no indexing.
declare const AUTOCUIDADO_SITE_ORIGIN: string;
declare const AUTOCUIDADO_PUBLISH_GUIDES: boolean;

export const SITE_ORIGIN = typeof AUTOCUIDADO_SITE_ORIGIN === 'string' ? AUTOCUIDADO_SITE_ORIGIN : '';
export const PUBLISH_GUIDES = typeof AUTOCUIDADO_PUBLISH_GUIDES === 'boolean' && AUTOCUIDADO_PUBLISH_GUIDES;

export interface EditorialPage {
  path: string;
  title: string;
  description: string;
}

export const SOCIAL_IMAGE = {
  path: '/design/agenda-social.png',
  alt: 'Agenda de autocuidado, com um calendário ilustrado',
};

export const PLANNER_PAGE: EditorialPage = {
  path: '/',
  title: 'Cronograma capilar | Agenda de autocuidado',
  description: 'Monte seu ciclo de cuidados. Escolha a frequência, confira as etapas na prévia e salve na sua agenda.',
};
