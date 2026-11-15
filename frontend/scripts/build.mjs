import { spawnSync } from 'node:child_process';
import { existsSync, writeFileSync } from 'node:fs';
import { HELP_GUIDES } from '../src/help-guides.ts';
import { PLANNER_PAGE, SOCIAL_IMAGE } from '../src/publication.ts';
import { CLIENT_PATHS, GUIDE_PREFIX, NOT_FOUND_PATH } from '../src/route-paths.ts';

const origin = process.env.PUBLIC_SITE_ORIGIN ?? '';
const guidesSetting = process.env.PUBLIC_SITE_GUIDES ?? 'false';
if (!['true', 'false'].includes(guidesSetting) ||
    (origin && !/^https:\/\/(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,63}$/.test(origin)) ||
    (guidesSetting === 'true' && !origin)) {
  throw new Error('Informe uma origem HTTPS canônica sem caminho ou porta para publicar páginas editoriais.');
}
const publishGuides = guidesSetting === 'true';
const result = spawnSync(process.execPath, [
  'node_modules/@angular/cli/bin/ng.js', 'build',
  '--define', `AUTOCUIDADO_SITE_ORIGIN=${JSON.stringify(origin)}`,
  '--define', `AUTOCUIDADO_PUBLISH_GUIDES=${publishGuides}`,
], { stdio: 'inherit' });
if (result.error) throw result.error;
if (result.status !== 0) process.exit(result.status ?? 1);

const build = 'dist/autocuidado';
const browser = `${build}/browser`;
const guides = HELP_GUIDES.map(({ id }) => GUIDE_PREFIX + id);
const publicRoutes = ['/', ...(publishGuides ? guides.map(path => '/' + path) : [])];
const exampleUuid = '00000000-0000-0000-0000-000000000001';
const uuidPattern = '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}';
const pages = [
  ['/', '/index.html'],
  ...Object.values(CLIENT_PATHS).map(path => [
    `"~^/${path.replace(':planId', '[1-9][0-9]*').replace(':topicId', uuidPattern)}/?$"`, '/index.csr.html',
  ]),
  ...guides.map(path => [`~^/${path}/?$`, publishGuides ? `/${path}/index.html` : '/index.csr.html']),
];
for (const file of new Set([...pages.map(([, file]) => file), `/${NOT_FOUND_PATH}/index.html`])) {
  if (!existsSync(browser + file)) throw new Error(`Página esperada no build: ${file}`);
}
writeFileSync(`${build}/routes.conf`, [
  'map $uri $angular_page {',
  '    default /__unknown_route__;',
  ...pages.map(([route, file]) => `    ${route} ${file};`),
  '}\n',
].join('\n'));
writeFileSync(`${browser}/robots.txt`, origin
  ? `User-agent: *\nAllow: /\n\nSitemap: ${origin}/sitemap.xml\n`
  : 'User-agent: *\nDisallow: /\n');
if (origin) {
  writeFileSync(`${browser}/sitemap.xml`, [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">',
    ...publicRoutes.map(path => `  <url><loc>${origin}${path}</loc></url>`),
    '</urlset>\n',
  ].join('\n'));
  const pages = [
    { ...PLANNER_PAGE, heading: 'Cronograma capilar' },
    ...(publishGuides ? HELP_GUIDES.map(guide => ({
      path: '/' + GUIDE_PREFIX + guide.id, title: guide.title + ' | Agenda de autocuidado',
      description: guide.summary, heading: guide.title,
    })) : []),
  ];
  writeFileSync(`${build}/seo-contract.json`, JSON.stringify({
    canonical_origin: origin,
    public_pages: pages.map(page => ({
      ...page, content_marker: page.description, image_path: SOCIAL_IMAGE.path, image_alt: SOCIAL_IMAGE.alt,
    })),
    non_indexable: [
      ...Object.values(CLIENT_PATHS).map(path => '/' + path.replace(':planId', '1').replace(':topicId', exampleUuid)),
      ...(publishGuides ? [] : guides.map(path => '/' + path)),
    ].map(path => ({ path, statuses: [200] })),
    missing_page: '/pagina-inexistente-verificacao',
  }, null, 2) + '\n');
}
