import worker, { AgendaContainer } from './cloudflare.mjs';

export class QaAgendaContainer extends AgendaContainer {
  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === '/_rp03/control' && request.headers.get('X-Autocuidado-Origin') === this.env.AUTOCUIDADO_EDGE_TOKEN) {
      const { operation } = await request.json();
      const result = {};
      if (!['stop', 'maintenance', 'resume', 'origin', 'short-idle'].includes(operation)) {
        return new Response(null, { status: 400 });
      }
      if (operation === 'maintenance') await this.ctx.storage.put('qaMaintenance', true);
      if (operation === 'stop' || operation === 'maintenance') {
        if (this.ctx.container.running) await this.stop();
        this.readyOnce = false;
      }
      if (operation === 'resume') await this.ctx.storage.delete('qaMaintenance');
      if (operation === 'short-idle') await this.ctx.container.setInactivityTimeout(15_000);
      if (operation === 'origin') {
        const port = this.ctx.container.getTcpPort(8081);
        for (const [name, headers] of [['missing', {}], ['wrong', { 'X-Autocuidado-Origin': 'wrong' }]]) {
          const response = await port.fetch('http://container/api/ready', { headers });
          result[name] = response.status;
          await response.body?.cancel();
        }
      }
      return Response.json({ ...result, running: this.ctx.container.running,
        approvedImage: await this.ctx.storage.get('approvedImage') }, { headers: { 'Cache-Control': 'no-store' } });
    }
    if (await this.ctx.storage.get('qaMaintenance')) {
      return new Response(null, { status: 503, headers: { 'Cache-Control': 'no-store' } });
    }
    return super.fetch(request);
  }
}

export default {
  fetch(request, env) {
    if (!env.RP03_QA_KEY || request.headers.get('X-RP03-QA') !== env.RP03_QA_KEY) {
      return new Response(null, { status: 404 });
    }
    const forwarded = new Request(request);
    forwarded.headers.delete('X-RP03-QA');
    return worker.fetch(forwarded, env);
  },
};
