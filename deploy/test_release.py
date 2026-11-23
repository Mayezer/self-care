"""Exercise release decisions with simulated Docker/HTTPS and real state files."""

from contextlib import ExitStack, redirect_stdout
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from urllib.parse import urlsplit

import release


PREVIOUS = {"web": "sha256:" + "1" * 64, "api": "sha256:" + "2" * 64}
APPROVED = {"web": "sha256:" + "3" * 64, "api": "sha256:" + "4" * 64}
CANDIDATE = {"web": "sha256:" + "5" * 64, "api": "sha256:" + "6" * 64}
RESPONSES = {
    "/": "<app-root></app-root>200",
    "/api/ready": '{"status":"UP"}200',
    "/api/agenda": "401",
    "/api/forum/public/topics": "404",
    "/api/hair-plans/preview": '{"occurrences":[]}200',
}


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.context = ExitStack()
        self.addCleanup(self.context.close)
        directory = self.context.enter_context(tempfile.TemporaryDirectory(prefix="autocuidado-release-test-"))
        self.config = {
            "state_dir": directory,
            "env_file": str(Path(directory) / "unused.env"),
            "project": "autocuidado-release-test",
            "smoke_origin": "https://autocuidado.example",
            "smoke_address": "127.0.0.1",
        }
        self.state_file = Path(directory) / "images.json"
        self.state_file.write_text(json.dumps({"current": APPROVED, "previous": PREVIOUS}))
        self.before = self.state_file.read_bytes()
        self.active = APPROVED
        self.actions = []
        self.probes = []
        self.bad_responses = {}
        self.fail_operation = None
        self.cached = True
        self.process = self.context.enter_context(patch.object(release.subprocess, "run", side_effect=self.run_process))
        self.context.enter_context(redirect_stdout(io.StringIO()))

    def run_process(self, command, **kwargs):
        if command[:3] == ["docker", "image", "inspect"]:
            return subprocess.CompletedProcess(command, 0 if self.cached else 1)
        if command[:2] == ["docker", "compose"]:
            self.assertEqual(command[2:8], ["--env-file", self.config["env_file"],
                                           "-p", self.config["project"], "-f", str(release.ROOT / "compose.yaml")])
            self.assertTrue(kwargs["check"])
            images = {service: kwargs["env"][f"AUTOCUIDADO_{service.upper()}_IMAGE"] for service in ("web", "api")}
            operation = command[8:]
            self.actions.append((operation, images))
            if operation[0] == self.fail_operation and images == CANDIDATE:
                raise subprocess.CalledProcessError(1, command)
            if operation[0] == "up":
                self.assertIn("--wait", operation)
                self.active = images
            elif operation[0] == "stop":
                self.active = None
            return subprocess.CompletedProcess(command, 0)
        if command[0] == "curl":
            self.assertTrue(kwargs["check"])
            self.assertEqual(self.read_state(), self.before, "A revisão avançou antes do fim das sondas.")
            path = urlsplit(command[-1]).path
            self.probes.append((self.active, path))
            response = self.bad_responses.get(path, RESPONSES[path]) if self.active == CANDIDATE else RESPONSES[path]
            return subprocess.CompletedProcess(command, 0, stdout=response)
        self.fail(f"Processo inesperado: {command}")

    def read_state(self):
        return self.state_file.read_bytes() if self.state_file.exists() else None

    def operations(self):
        return [command[0] for command, images in self.actions]

    def assert_probes(self, images):
        self.assertEqual(self.probes, [(images, path) for path in RESPONSES])

    def test_deploy_migrates_then_approves_only_after_all_probes(self):
        self.cached = False

        release.release(self.config, "deploy", CANDIDATE)

        self.assertEqual(self.operations(), ["pull", "pull", "run", "up"])
        self.assertEqual(self.actions[2][0], ["run", "--rm", "--no-deps", "--pull", "never", "migrate"])
        self.assertTrue(all(images == CANDIDATE for command, images in self.actions))
        self.assert_probes(CANDIDATE)
        self.assertEqual(self.active, CANDIDATE)
        self.assertEqual(json.loads(self.read_state()), {"current": CANDIDATE, "previous": APPROVED})
        self.assertEqual(self.state_file.stat().st_mode & 0o777, 0o600)

    def test_manual_rollback_switches_to_previous_without_migrating(self):
        release.release(self.config, "rollback", {})

        self.assertEqual(self.operations(), ["up"])
        self.assert_probes(PREVIOUS)
        self.assertEqual(self.active, PREVIOUS)
        self.assertEqual(json.loads(self.read_state()), {"current": PREVIOUS, "previous": APPROVED})

    def test_rejected_update_restores_approved_images_and_keeps_state(self):
        failures = {
            "/": "unexpected HTML200",
            "/api/ready": '{"status":"DOWN"}200',
            "/api/agenda": "[]200",
            "/api/forum/public/topics": "[]200",
            "/api/hair-plans/preview": "{}200",
        }
        for path, response in failures.items():
            with self.subTest(path=path):
                self.actions.clear()
                self.probes.clear()
                self.bad_responses = {path: response}

                with self.assertRaises(RuntimeError):
                    release.release(self.config, "deploy", CANDIDATE)

                self.assertEqual(self.operations(), ["run", "up", "up"])
                self.assertEqual(self.actions[-1][1], APPROVED)
                self.assertEqual(self.active, APPROVED)
                self.assertEqual(self.read_state(), self.before)
                self.assertEqual(self.probes[-5:], [(APPROVED, route) for route in RESPONSES])

    def test_rejected_first_deploy_stops_application_without_approved_state(self):
        self.state_file.unlink()
        self.before = None
        self.active = None
        self.bad_responses = {"/": "unavailable503"}

        with self.assertRaises(RuntimeError):
            release.release(self.config, "deploy", CANDIDATE)

        self.assertEqual(self.operations(), ["run", "up", "stop"])
        self.assertEqual(self.actions[-1], (["stop", "web", "api"], CANDIDATE))
        self.assertIsNone(self.active)
        self.assertIsNone(self.read_state())

    def test_failed_migration_never_activates_candidate_or_changes_state(self):
        self.fail_operation = "run"

        with self.assertRaises(subprocess.CalledProcessError):
            release.release(self.config, "deploy", CANDIDATE)

        self.assertEqual(self.operations(), ["run"])
        self.assertEqual(self.probes, [])
        self.assertEqual(self.active, APPROVED)
        self.assertEqual(self.read_state(), self.before)

    def test_failed_start_restores_approved_images_and_reports_failure(self):
        self.fail_operation = "up"

        with self.assertRaises(subprocess.CalledProcessError):
            release.release(self.config, "deploy", CANDIDATE)

        self.assertEqual(self.operations(), ["run", "up", "up"])
        self.assert_probes(APPROVED)
        self.assertEqual(self.active, APPROVED)
        self.assertEqual(self.read_state(), self.before)

    def test_rollback_without_previous_revision_has_no_external_effects(self):
        self.state_file.write_text(json.dumps({"current": APPROVED, "previous": None}))
        self.before = self.state_file.read_bytes()

        with self.assertRaises(RuntimeError):
            release.release(self.config, "rollback", {})

        self.process.assert_not_called()
        self.assertEqual(self.read_state(), self.before)

    def test_deploy_requires_both_images_by_digest_before_external_effects(self):
        for images in ({}, {"web": CANDIDATE["web"]}, {"web": "web:latest", "api": CANDIDATE["api"]}):
            with self.subTest(images=images):
                with self.assertRaises(ValueError):
                    release.release(self.config, "deploy", images)

                self.process.assert_not_called()
                self.assertEqual(self.read_state(), self.before)


if __name__ == "__main__":
    unittest.main()
