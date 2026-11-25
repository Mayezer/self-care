#!/usr/bin/env python3
"""Promote a pair of immutable images on a Docker Compose host."""

import argparse
from datetime import date, timedelta
import fcntl
import ipaddress
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from urllib.parse import urlsplit


ROOT = Path(__file__).resolve().parent
IMAGE = re.compile(r"(?:[a-z0-9][a-z0-9.\-/:_]*@)?sha256:[0-9a-f]{64}")


def smoke(config):
    origin = urlsplit(config["smoke_origin"])
    if (origin.scheme != "https" or not origin.hostname or origin.username is not None
            or origin.path or origin.query or origin.fragment):
        raise ValueError("Informe a origem HTTPS de verificação, sem caminho nem credenciais.")
    address = ipaddress.ip_address(config["smoke_address"])
    resolve = f"{origin.hostname}:{origin.port or 443}:{address}"
    args = ["curl", "--silent", "--show-error", "--noproxy", "*", "--proto", "=https", "--connect-timeout", "3",
            "--max-time", "10", "--resolve", resolve, "--write-out", "%{http_code}"]
    if config.get("smoke_ca_file"):
        args += ["--cacert", config["smoke_ca_file"]]

    def check(path, status, marker, payload=None):
        request = args.copy()
        if payload is not None:
            request += ["--header", "Content-Type: application/json", "--data-binary", json.dumps(payload)]
        result = subprocess.run([*request, origin.geturl() + path], check=True, capture_output=True, text=True).stdout
        if result[-3:] != str(status):
            raise RuntimeError(f"Sonda {path} esperava HTTP {status} e recebeu {result[-3:]}.")
        if marker not in result[:-3]:
            raise RuntimeError(f"Sonda {path} recebeu HTTP {status} com conteúdo inesperado.")

    check("/", 200, "<app-root")
    check("/api/ready", 200, '"UP"')
    check("/api/agenda", 401, "")
    check("/api/forum/public/topics", 404, "")
    check("/api/hair-plans/preview", 200, '"occurrences"', {
        "startDate": (date.today() + timedelta(days=2)).isoformat(), "weeks": 1,
        "weekdays": ["MONDAY", "THURSDAY"], "timeZone": "America/Sao_Paulo",
    })


def compose(config, images, *command):
    if set(images) != {"web", "api"} or not all(IMAGE.fullmatch(image) for image in images.values()):
        raise ValueError("Informe as duas imagens por digest SHA-256.")
    environment = {**os.environ, "AUTOCUIDADO_WEB_IMAGE": images["web"], "AUTOCUIDADO_API_IMAGE": images["api"]}
    subprocess.run(["docker", "compose", "--env-file", config["env_file"], "-p", config["project"],
                    "-f", str(ROOT / "compose.yaml"), *command], env=environment, check=True)


def start(config, images):
    compose(config, images, "up", "-d", "--no-deps", "--pull", "never", "--wait", "--wait-timeout", "120", "api", "web")
    smoke(config)


def save_state(directory, state):
    with tempfile.NamedTemporaryFile(mode="w", dir=directory, delete=False) as pending:
        json.dump(state, pending)
        name = pending.name
    try:
        os.replace(name, directory / "images.json")
    finally:
        Path(name).unlink(missing_ok=True)


def release(config, operation, images):
    directory = Path(config["state_dir"])
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (directory / "lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        state_file = directory / "images.json"
        state = json.loads(state_file.read_text()) if state_file.exists() else {}
        current = state.get("current")
        target = images if operation == "deploy" else state.get("previous")
        if target is None:
            raise RuntimeError("Ainda não há uma revisão anterior aprovada para reversão.")
        if set(target) != {"web", "api"} or not all(IMAGE.fullmatch(image) for image in target.values()):
            raise ValueError("Informe as duas imagens por digest SHA-256.")
        for service, image in target.items():
            cached = subprocess.run(["docker", "image", "inspect", image], capture_output=True).returncode == 0
            if not cached:
                compose(config, target, "pull", service)
        if operation == "deploy":
            compose(config, target, "run", "--rm", "--no-deps", "--pull", "never", "migrate")
        try:
            start(config, target)
            if target != current:
                save_state(directory, {"current": target, "previous": current})
        except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError):
            if current is None:
                compose(config, target, "stop", "web", "api")
            else:
                print("A revisão falhou. Restaurando as últimas imagens aprovadas.", flush=True)
                start(config, current)
            raise
    print("Sondas HTTPS aprovadas e imagens ativas registradas.")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, default=Path("/etc/autocuidado/release.json"))
    parser.add_argument("operation", choices=("deploy", "rollback", "smoke"))
    parser.add_argument("images", nargs="*")
    args = parser.parse_args()
    try:
        config = json.loads(args.config.read_text())
        if args.operation == "deploy" and len(args.images) == 2:
            release(config, "deploy", dict(zip(("web", "api"), args.images)))
        elif args.operation == "rollback" and not args.images:
            release(config, "rollback", {})
        elif args.operation == "smoke" and not args.images:
            smoke(config)
            print("Sondas HTTPS aprovadas.")
        else:
            parser.error("Passe as imagens web e API apenas para deploy.")
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Falha na publicação: {error}\n")


if __name__ == "__main__":
    main()
