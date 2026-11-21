import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
OPENING = ("compose.yaml", "compose.operator.yaml", "compose.opening.yaml")


def config(*files):
    env = os.environ.copy()
    env.pop("COMPOSE_PROJECT_NAME", None)
    for name in ("POSTGRES_PASSWORD", "TNA_MIGRATION_DB_PASSWORD", "TNA_APP_DB_PASSWORD",
                 "TNA_AUTH_DB_PASSWORD", "TNA_OPERATOR_DB_PASSWORD"):
        env[name] = f"synthetic-only-{name}"
    with tempfile.TemporaryDirectory() as temp:
        env_file = Path(temp) / "empty.env"
        env_file.touch()
        args = ["docker", "compose", "--env-file", str(env_file)]
        for file in files:
            args.extend(["-f", file])
        result = subprocess.run(args + ["config", "--format", "json"], cwd=ROOT, env=env,
                                capture_output=True, text=True, encoding="utf-8", check=True)
    return json.loads(result.stdout)


@unittest.skipUnless(shutil.which("docker"), "Docker Compose CLI required")
class OpeningComposeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.base = config("compose.yaml")
        cls.opening = config(*OPENING)

    def test_demo_and_customer_opening_have_separate_login_profiles_and_storage(self):
        self.assertEqual(self.base["services"]["app"]["environment"]["SPRING_PROFILES_ACTIVE"], "local,local-demo")
        self.assertNotIn("mailpit", self.base["services"])
        self.assertEqual(self.opening["name"], "vibe-attendance-opening")
        self.assertEqual(self.opening["volumes"]["postgres-data"]["name"], "vibe-attendance-opening_postgres-data")
        self.assertNotEqual(self.base["services"]["db"]["image"], self.opening["services"]["db"]["image"])
        app = self.opening["services"]["app"]["environment"]
        self.assertEqual(app["SPRING_PROFILES_ACTIVE"], "local")
        self.assertEqual(app["TNA_OPERATOR_ENABLED"], "true")
        self.assertNotIn("TNA_MIGRATION_DB_PASSWORD", app)
        self.assertNotIn("POSTGRES_PASSWORD", app)

    def test_only_app_database_and_mailbox_ui_are_published_on_loopback(self):
        expected = {"app": (18082, 18080), "db": (15434, 5432), "mailpit": (18025, 8025)}
        for name, (host, target) in expected.items():
            ports = self.opening["services"][name]["ports"]
            self.assertEqual([(p["host_ip"], int(p["published"]), p["target"]) for p in ports],
                             [("127.0.0.1", host, target)])

    def test_account_mail_targets_the_private_capture_server(self):
        services = self.opening["services"]
        app = services["app"]["environment"]
        self.assertEqual(app["TNA_MAIL_MODE"], "smtp")
        self.assertEqual(app["TNA_MAIL_PUBLIC_ORIGIN"], "http://127.0.0.1:18082")
        self.assertEqual(app["TNA_MAIL_FROM"], "worklog@example.test")
        self.assertEqual((app["SPRING_MAIL_HOST"], app["SPRING_MAIL_PORT"]), ("mailpit", "1025"))
        self.assertEqual(services["app"]["depends_on"]["mailpit"]["condition"], "service_healthy")
        settings = json.loads(app["SPRING_APPLICATION_JSON"])
        for key in ("enable", "required"):
            self.assertIs(settings[f"spring.mail.properties[mail.smtp.starttls.{key}]"], False)
        self.assertTrue(services["mailpit"]["image"].startswith("axllent/mailpit:v1.34.0@sha256:"))
        self.assertNotIn("volumes", services["mailpit"])


if __name__ == "__main__":
    unittest.main()
