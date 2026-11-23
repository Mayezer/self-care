import { test } from 'node:test';
import assert from 'node:assert/strict';
import worker, { AgendaContainer, runtimeEnvironment } from './cloudflare.mjs';

function environment() {
  return {
    PUBLIC_SITE_ORIGIN: 'https://agenda-autocuidado.example',
    AUTOCUIDADO_DB_RUNTIME_URL: 'jdbc:postgresql://synthetic.neon.tech/app?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory',
    AUTOCUIDADO_DB_RUNTIME_PASSWORD: 'synthetic-runtime-password',
    AUTOCUIDADO_SMTP_PASSWORD: 'synthetic-smtp-credential',
    AUTOCUIDADO_SMTP_FROM: 'sender@example.test',
    AUTOCUIDADO_EDGE_TOKEN: '1'.repeat(64), AUTOCUIDADO_MFA_KEY: '2'.repeat(64),
  };
}

test('forwards only the Cloudflare client identity and replaces attacker-controlled headers', async () => {
  const env = environment();
  let forwarded;
  env.AGENDA = { getByName(name) {
    assert.equal(name, 'application');
    return { async fetch(request) { forwarded = request; return new Response(null, { status: 204 }); } };
  } };
  const request = new Request(env.PUBLIC_SITE_ORIGIN + '/api/auth/login', { method: 'POST', body: 'synthetic-form', headers: {
    'CF-Connecting-IP': '192.0.2.10', 'Forwarded': 'spoofed', 'X-Forwarded-For': 'spoofed',
    'X-Autocuidado-Client-IP': 'spoofed', 'X-Autocuidado-Origin': 'spoofed', 'X-Request-Id': 'spoofed',
  } });
  assert.equal((await worker.fetch(request, env)).status, 204);
  assert.equal(forwarded.headers.get('X-Autocuidado-Client-IP'), '192.0.2.10');
  assert.equal(forwarded.headers.get('X-Autocuidado-Origin'), env.AUTOCUIDADO_EDGE_TOKEN);
  assert.equal(forwarded.headers.get('X-Forwarded-For'), null);
  assert.equal(forwarded.headers.get('Forwarded'), null);
  assert.match(forwarded.headers.get('X-Request-Id'), /^[a-f0-9]{32}$/);
  assert.equal(await forwarded.text(), 'synthetic-form');
});

test('rejects another host, missing client identity, insecure writes and incomplete secrets', async () => {
  const env = environment();
  assert.equal((await worker.fetch(new Request('https://another.example/'), env)).status, 404);
  assert.equal((await worker.fetch(new Request(env.PUBLIC_SITE_ORIGIN), env)).status, 403);
  assert.equal((await worker.fetch(new Request('http://agenda-autocuidado.example/api/auth/login', { method: 'POST' }), env)).status, 400);
  delete env.AUTOCUIDADO_MFA_KEY;
  assert.equal((await worker.fetch(new Request(env.PUBLIC_SITE_ORIGIN, { headers: { 'CF-Connecting-IP': '192.0.2.10' } }), env)).status, 503);
});

test('keeps migration privileges, optional modules and registration out of the runtime', () => {
  const env = environment();
  const runtime = runtimeEnvironment(env);
  assert.equal(runtime.SPRING_DATASOURCE_USERNAME, 'autocuidado_runtime');
  assert.equal(runtime.SPRING_FLYWAY_ENABLED, 'false');
  assert.equal(runtime.APP_AUTH_REGISTRATION_ENABLED, 'false');
  assert.equal(runtime.APP_FORUM_ENABLED, 'false');
  assert.equal(runtime.APP_AGENDA_WEB_PUSH_ENABLED, 'false');
  assert.equal(runtime.SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE, '5');
  assert.throws(() => runtimeEnvironment({ ...env,
    AUTOCUIDADO_DB_RUNTIME_URL: env.AUTOCUIDADO_DB_RUNTIME_URL.replace('synthetic.neon.tech', 'synthetic-pooler.neon.tech'),
  }));
  for (const suffix of ['&user=owner', '&sslmode=require']) {
    assert.throws(() => runtimeEnvironment({ ...env, AUTOCUIDADO_DB_RUNTIME_URL: env.AUTOCUIDADO_DB_RUNTIME_URL + suffix }));
  }
  assert.throws(() => runtimeEnvironment({ ...env, AUTOCUIDADO_EDGE_TOKEN: env.AUTOCUIDADO_MFA_KEY }));
});

async function containerFixture({ approved, bad = false } = {}) {
  const env = environment();
  const state = new Map(approved ? [['approvedImage', approved]] : []);
  if (approved) {
    const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(JSON.stringify({
      instance: { vcpu: 1, memoryMib: 1024, diskMb: 2000 }, enableInternet: true,
      entrypoint: ['/bin/sh', '/app/start-cloudflare.sh'],
      inactivityTimeoutMs: 35 * 60_000, env: runtimeEnvironment(env),
    })));
    state.set('approvedConfiguration', Buffer.from(bytes).toString('hex'));
  }
  const events = [];
  let active = approved;
  const port = { async fetch(request) {
    const path = new URL(typeof request === 'string' ? request : request.url).pathname;
    events.push(['probe', active, path]);
    if (typeof request === 'string') assert.equal(state.get('approvedImage'), approved, 'State advanced before smoke completed');
    if (path === '/' && bad && active === 'candidate') return new Response('unexpected');
    const responses = { '/': ['<app-root>', 200], '/api/ready': ['{"status":"UP"}', 200],
      '/api/agenda': ['', 401], '/api/forum/public/topics': ['', 404], '/api/hair-plans/preview': ['{"occurrences":[]}', 200] };
    const [body, status] = responses[path] ?? ['', 204];
    return new Response(body || null, { status });
  } };
  const ctx = {
    blockConcurrencyWhile(action) { return action(); },
    storage: { async get(key) { return state.get(key); }, async put(key, value) { events.push(['save', key, value]); state.set(key, value); },
      async delete(key) { state.delete(key); } },
    container: { images: { app: 'candidate' }, running: Boolean(approved),
      start(options) { events.push(['start', options.image]); active = options.image; this.running = true; },
      signal(value) { assert.equal(value, 15); events.push(['stop', active]); active = null; this.running = false; },
      async monitor() {},
      async destroy() { events.push(['destroy', active]); active = null; this.running = false; },
      async setInactivityTimeout(value) { this.inactivityTimeout = value; },
      getTcpPort(portNumber) { assert.equal(portNumber, 8081); return port; },
    },
  };
  return { instance: new AgendaContainer(ctx, env), ctx, state, events, env };
}

test('promotes a new immutable image only after all five probes', async () => {
  const f = await containerFixture({ approved: 'previous' });
  await f.instance.ready();
  assert.equal(f.state.get('approvedImage'), 'candidate');
  assert.deepEqual(f.events.filter(e => e[0] === 'start'), [['start', 'candidate']]);
  const save = f.events.findIndex(e => e[0] === 'save');
  assert.equal(f.events[save - 1][2], '/api/hair-plans/preview');
});

test('uses the resource budget verified for the Java runtime', async () => {
  const f = await containerFixture();
  const original = f.ctx.container.start.bind(f.ctx.container);
  f.ctx.container.start = options => {
    assert.equal(typeof options.instance, 'object');
    assert.ok(options.instance.vcpu >= 1);
    assert.ok(Number.isInteger(options.instance.memoryMib) && options.instance.memoryMib >= 1024);
    assert.ok(Number.isInteger(options.instance.diskMb) && options.instance.diskMb >= 2000);
    original(options);
  };
  await f.instance.ready();
});

test('starts the guarded supervisor explicitly in the native runtime', async () => {
  const f = await containerFixture();
  const original = f.ctx.container.start.bind(f.ctx.container);
  f.ctx.container.start = options => {
    assert.deepEqual(options.entrypoint, ['/bin/sh', '/app/start-cloudflare.sh']);
    original(options);
  };
  await f.instance.ready();
});

test('sets an inactivity timeout before probing and restores it after a Durable Object restart', async t => {
  const f = await containerFixture();
  let clockReads = 0;
  t.mock.method(Date, 'now', () => clockReads++ < 2 ? 0 : 120_001);
  const original = f.ctx.container.getTcpPort.bind(f.ctx.container);
  f.ctx.container.getTcpPort = port => {
    assert.ok(f.ctx.container.inactivityTimeout > 30 * 60_000);
    return original(port);
  };
  await f.instance.ready();
  f.ctx.container.inactivityTimeout = undefined;
  const restarted = new AgendaContainer(f.ctx, f.env);
  await restarted.ready();
  assert.ok(f.ctx.container.inactivityTimeout > 30 * 60_000);
});

test('retries a held image when the native launch configuration changes', async () => {
  const f = await containerFixture();
  const old = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(JSON.stringify(runtimeEnvironment(f.env))));
  f.state.set('failedImage', { image: 'candidate', configuration: Buffer.from(old).toString('hex') });
  await f.instance.ready();
  assert.equal(f.state.get('approvedImage'), 'candidate');
  assert.equal(f.state.get('failedImage'), undefined);
});

test('stops replaced images through native signal and monitor methods', async () => {
  const f = await containerFixture({ approved: 'previous' });
  delete f.ctx.container.stop;
  let signalled = false;
  f.ctx.container.signal = signal => {
    assert.equal(signal, 15);
    signalled = true;
    f.ctx.container.running = false;
  };
  f.ctx.container.monitor = async () => {};
  await f.instance.ready();
  assert.equal(signalled, true);
  assert.equal(f.state.get('approvedImage'), 'candidate');
});

test('destroys a container that does not finish its graceful shutdown within thirty seconds', async t => {
  const f = await containerFixture({ approved: 'previous' });
  f.ctx.container.signal = () => {};
  f.ctx.container.monitor = () => new Promise(() => {});
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const stopped = f.instance.stop();
  t.mock.timers.tick(29_999);
  assert.equal(f.ctx.container.running, true);
  t.mock.timers.tick(1);
  await stopped;
  assert.equal(f.ctx.container.running, false);
  assert.deepEqual(f.events.filter(event => event[0] === 'destroy'), [['destroy', 'previous']]);
});

test('restores the approved image after a rejected update and does not retry it on every request', async () => {
  const f = await containerFixture({ approved: 'previous', bad: true });
  await assert.rejects(f.instance.ready());
  assert.equal(f.state.get('approvedImage'), 'previous');
  assert.equal(f.state.get('failedImage').image, 'candidate');
  assert.deepEqual(f.events.filter(e => e[0] === 'start'), [['start', 'candidate'], ['start', 'previous']]);
  f.events.length = 0;
  await f.instance.ready();
  assert.deepEqual(f.events, []);
});

test('stops a rejected first deployment and holds it until the image changes', async () => {
  const f = await containerFixture({ bad: true });
  await assert.rejects(f.instance.ready());
  assert.equal(f.state.get('approvedImage'), undefined);
  assert.equal(f.ctx.container.running, false);
  const count = f.events.length;
  await assert.rejects(f.instance.ready());
  assert.equal(f.events.length, count);
});

test('rolls back images without enabling Flyway and restarts after a runtime secret changes', async () => {
  const f = await containerFixture({ approved: 'previous' });
  f.ctx.container.images.app = 'older-approved';
  await f.instance.ready();
  assert.equal(f.state.get('approvedImage'), 'older-approved');
  assert.deepEqual(f.events.filter(e => e[0] === 'start'), [['start', 'older-approved']]);
  const rotation = await containerFixture({ approved: 'previous' });
  const oldFingerprint = rotation.state.get('approvedConfiguration');
  rotation.env.AUTOCUIDADO_SMTP_PASSWORD = 'rotated-synthetic-credential';
  rotation.ctx.container.images.app = 'previous';
  await rotation.instance.ready();
  assert.notEqual(rotation.state.get('approvedConfiguration'), oldFingerprint);
  assert.deepEqual(rotation.events.filter(e => e[0] === 'start'), [['start', 'previous']]);
});

test('concurrent requests share one startup and client writes are forwarded only once', async () => {
  const f = await containerFixture();
  const responses = await Promise.all([1, 2].map(() =>
    f.instance.fetch(new Request('https://agenda-autocuidado.example/api/custom', { method: 'POST', body: 'synthetic' }))));
  assert.deepEqual(responses.map(response => response.status), [204, 204]);
  assert.equal(f.events.filter(e => e[0] === 'start').length, 1);
  assert.equal(f.events.filter(e => e[0] === 'probe' && e[2] === '/api/custom').length, 2);
});

test('stops rather than restoring with changed secrets after a rejected deployment', async () => {
  const f = await containerFixture({ approved: 'previous', bad: true });
  f.env.AUTOCUIDADO_SMTP_PASSWORD = 'changed-synthetic-credential';
  await assert.rejects(f.instance.ready());
  assert.equal(f.state.get('approvedImage'), 'previous');
  assert.equal(f.ctx.container.running, false);
  assert.deepEqual(f.events.filter(e => e[0] === 'start'), [['start', 'candidate']]);
  await assert.rejects(f.instance.ready());
});
