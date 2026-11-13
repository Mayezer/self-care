import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { afterEach, beforeEach, expect, it } from 'vitest';
import { Agenda } from './agenda';

const profilePage = '/meu-cabelo';
const productsPage = '/meu-cabelo/produtos';
const profileApi = '/api/hair-profile';
const productsApi = profileApi + '/products';
const account = { today: '2026-11-11', hairProfileAvailable: true, hairProductsAvailable: true };
const shampoo = {
  category: 'SHAMPOO', title: 'Xampu para raiz oleosa', reason: 'A raiz fica oleosa com frequência.',
  area: 'Raiz e couro cabeludo', instructions: 'Aplique no couro cabeludo e enxágue.',
  optional: false, variant: {
    brand: 'Marca de teste', name: 'Xampu fictício', presentation: '300 mL', hairCondition: 'Fios molhados',
    rinse: 'RINSE_OFF', warnings: ['Evite contato com os olhos.'],
    manufacturerUrl: 'https://example.com/rotulo',
  },
};
const mask = {
  category: 'MASK', title: 'Máscara para fios ressecados', reason: 'Você informou ressecamento.',
  area: 'Comprimento e pontas', instructions: 'Respeite o tempo do rótulo.', optional: true, variant: null,
};

let http: HttpTestingController;
let harness: RouterTestingHarness;

beforeEach(async () => {
  TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([
      { path: 'meu-cabelo', component: Agenda, data: { view: 'hair-profile' } },
      { path: 'meu-cabelo/produtos', component: Agenda, data: { view: 'hair-products' } },
    ])],
  });
  http = TestBed.inject(HttpTestingController);
  harness = await RouterTestingHarness.create();
});

afterEach(() => http.verify());

async function open(path: string, features = account) {
  await harness.navigateByUrl(path);
  http.expectOne('/api/agenda').flush(features);
  harness.detectChanges();
  return harness.routeNativeElement!;
}

function changeOiliness(page: HTMLElement, value: string) {
  const select = page.querySelector<HTMLSelectElement>('#rootOiliness')!;
  select.value = value;
  select.dispatchEvent(new Event('change', { bubbles: true }));
  harness.detectChanges();
}

function button(page: HTMLElement, label: string) {
  return Array.from(page.querySelectorAll('button')).find(item => item.textContent?.trim() === label)!;
}

function saveResponse(answers: object) {
  http.expectOne('/api/auth/csrf').flush({ headerName: 'X-CSRF-TOKEN', token: 'test-token' });
  const request = http.expectOne({ method: 'PUT', url: profileApi });
  expect(JSON.parse(request.request.serializeBody() as string)).toEqual(answers);
  request.flush(answers);
  harness.detectChanges();
}

it('exposes Produtos only after save, reloads suggestions after edit and removes access to cards after clear', async () => {
  let profile = await open(profilePage);
  http.expectOne(profileApi).flush({});
  harness.detectChanges();
  const productLinks = () => profile.querySelectorAll(`a[href="${productsPage}"]`);
  expect(productLinks()).toHaveLength(0);

  changeOiliness(profile, 'OFTEN');
  expect(productLinks()).toHaveLength(0);
  button(profile, 'Salvar respostas').click();
  saveResponse({ rootOiliness: 'OFTEN' });
  expect(productLinks()).toHaveLength(2);
  expect(productLinks()[1].textContent).toContain('Ver sugestões de produtos');

  let products = await open(productsPage);
  expect(products.querySelector('[role="status"]')?.textContent).toContain('Carregando');
  http.expectOne(productsApi).flush({ hasProfile: true, cards: [shampoo, mask] });
  harness.detectChanges();
  expect(products.querySelector('[aria-current="page"]')?.textContent).toBe('Produtos');
  const cards = products.querySelectorAll('.product-card');
  expect(cards[0].textContent).toContain('Cuidados do rótulo');
  expect(cards[0].textContent).toContain('Enxaguar');
  expect(cards[0].querySelector('a')?.getAttribute('href')).toBe(shampoo.variant.manufacturerUrl);
  expect(cards[1].textContent).toContain('Orientação de categoria');
  expect(cards[1].textContent).toContain('Opcional');
  expect(cards[1].querySelector('a')).toBeNull();

  profile = await open(profilePage);
  http.expectOne(profileApi).flush({ rootOiliness: 'OFTEN' });
  harness.detectChanges();
  changeOiliness(profile, 'RARELY');
  button(profile, 'Salvar respostas').click();
  saveResponse({ rootOiliness: 'RARELY' });

  products = await open(productsPage);
  http.expectOne(productsApi).flush({ hasProfile: true, cards: [{ ...shampoo, title: 'Xampu de uso regular' }] });
  harness.detectChanges();
  expect(products.textContent).toContain('Xampu de uso regular');
  expect(products.textContent).not.toContain('Xampu para raiz oleosa');

  profile = await open(profilePage);
  http.expectOne(profileApi).flush({ rootOiliness: 'RARELY' });
  harness.detectChanges();
  button(profile, 'Limpar respostas').click();
  harness.detectChanges();
  button(profile, 'Sim, limpar respostas').click();
  saveResponse({});
  expect(productLinks()).toHaveLength(0);

  products = await open(productsPage);
  http.expectOne(productsApi).flush({ hasProfile: false, cards: [] });
  harness.detectChanges();
  expect(products.textContent).toContain('Salve pelo menos uma resposta');
  expect(products.querySelector('.product-card, [aria-current="page"]')).toBeNull();
});

it('separates a temporary error, a saved profile without matches and the paused module', async () => {
  let products = await open(productsPage);
  http.expectOne(productsApi).flush(null, { status: 503, statusText: 'Service Unavailable' });
  harness.detectChanges();
  expect(products.querySelector('[role="alert"]')?.textContent).toContain('Não foi possível carregar');

  button(products, 'Tentar novamente').click();
  harness.detectChanges();
  expect(products.querySelector('[role="status"]')?.textContent).toContain('Carregando');
  http.expectOne(productsApi).flush({ hasProfile: true, cards: [] });
  harness.detectChanges();
  expect(products.textContent).toContain('Ainda não há sugestões');
  expect(products.querySelector('[aria-current="page"]')?.textContent).toBe('Produtos');

  const paused = { ...account, hairProductsAvailable: false };
  const profile = await open(profilePage, paused);
  http.expectOne(profileApi).flush({ rootOiliness: 'OFTEN' });
  harness.detectChanges();
  expect(profile.querySelector(`a[href="${productsPage}"]`)).toBeNull();
  products = await open(productsPage, paused);
  expect(products.textContent).toContain('indisponíveis no momento');
  expect(products.querySelector(`a[href="${profilePage}"]`)).not.toBeNull();
  http.expectNone(productsApi);
});

it('handles pause after the session check and requires login if the session expires', async () => {
  let products = await open(productsPage);
  http.expectOne(productsApi).flush(null, { status: 404, statusText: 'Not Found' });
  harness.detectChanges();
  expect(products.textContent).toContain('indisponíveis no momento');
  expect(products.querySelector('[role="alert"], .product-card')).toBeNull();

  await open(profilePage);
  http.expectOne(profileApi).flush({});
  products = await open(productsPage);
  http.expectOne(productsApi).flush(null, { status: 401, statusText: 'Unauthorized' });
  http.expectOne('/api/agenda').flush(null, { status: 401, statusText: 'Unauthorized' });
  harness.detectChanges();
  expect(button(products, 'Entrar')).toBeDefined();
  expect(products.querySelector('app-hair-products, .product-card')).toBeNull();
});
