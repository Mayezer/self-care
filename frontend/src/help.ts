import { PageHeading } from './page-heading';
import { HttpClient } from '@angular/common/http';
import { Component, inject } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { HELP_GUIDES } from './help-guides';
import { PUBLISH_GUIDES } from './publication';

interface HelpFeatures {
  guidesAvailable: boolean;
  forumAvailable: boolean;
}

export function loadHelpFeatures() {
  return inject(HttpClient).get<HelpFeatures>('/api/help/features').pipe(
    map(features => ({ guidesAvailable: PUBLISH_GUIDES || features?.guidesAvailable === true,
      forumAvailable: features?.forumAvailable === true })),
    catchError(() => of({ guidesAvailable: PUBLISH_GUIDES, forumAvailable: false })),
  );
}

export function allowGuide() {
  const help = inject(Router).createUrlTree(['/ajuda']);
  return loadHelpFeatures().pipe(map(features => features.guidesAvailable || help));
}

@Component({
  selector: 'app-help',
  imports: [PageHeading, RouterLink],
  templateUrl: './help.html',
})
export class Help {
  protected readonly features: HelpFeatures = inject(ActivatedRoute).snapshot.data['features'];
  protected readonly guidesAvailable = this.features.guidesAvailable;
  protected readonly guides = HELP_GUIDES;
}
