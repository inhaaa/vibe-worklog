"""Offline public-demo deployment boundaries; stdlib only, no DB/network access."""
import json
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
DEPLOY = ROOT / "deploy" / "worklog"
PASSWORDS = {"POSTGRES_PASSWORD", "TNA_MIGRATION_DB_PASSWORD", "TNA_APP_DB_PASSWORD",
             "TNA_AUTH_DB_PASSWORD", "TNA_OPERATOR_DB_PASSWORD"}


class PublicDemoDeployTest(unittest.TestCase):
    def compose(self):
        path = DEPLOY / "compose.yaml"
        self.assertTrue(path.is_file(), "독립 공개 데모 Compose가 필요합니다")
        return json.loads(path.read_text(encoding="utf-8"))

    def test_resources_are_dedicated_and_only_app_is_published_on_loopback(self):
        config = self.compose()
        self.assertEqual("worklog-demo", config["name"])
        self.assertEqual({"db", "migrate", "bootstrap", "app"}, set(config["services"]))
        self.assertEqual({"postgres-data"}, set(config["volumes"]))
        self.assertEqual("worklog-demo-postgres-data", config["volumes"]["postgres-data"]["name"])
        self.assertFalse(config["volumes"]["postgres-data"].get("external", False))
        self.assertEqual({"private"}, set(config["networks"]))
        self.assertEqual("worklog-demo-private", config["networks"]["private"]["name"])
        self.assertFalse(config["networks"]["private"].get("external", False))
        for name, service in config["services"].items():
            with self.subTest(service=name):
                self.assertNotIn("container_name", service)
                self.assertFalse(service.get("privileged", False))
                self.assertEqual(["127.0.0.1:18081:18080"] if name == "app" else [], service.get("ports", []))
                if name == "bootstrap":
                    self.assertEqual("service:db", service["network_mode"])
                    self.assertNotIn("networks", service)
                else:
                    self.assertEqual(["private"], service["networks"])
                for mount in service.get("volumes", []):
                    self.assertNotIn("docker.sock", mount)

    def test_database_credentials_are_split_from_webapp_and_bootstrap_is_explicit(self):
        services = self.compose()["services"]
        expected = {"db": PASSWORDS, "migrate": {"TNA_MIGRATION_DB_PASSWORD"},
                    "bootstrap": {"TNA_MIGRATION_DB_PASSWORD"},
                    "app": {"TNA_APP_DB_PASSWORD", "TNA_AUTH_DB_PASSWORD"}}
        for name, service in services.items():
            env = service["environment"]
            self.assertEqual(expected[name], set(env) & PASSWORDS, name)
            self.assertNotIn("env_file", service, name)
            for secret in expected[name]:
                self.assertRegex(env[secret], r"^\$\{" + secret + r":\?[^}]+\}$")
        self.assertEqual("worklog_demo", services["db"]["environment"]["POSTGRES_DB"])
        self.assertEqual("jdbc:postgresql://db:5432/worklog_demo", services["app"]["environment"]["TNA_JDBC_URL"])
        self.assertEqual("jdbc:postgresql://db:5432/worklog_demo", services["migrate"]["environment"]["TNA_JDBC_URL"])
        bootstrap = services["bootstrap"]
        self.assertEqual(["setup"], bootstrap["profiles"])
        self.assertEqual(["public-demo-bootstrap"], bootstrap["command"])
        self.assertEqual("jdbc:postgresql://127.0.0.1:5432/worklog_demo", bootstrap["environment"]["TNA_JDBC_URL"])
        self.assertEqual("public-demo", bootstrap["environment"]["SPRING_PROFILES_ACTIVE"])
        self.assertEqual("worklog-demo", bootstrap["environment"]["TNA_PUBLIC_DEMO_CONFIRM"])
        for name in ["migrate", "bootstrap"]:
            self.assertEqual("no", services[name]["restart"])
            self.assertEqual("service_healthy", services[name]["depends_on"]["db"]["condition"])
        self.assertEqual("service_completed_successfully", services["app"]["depends_on"]["migrate"]["condition"])
        self.assertNotIn("bootstrap", services["app"]["depends_on"])

    def test_public_profile_requires_explicit_proxy_trust_and_secure_cookie(self):
        env = self.compose()["services"]["app"]["environment"]
        self.assertEqual("public-demo", env["SPRING_PROFILES_ACTIVE"])
        self.assertEqual("false", env["TNA_OPERATOR_ENABLED"])
        self.assertEqual("true", env["SERVER_SERVLET_SESSION_COOKIE_SECURE"])
        self.assertEqual("native", env["SERVER_FORWARD_HEADERS_STRATEGY"])
        self.assertEqual("0.0.0.0", env["SERVER_ADDRESS"])
        self.assertEqual("18080", env["SERVER_PORT"])
        self.assertRegex(env["SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES"],
                         r"^\$\{SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES:\?[^}]+\}$")

    def test_existing_builds_are_reused_with_bounded_runtime(self):
        services = self.compose()["services"]
        self.assertEqual({"context": "../../infra/postgres"}, services["db"]["build"])
        self.assertIn("../../infra/postgres/001-roles.sh:/docker-entrypoint-initdb.d/001-roles.sh:ro", services["db"]["volumes"])
        self.assertIn("postgres-data:/var/lib/postgresql", services["db"]["volumes"])
        for name, service in services.items():
            with self.subTest(service=name):
                if name != "db":
                    self.assertEqual({"context": "../..", "target": "runtime" if name == "app" else "migrate"}, service["build"])
                self.assertEqual("postgres" if name == "db" else "10001:10001", service["user"])
                self.assertTrue(service["read_only"])
                self.assertIn("ALL", service["cap_drop"])
                self.assertIn("no-new-privileges:true", service["security_opt"])
                self.assertTrue(any(mount.split(":", 1)[0] == "/tmp" for mount in service["tmpfs"]))
                self.assertGreater(float(service["cpus"]), 0)
                self.assertLessEqual(float(service["cpus"]), 1)
                self.assertRegex(service["mem_limit"], r"^\d+[mMgG]$")
                self.assertGreater(service["pids_limit"], 0)
                self.assertLessEqual(service["pids_limit"], 256)
                self.assertEqual("json-file", service["logging"]["driver"])
                self.assertRegex(service["logging"]["options"]["max-size"], r"^\d+m$")
                self.assertLessEqual(int(service["logging"]["options"]["max-file"]), 3)
        self.assertTrue(any(mount.split(":", 1)[0] == "/var/run/postgresql" for mount in services["db"]["tmpfs"]))

    def test_caddy_uses_only_new_host_and_replaces_forwarded_identity(self):
        path = DEPLOY / "Caddyfile.example"
        self.assertTrue(path.is_file(), "기존 설정에 추가할 새 호스트 블록이 필요합니다")
        text = "\n".join(line for line in path.read_text(encoding="utf-8").splitlines()
                         if not line.lstrip().startswith("#"))
        for scheme in ["http", "https"]:
            self.assertRegex(text, r"(?m)^" + scheme + r"://worklog\.inhaaa\.com\s*\{")
        self.assertEqual(2, text.count("bind __CONFIRMED_BIND_ADDRESSES__"))
        self.assertIn("redir https://worklog.inhaaa.com{uri} permanent", text)
        self.assertIn("reverse_proxy 127.0.0.1:18081", text)
        for header in ["header_up -Forwarded", "header_up X-Forwarded-For {remote_host}",
                       "header_up Host {hostport}", "header_up X-Forwarded-Host {hostport}",
                       "header_up X-Forwarded-Proto https"]:
            self.assertIn(header, text)
        self.assertNotIn("trusted_proxies", text)
        self.assertEqual(2, len(re.findall(r"(?m)^\S+\.com\s*\{", text)))

    def test_documented_commands_override_an_inherited_compose_project(self):
        commands = [line for line in (DEPLOY / 'README.md').read_text(encoding='utf-8').splitlines()
                    if line.startswith('docker compose ')]
        self.assertTrue(commands)
        self.assertTrue(all(line.startswith('docker compose -p worklog-demo ') for line in commands),
                        'COMPOSE_PROJECT_NAME이 기존 서비스를 선택하지 못하게 -p를 고정해야 합니다')

    def test_env_example_contains_no_usable_secret_and_no_default_proxy_trust(self):
        path = DEPLOY / ".env.example"
        self.assertTrue(path.is_file(), "공개 데모 전용 환경 예시가 필요합니다")
        values = dict(line.split("=", 1) for line in path.read_text(encoding="utf-8").splitlines()
                      if line.strip() and not line.lstrip().startswith("#"))
        for secret in PASSWORDS:
            self.assertTrue(values[secret].startswith("CHANGE_ME_"), secret)
        self.assertEqual("", values["SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES"])


if __name__ == "__main__":
    unittest.main()
