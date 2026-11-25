import json
from pathlib import Path
import unittest
from unittest.mock import MagicMock, patch

import cloudflare_release as release


class CloudflareReleaseTest(unittest.TestCase):
    def source(self):
        config = json.loads((Path(__file__).parent / 'wrangler.example.json').read_text())
        config['account_id'] = 'a' * 32
        config['vars']['PUBLIC_SITE_ORIGIN'] = 'https://agenda.example'
        return config

    def prepare(self, config=None, operation='deploy', image=None):
        return release.configuration(json.dumps(config or self.source()), 'producao', operation, image)

    def test_private_deploy_uses_repository_sources_and_immediate_update(self):
        source = self.source()
        source['main'] = '/old-machine/private-worker.mjs'
        source['durable_objects'].pop('code_update_strategy')
        config = self.prepare(source)
        self.assertEqual(config['main'], str(release.ROOT / 'deploy/cloudflare.mjs'))
        self.assertEqual(config['routes'], [])
        self.assertEqual(config['durable_objects']['code_update_strategy'], {'mode': 'immediate'})
        self.assertEqual(config['containers'][0]['images']['app']['build_vars']['API_IMAGE'],
                         'autocuidado-release-api:current')

    def test_rollback_pins_image_and_does_not_build(self):
        image = 'registry.cloudflare.com/' + 'a' * 32 + '/approved-app@sha256:' + 'b' * 64
        config = self.prepare(operation='rollback', image=image)
        self.assertEqual(config['containers'][0]['images'], {'app': {'image': image}})
        for invalid in [None, image.replace('@sha256:', ':tag-'), image.replace('/' + 'a' * 32 + '/', '/' + 'c' * 32 + '/')]:
            with self.subTest(image=invalid), self.assertRaises(ValueError):
                self.prepare(operation='rollback', image=invalid)

    def test_qa_keeps_the_existing_guarded_class(self):
        source = self.source()
        source['durable_objects']['bindings'][0]['class_name'] = 'QaAgendaContainer'
        config = release.configuration(json.dumps(source), 'homologacao', 'deploy')
        self.assertEqual(config['main'], str(release.ROOT / 'deploy/cloudflare-qa.mjs'))
        with self.assertRaises(ValueError):
            release.smoke(config, '')

    def test_rejects_other_domains_public_previews_and_wrong_bindings(self):
        mutations = [lambda c: c.update(workers_dev=True), lambda c: c.update(preview_urls=True),
                     lambda c: c.update(routes=[{'pattern': 'other.example', 'custom_domain': True}]),
                     lambda c: c['vars'].update(PUBLIC_SITE_ORIGIN='http://agenda.example'),
                     lambda c: c['durable_objects']['bindings'][0].update(class_name='OtherContainer')]
        for mutate in mutations:
            config = self.source()
            mutate(config)
            with self.subTest(change=mutate), self.assertRaises(ValueError):
                self.prepare(config)

    @patch.object(release.os, 'killpg')
    @patch.object(release.subprocess, 'Popen')
    @patch.object(release, 'request')
    def test_probe_checks_all_five_routes_and_terminates_its_client(self, request, popen, killpg):
        process = MagicMock()
        process.poll.return_value = None
        popen.return_value = process
        request.side_effect = [(200,b'{"status":"UP"}'), (200,b'<app-root>'), (200,b'{"status":"UP"}'),
                               (401,b''), (404,b''), (200,b'{"occurrences":[]}')]
        release.smoke(self.prepare(), '')
        self.assertEqual([call.args[0] for call in request.call_args_list],
                         ['/api/ready','/','/api/ready','/api/agenda','/api/forum/public/topics','/api/hair-plans/preview'])
        preview = request.call_args_list[-1].args[1]
        self.assertEqual(preview['timeZone'], 'America/Sao_Paulo')
        killpg.assert_called_once_with(process.pid, release.signal.SIGTERM)
        process.wait.assert_called_once_with(timeout=10)

    @patch.object(release.os, 'killpg')
    @patch.object(release.subprocess, 'Popen')
    @patch.object(release, 'request')
    def test_bad_probe_fails_and_still_terminates_the_client(self, request, popen, killpg):
        process = MagicMock()
        process.poll.return_value = None
        popen.return_value = process
        request.side_effect = [(200,b'{"status":"UP"}'), (503,b'')]
        with self.assertRaises(RuntimeError):
            release.smoke(self.prepare(), '')
        killpg.assert_called_once()
        process.wait.assert_called_once()


if __name__ == '__main__':
    unittest.main()
