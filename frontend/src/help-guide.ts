import { Component, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { CURL_GROUPS } from './curl-patterns';
import { Guide, GUIDES } from './help-guides';

@Component({
  selector: 'app-help-guide',
  imports: [RouterLink],
  templateUrl: './help-guide.html',
  styleUrl: './help-guide.css',
})
export class HelpGuide {
  protected readonly guide: Guide = inject(ActivatedRoute).snapshot.data['guide'];
  protected readonly showCurlChart = this.guide.id === GUIDES.curl.id;
  protected readonly curlGroups = CURL_GROUPS;
}
