import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app-config';
import { Shell } from './shell';

bootstrapApplication(Shell, appConfig).catch((error: unknown) => console.error(error));
