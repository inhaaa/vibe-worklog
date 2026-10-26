"""Login-page parsing and one-hop redirect boundary; no HTTP, DB, or Docker."""
import importlib.util
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location("local_http", Path(__file__).with_name("verify-local-http.py"))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)
FORM = '<input type="hidden" name="_csrf" value="synthetic-csrf">'


class Requests:
    def __init__(self, *responses):
        self.responses = list(responses)
        self.paths = []

    def __call__(self, path):
        self.paths.append(path)
        return self.responses.pop(0)


class LoginPageTest(unittest.TestCase):
    def test_csrf_parser_requires_a_nonempty_input(self):
        self.assertEqual("synthetic-csrf", probe.csrf(FORM))
        for html in ["<p>No token</p>", '<input name="_csrf" value="">']:
            with self.subTest(html=html), self.assertRaises(AssertionError):
                probe.csrf(html)

    def test_normal_login_is_read_without_a_redirect(self):
        headers = {"Cache-Control": "no-store"}
        request = Requests((200, headers, FORM))
        self.assertEqual((headers, FORM), probe.login_page(request))
        self.assertEqual(["/login"], request.paths)

    def test_exact_demo_redirect_is_followed_once_and_its_form_is_validated(self):
        headers = {"Cache-Control": "no-store"}
        request = Requests((302, {"Location": "/demo"}, ""), (200, headers, FORM))
        self.assertEqual((headers, FORM), probe.login_page(request))
        self.assertEqual(["/login", "/demo"], request.paths)

    def test_external_arbitrary_or_nonexact_redirects_are_rejected_before_following(self):
        for location in ["https://outside.example/demo", "http://127.0.0.1:18080/demo",
                         "//outside.example/demo", "/login", "/admin/employees", "/demo/",
                         "/demo?next=/admin", "/demo#entry", " /demo", None]:
            request = Requests((302, {"Location": location}, ""))
            with self.subTest(location=location), self.assertRaises(AssertionError):
                probe.login_page(request)
            self.assertEqual(["/login"], request.paths)
        for status in [301, 303, 307, 308, 401, 503]:
            request = Requests((status, {"Location": "/demo"}, ""))
            with self.subTest(status=status), self.assertRaises(AssertionError):
                probe.login_page(request)
            self.assertEqual(["/login"], request.paths)

    def test_demo_response_must_be_200_with_csrf_and_cannot_redirect_again(self):
        for response in [(302, {"Location": "/demo"}, FORM),
                         (302, {"Location": "https://outside.example/"}, FORM),
                         (403, {}, FORM), (200, {}, "<p>No token</p>")]:
            request = Requests((302, {"Location": "/demo"}, ""), response)
            with self.subTest(response=response), self.assertRaises(AssertionError):
                probe.login_page(request)
            self.assertEqual(["/login", "/demo"], request.paths)

    def test_automatic_redirect_handling_remains_disabled(self):
        for url in ["/demo", "https://outside.example/"]:
            self.assertIsNone(probe.NoRedirect().redirect_request(None, None, 302, "", {}, url))

    def test_absolute_demo_location_must_match_the_exact_loopback_origin(self):
        base = "http://127.0.0.1:18080"
        response = (200, {}, FORM)
        request = Requests((302, {"Location": base + "/demo"}, ""), response)
        self.assertEqual(({}, FORM), probe.login_page(request, base))
        self.assertEqual(["/login", "/demo"], request.paths)
        for location in ["http://127.0.0.1:18081/demo", "https://127.0.0.1:18080/demo",
                         "http://127.0.0.1:18080@outside.example/demo", base + "/demo?next=/",
                         base + "/demo#intro", base + "/demo/", "https://outside.example/demo"]:
            request = Requests((302, {"Location": location}, ""))
            with self.subTest(location=location), self.assertRaises(AssertionError):
                probe.login_page(request, base)
            self.assertEqual(["/login"], request.paths)


if __name__ == "__main__":
    unittest.main()
