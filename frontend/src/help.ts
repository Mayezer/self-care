import { HttpClient } from '@angular/common/http';
import { Component, inject } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { HELP_GUIDES } from './help-guides';
import { PUBLISH_GUIDES } from './publication';

export function loadGuidesAvailability() {
  if (PUBLISH_GUIDES) return of(true);
  return inject(HttpClient).get<{ guidesAvailable: boolean }>('/api/help/features').pipe(
    map(features => features?.guidesAvailable === true),
    catchError(() => of(false)),
  );
}

export function allowGuide() {
  const help = inject(Router).createUrlTree(['/ajuda']);
  return loadGuidesAvailability().pipe(map(available => available || help));
}

@Component({
  selector: 'app-help',
  imports: [RouterLink],
  templateUrl: './help.html',
})
export class Help {
  protected readonly guidesAvailable = inject(ActivatedRoute).snapshot.data['guidesAvailable'] === true;
  protected readonly guides = HELP_GUIDES;
}
