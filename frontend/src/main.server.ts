import { mergeApplicationConfig } from '@angular/core';
import { BootstrapContext, bootstrapApplication } from '@angular/platform-browser';
import { provideServerRendering, RenderMode, withRoutes } from '@angular/ssr';
import { appConfig } from './app-config';
import { HELP_GUIDES } from './help-guides';
import { PUBLISH_GUIDES } from './publication';
import { GUIDE_PREFIX, NOT_FOUND_PATH } from './route-paths';
import { Shell } from './shell';

const serverConfig = mergeApplicationConfig(appConfig, {
  providers: [provideServerRendering(withRoutes([
    { path: '', renderMode: RenderMode.Prerender },
    { path: NOT_FOUND_PATH, renderMode: RenderMode.Prerender },
    ...(PUBLISH_GUIDES ? HELP_GUIDES.map(guide => ({
      path: GUIDE_PREFIX + guide.id, renderMode: RenderMode.Prerender as const,
    })) : []),
    { path: '**', renderMode: RenderMode.Client },
  ]))],
});

export default (context: BootstrapContext) => bootstrapApplication(Shell, serverConfig, context);
