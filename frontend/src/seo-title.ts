import { DOCUMENT, inject, Injectable } from '@angular/core';
import { Meta, Title } from '@angular/platform-browser';
import { RouterStateSnapshot, TitleStrategy } from '@angular/router';
import { EditorialPage, SITE_ORIGIN, SOCIAL_IMAGE } from './publication';

const socialFields = [
  'og:type', 'og:locale', 'og:title', 'og:description', 'og:url', 'og:image', 'og:image:alt',
  'twitter:card', 'twitter:title', 'twitter:description', 'twitter:image', 'twitter:image:alt',
];

@Injectable()
export class SeoTitleStrategy extends TitleStrategy {
  private readonly document = inject(DOCUMENT);
  private readonly meta = inject(Meta);
  private readonly title = inject(Title);

  override updateTitle(snapshot: RouterStateSnapshot): void {
    this.title.setTitle(this.buildTitle(snapshot) ?? 'Agenda de autocuidado');
    let route = snapshot.root;
    while (route.firstChild) route = route.firstChild;
    const page: EditorialPage | undefined = route.data['editorial'];
    const canonical = this.document.head.querySelector<HTMLLinkElement>('link[rel="canonical"]');

    if (!page || !SITE_ORIGIN) {
      this.meta.updateTag({ name: 'robots', content: 'noindex' });
      this.meta.removeTag('name="description"');
      if (canonical) this.document.head.removeChild(canonical);
      for (const key of socialFields) {
        this.meta.removeTag(`${key.startsWith('og:') ? 'property' : 'name'}="${key}"`);
      }
      return;
    }

    const url = SITE_ORIGIN + page.path;
    const image = SITE_ORIGIN + SOCIAL_IMAGE.path;
    const link = canonical ?? this.document.createElement('link');
    link.rel = 'canonical';
    link.href = url;
    if (!canonical) this.document.head.appendChild(link);
    this.meta.removeTag('name="robots"');
    this.meta.updateTag({ name: 'description', content: page.description });
    const values = [
      'website', 'pt_BR', page.title, page.description, url, image, SOCIAL_IMAGE.alt,
      'summary_large_image', page.title, page.description, image, SOCIAL_IMAGE.alt,
    ];
    socialFields.forEach((key, index) => this.meta.updateTag({
      [key.startsWith('og:') ? 'property' : 'name']: key, content: values[index],
    }));
  }
}
