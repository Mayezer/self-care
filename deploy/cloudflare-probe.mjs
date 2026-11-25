export default {
  fetch(request, env) {
    const incoming = new URL(request.url);
    const url = new URL(env.PUBLIC_SITE_ORIGIN);
    url.pathname = incoming.pathname;
    url.search = incoming.search;
    const forwarded = new Request(url, request);
    forwarded.headers.set('Host', url.host);
    forwarded.headers.set('CF-Connecting-IP', '192.0.2.80');
    if (env.RP03_QA_KEY) forwarded.headers.set('X-RP03-QA', env.RP03_QA_KEY);
    return env.TARGET.fetch(forwarded);
  },
};
