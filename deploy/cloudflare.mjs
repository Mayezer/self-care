const CONTAINER_OPTIONS = {
  instance: { vcpu: 1, memoryMib: 1024, diskMb: 2000 }, enableInternet: true,
  entrypoint: ['/bin/sh', '/app/start-cloudflare.sh'],
};
const INACTIVITY_TIMEOUT_MS = 35 * 60_000;

function unavailable() {
  return new Response('Serviço temporariamente indisponível.', { status: 503, headers: { 'Cache-Control': 'no-store' } });
}

export function runtimeEnvironment(env) {
  const url = new URL(env.AUTOCUIDADO_DB_RUNTIME_URL?.replace(/^jdbc:/, '') ?? 'invalid:');
  if (url.protocol !== 'postgresql:' || !url.hostname.endsWith('.neon.tech') || url.hostname.includes('-pooler.')
      || url.username || url.password
      || url.searchParams.getAll('sslmode').length !== 1 || url.searchParams.get('sslmode') !== 'verify-full'
      || url.searchParams.getAll('sslfactory').length !== 1
      || [...url.searchParams.keys()].some(key => !['sslmode', 'sslfactory'].includes(key))
      || url.searchParams.get('sslfactory') !== 'org.postgresql.ssl.DefaultJavaSSLFactory'
      || !/^[a-f0-9]{64}$/i.test(env.AUTOCUIDADO_EDGE_TOKEN ?? '')
      || !/^[a-f0-9]{64}$/i.test(env.AUTOCUIDADO_MFA_KEY ?? '')
      || env.AUTOCUIDADO_EDGE_TOKEN === env.AUTOCUIDADO_MFA_KEY
      || !env.AUTOCUIDADO_DB_RUNTIME_PASSWORD || !env.AUTOCUIDADO_SMTP_PASSWORD || !env.AUTOCUIDADO_SMTP_FROM) {
    throw new Error('Incomplete protected runtime configuration');
  }
  return {
    SPRING_PROFILES_ACTIVE: 'publicacao,cloudflare-mail,cloudflare',
    SPRING_DATASOURCE_URL: env.AUTOCUIDADO_DB_RUNTIME_URL,
    SPRING_DATASOURCE_USERNAME: 'autocuidado_runtime',
    SPRING_DATASOURCE_PASSWORD: env.AUTOCUIDADO_DB_RUNTIME_PASSWORD,
    SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE: '5',
    SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT: '10000',
    SPRING_FLYWAY_ENABLED: 'false',
    SPRING_MAIL_PASSWORD: env.AUTOCUIDADO_SMTP_PASSWORD,
    APP_AUTH_EMAIL_FROM: env.AUTOCUIDADO_SMTP_FROM,
    APP_AUTH_MFA_KEY: env.AUTOCUIDADO_MFA_KEY,
    APP_AUTH_REGISTRATION_ENABLED: env.AUTOCUIDADO_REGISTRATION_ENABLED === 'true' ? 'true' : 'false',
    AUTOCUIDADO_EDGE_TOKEN: env.AUTOCUIDADO_EDGE_TOKEN,
    APP_HAIR_PROFILE_ENABLED: 'true', APP_HELP_GUIDES_ENABLED: 'true',
    APP_HAIR_PRODUCTS_ENABLED: 'false', APP_HAIR_DIARY_ENABLED: 'false', APP_BILLING_ENABLED: 'false',
    APP_FORUM_ENABLED: 'false', APP_FORUM_PUBLIC_READING_ENABLED: 'false', APP_AGENDA_WEB_PUSH_ENABLED: 'false',
    APP_AGENDA_REMINDERS_ENABLED: 'true', APP_AGENDA_CARE_STREAK_ENABLED: 'true', APP_AGENDA_DAILY_MESSAGE_ENABLED: 'true',
  };
}

export class AgendaContainer {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
    if (ctx.container.running) {
      ctx.blockConcurrencyWhile(() => ctx.container.setInactivityTimeout(INACTIVITY_TIMEOUT_MS));
    }
  }

  async fetch(request) {
    try {
      if (!this.ctx.container.running) this.readyOnce = false;
      if (!this.readyOnce) {
        this.starting ??= this.ready().finally(() => { this.starting = undefined; });
        await this.starting;
        this.readyOnce = true;
      }
      const url = new URL(request.url);
      url.protocol = 'http:';
      url.host = 'container';
      return await this.ctx.container.getTcpPort(8081).fetch(new Request(url, request));
    } catch {
      return unavailable();
    }
  }

  async ready() {
    const container = this.ctx.container;
    const environment = runtimeEnvironment(this.env);
    const configuration = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',
      new TextEncoder().encode(JSON.stringify({ ...CONTAINER_OPTIONS,
        inactivityTimeoutMs: INACTIVITY_TIMEOUT_MS, env: environment })))), byte => byte.toString(16).padStart(2, '0')).join('');
    const target = container.images.app;
    if (typeof target !== 'string') throw new Error('Expected immutable container image');
    const approved = await this.ctx.storage.get('approvedImage');
    const approvedConfiguration = await this.ctx.storage.get('approvedConfiguration');
    const failed = await this.ctx.storage.get('failedImage');
    if (target === failed?.image && configuration === failed.configuration) {
      if (!approved || approvedConfiguration !== configuration) throw new Error('Deployment configuration rejected');
      if (!container.running) await this.start(approved, environment);
      return;
    }
    if (container.running && approved === target && approvedConfiguration === configuration) return;
    if (container.running) await this.stop();
    try {
      await this.start(target, environment);
      await this.ctx.storage.put('approvedImage', target);
      await this.ctx.storage.put('approvedConfiguration', configuration);
      await this.ctx.storage.delete('failedImage');
    } catch (error) {
      if (container.running) await this.stop();
      await this.ctx.storage.put('failedImage', { image: target, configuration });
      if (approved && approved !== target && approvedConfiguration === configuration) await this.start(approved, environment);
      throw error;
    }
  }

  async stop() {
    const container = this.ctx.container;
    container.signal(15);
    let timer;
    try {
      await Promise.race([container.monitor().catch(() => {}), new Promise(resolve => {
        timer = setTimeout(resolve, 30_000);
      })]);
    } finally {
      clearTimeout(timer);
    }
    if (container.running) await container.destroy();
  }

  async start(image, environment) {
    const container = this.ctx.container;
    container.start({ image, ...CONTAINER_OPTIONS, env: environment });
    await container.setInactivityTimeout(INACTIVITY_TIMEOUT_MS);
    const deadline = Date.now() + 120_000;
    while (Date.now() < deadline) {
      let ready = false;
      try {
        const response = await container.getTcpPort(8081).fetch('http://container/api/ready', {
          headers: { 'X-Autocuidado-Origin': this.env.AUTOCUIDADO_EDGE_TOKEN }, signal: AbortSignal.timeout(2000),
        });
        await response.body?.cancel();
        ready = response.ok;
      } catch { /* Retry only the readiness probe; client writes are never retried. */ }
      if (ready) {
        await this.smoke();
        return;
      }
      await new Promise(resolve => setTimeout(resolve, 200));
    }
    throw new Error('Readiness deadline exceeded');
  }

  async smoke() {
    const preview = { startDate: new Date(Date.now() + 2 * 86_400_000).toISOString().slice(0, 10),
      weeks: 1, weekdays: ['MONDAY', 'THURSDAY'], timeZone: 'America/Sao_Paulo' };
    for (const [path, status, marker, body] of [
      ['/', 200, '<app-root'], ['/api/ready', 200, '"UP"'], ['/api/agenda', 401, ''],
      ['/api/forum/public/topics', 404, ''], ['/api/hair-plans/preview', 200, '"occurrences"', preview],
    ]) {
      const response = await this.ctx.container.getTcpPort(8081).fetch(`http://container${path}`, {
        method: body ? 'POST' : 'GET',
        headers: { 'X-Autocuidado-Origin': this.env.AUTOCUIDADO_EDGE_TOKEN, 'Content-Type': 'application/json' },
        body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(10_000),
      });
      if (response.status !== status || !(await response.text()).includes(marker)) {
        throw new Error('Container smoke rejected');
      }
    }
  }
}

export default {
  async fetch(request, env) {
    const expected = new URL(env.PUBLIC_SITE_ORIGIN);
    const url = new URL(request.url);
    if (expected.protocol !== 'https:' || url.host !== expected.host) return new Response(null, { status: 404 });
    if (url.protocol !== 'https:') {
      if (!['GET', 'HEAD'].includes(request.method)) return new Response(null, { status: 400 });
      url.protocol = 'https:';
      return Response.redirect(url.toString(), 308);
    }
    const client = request.headers.get('CF-Connecting-IP');
    if (!client || !/^[a-f0-9:.]{3,45}$/i.test(client)) return new Response(null, { status: 403 });
    try {
      runtimeEnvironment(env);
      const forwarded = new Request(request);
      for (const header of ['Forwarded', 'X-Forwarded-For', 'X-Forwarded-Host', 'X-Forwarded-Proto',
        'X-Real-IP', 'CF-Connecting-IP', 'X-Autocuidado-Origin', 'X-Autocuidado-Client-IP', 'X-Request-Id']) {
        forwarded.headers.delete(header);
      }
      forwarded.headers.set('X-Autocuidado-Origin', env.AUTOCUIDADO_EDGE_TOKEN);
      forwarded.headers.set('X-Autocuidado-Client-IP', client);
      forwarded.headers.set('X-Request-Id', crypto.randomUUID().replaceAll('-', ''));
      return await env.AGENDA.getByName('application').fetch(forwarded);
    } catch {
      return unavailable();
    }
  },
};
