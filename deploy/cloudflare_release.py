"""Prepare Cloudflare releases and probe the exact private service binding."""
import argparse
import datetime as dt
import http.client
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import tempfile
import time
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]


def configuration(source, target, operation, image=None):
    config = json.loads(source)
    if (target not in {'homologacao', 'producao'} or operation not in {'deploy', 'rollback'}
            or not re.fullmatch(r'[a-f0-9]{32}', config.get('account_id', ''))
            or not re.fullmatch(r'[a-z0-9][a-z0-9-]{0,62}', config.get('name', ''))):
        raise ValueError('Invalid release target or Cloudflare identity')
    origin = config['vars']['PUBLIC_SITE_ORIGIN']
    if not re.fullmatch(r'https://(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,63}', origin):
        raise ValueError('Expected canonical HTTPS origin')
    if config.get('workers_dev') is not False or config.get('preview_urls') is not False:
        raise ValueError('Public development URLs must remain disabled')
    for route in config.get('routes', []):
        if route != {'pattern': urlsplit(origin).hostname, 'custom_domain': True}:
            raise ValueError('Only the approved canonical custom domain is allowed')
    expected_class = 'QaAgendaContainer' if target == 'homologacao' else 'AgendaContainer'
    if config['durable_objects']['bindings'] != [{'name': 'AGENDA', 'class_name': expected_class}]:
        raise ValueError('Unexpected Durable Object binding')
    config['durable_objects']['code_update_strategy'] = {'mode': 'immediate'}
    config['main'] = str(ROOT / 'deploy' / ('cloudflare-qa.mjs' if target == 'homologacao' else 'cloudflare.mjs'))
    containers = config['containers']
    if len(containers) != 1 or containers[0]['scheduling_policy'] != 'durable_object':
        raise ValueError('Expected one Durable Object container')
    if operation == 'rollback':
        pattern = r'registry\.cloudflare\.com/' + config['account_id'] + r'/[a-z0-9._-]+@sha256:[a-f0-9]{64}'
        if not image or not re.fullmatch(pattern, image):
            raise ValueError('Rollback requires the approved immutable image in this account')
        containers[0]['images'] = {'app': {'image': image}}
    else:
        containers[0]['images'] = {'app': {'dockerfile': str(ROOT / 'deploy/Dockerfile.cloudflare'),
            'build_context': str(ROOT), 'build_vars': {'API_IMAGE': 'autocuidado-release-api:current',
                                                     'WEB_IMAGE': 'autocuidado-release-web:current'}}}
    return config


def smoke(config, qa_key):
    if config['durable_objects']['bindings'][0]['class_name'] == 'QaAgendaContainer' and not qa_key:
        raise ValueError('Protected QA requires its access key')
    with tempfile.TemporaryDirectory(prefix='autocuidado-cloudflare-probe-') as directory:
        base = Path(directory)
        probe = {'name': 'autocuidado-release-probe', 'account_id': config['account_id'],
                 'compatibility_date': config['compatibility_date'],
                 'main': str(ROOT / 'deploy/cloudflare-probe.mjs'),
                 'workers_dev': False, 'preview_urls': False, 'routes': [],
                 'vars': {'PUBLIC_SITE_ORIGIN': config['vars']['PUBLIC_SITE_ORIGIN']},
                 'services': [{'binding': 'TARGET', 'service': config['name'], 'remote': True}]}
        (base / 'wrangler.json').write_text(json.dumps(probe))
        if qa_key:
            p = base / '.dev.vars'
            p.write_text('RP03_QA_KEY=' + json.dumps(qa_key) + '\n')
            p.chmod(0o600)
        with (base / 'probe.log').open('w') as log:
            process = subprocess.Popen(['npx', '--yes', 'wrangler@4.165.0', 'dev', '--config', str(base / 'wrangler.json'),
                '--ip', '127.0.0.1', '--port', '18887', '--show-interactive-dev-session=false'],
                stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            try:
                deadline = time.monotonic() + 180
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise RuntimeError('Private Cloudflare probe failed to start')
                    try:
                        if request('/api/ready')[0] == 200:
                            break
                    except (OSError, http.client.HTTPException):
                        pass
                    time.sleep(1)
                else:
                    raise RuntimeError('Private Cloudflare readiness deadline exceeded')
                preview = {'startDate': str(dt.date.today() + dt.timedelta(days=2)), 'weeks': 1,
                           'weekdays': ['MONDAY', 'THURSDAY'], 'timeZone': 'America/Sao_Paulo'}
                for path, expected, marker, body in [('/', 200, b'<app-root', None),
                    ('/api/ready', 200, b'"UP"', None), ('/api/agenda', 401, b'', None),
                    ('/api/forum/public/topics', 404, b'', None),
                    ('/api/hair-plans/preview', 200, b'"occurrences"', preview)]:
                    status, content = request(path, body)
                    if status != expected or marker not in content:
                        raise RuntimeError('Private Cloudflare smoke rejected: ' + path)
                print('Five private binding probes passed for ' + config['name'])
            finally:
                if process.poll() is None:
                    os.killpg(process.pid, signal.SIGTERM)
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        os.killpg(process.pid, signal.SIGKILL)
                        process.wait()


def request(path, body=None):
    connection = http.client.HTTPConnection('127.0.0.1', 18887, timeout=150)
    try:
        connection.request('POST' if body else 'GET', path,
            body=json.dumps(body) if body else None, headers={'Content-Type': 'application/json'})
        response = connection.getresponse()
        return response.status, response.read()
    finally:
        connection.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=['prepare', 'smoke'])
    parser.add_argument('--config', required=True, type=Path)
    args = parser.parse_args()
    try:
        if args.operation == 'prepare':
            config = configuration(os.environ['CLOUDFLARE_DEPLOY_CONFIG'], os.environ['RELEASE_TARGET'],
                                   os.environ['RELEASE_OPERATION'], os.environ.get('ROLLBACK_IMAGE'))
            args.config.write_text(json.dumps(config, indent=2))
            args.config.chmod(0o600)
        else:
            smoke(json.loads(args.config.read_text()), os.environ.get('CLOUDFLARE_QA_KEY'))
    except (ValueError, KeyError, RuntimeError, OSError):
        parser.exit(1, 'Cloudflare release failed; check protected configuration and provider state.\n')
