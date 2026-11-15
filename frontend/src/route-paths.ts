export const CLIENT_PATHS = {
  plan: 'cronogramas/:planId',
  agenda: 'agenda',
  profile: 'perfil',
  forum: 'forum',
  forumModeration: 'forum/moderacao',
  forumTopic: 'forum/:topicId',
  hairProfile: 'meu-cabelo',
  products: 'meu-cabelo/produtos',
  diary: 'diario-capilar',
  reminders: 'configuracoes/lembretes',
  subscription: 'configuracoes/assinatura',
  newCare: 'novo-cuidado',
  vanity: 'penteadeira',
  history: 'historico',
  help: 'ajuda',
} as const;

export const GUIDE_PREFIX = 'ajuda/guias/';
export const NOT_FOUND_PATH = '404';
