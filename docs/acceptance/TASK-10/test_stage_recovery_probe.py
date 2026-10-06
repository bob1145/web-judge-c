import copy
import io
import json
import sys
import time
import unittest
from types import SimpleNamespace

from stage_recovery_probe import MAX_BODY, ProbePolicyError, ProxyPolicy, parse_request, validate_unknown


ATTEMPT = "12345678-1234-1234-1234-123456789abc"
STAGE = "12345678-1234-1234-1234-123456789def"
NAME = "wjc-stage-" + STAGE


class StageRecoveryProbeContractsTest(unittest.TestCase):
    def config(self):
        return SimpleNamespace(stage_volume="wjc-stage-fixture", volume_holder_id="b" * 64,
                               executor_image="sha256:" + "a" * 64, uid=65532, gid=65532)

    def payload(self):
        config = self.config()
        return {"Image": config.executor_image, "User": "65532:65532",
                "Labels": {"io.webjudge.attempt": ATTEMPT, "io.webjudge.stage": STAGE,
                           "io.webjudge.role": "USER", "io.webjudge.managed": "stage"},
                "Cmd": ["g++", "/input/source.cpp", "-o", "/scratch/program", "-O2", "-std=c++17"],
                "HostConfig": {"NetworkMode": "none", "ReadonlyRootfs": True, "Privileged": False,
                               "CapDrop": ["ALL"], "SecurityOpt": ["no-new-privileges"],
                               "Mounts": [{"Type": "volume", "Source": config.stage_volume, "Target": "/" + name,
                                           "ReadOnly": name == "input", "VolumeOptions": {"Subpath": STAGE + "/" + name, "NoCopy": True}}
                                          for name in ("input", "scratch")]}}

    def claim(self, policy, payload=None):
        body = json.dumps(self.payload() if payload is None else payload).encode()
        return policy.accept("POST", "/v1.52/containers/create?name=" + NAME, {"content-type": "application/json"}, body)

    def journal(self):
        return {"journalVersion": 1, "state": "CREATE_UNKNOWN", "creationState": "UNKNOWN",
                "attempt": ATTEMPT, "stage": STAGE, "volume": self.config().stage_volume,
                "role": "USER", "phase": "compile", "name": NAME, "labels": self.payload()["Labels"]}

    def test_read_whitelist_allows_only_fixed_probe_resources(self):
        policy = ProxyPolicy(self.config(), ATTEMPT)
        for method, path in (("HEAD", "/_ping"), ("GET", "/_ping"), ("GET", "/version"),
                             ("GET", "/v1.52/volumes/" + self.config().stage_volume),
                             ("GET", "/v1.52/containers/" + self.config().volume_holder_id + "/json")):
            with self.subTest(path=path):
                self.assertEqual("read", policy.accept(method, path, {}, b""))

    def test_foreign_reads_queries_and_encoded_paths_are_rejected(self):
        for target in ("/containers/foreign/json", "/volumes/foreign", "/info", "/version?secret=value", "/%2e%2e/version"):
            with self.subTest(target=target), self.assertRaises(ProbePolicyError):
                ProxyPolicy(self.config(), ATTEMPT).accept("GET", target, {}, b"")

    def test_runtime_start_delete_and_exec_are_not_proxied(self):
        for method, path in (("POST", "/containers/" + NAME + "/start"),
                             ("DELETE", "/containers/" + NAME), ("POST", "/containers/" + NAME + "/exec")):
            with self.subTest(path=path), self.assertRaises(ProbePolicyError):
                ProxyPolicy(self.config(), ATTEMPT).accept(method, path, {}, b"{}")

    def test_only_one_valid_create_is_claimed(self):
        policy = ProxyPolicy(self.config(), ATTEMPT)
        self.assertEqual("create", self.claim(policy))
        self.assertEqual(STAGE, policy.create["stage"])
        self.assertEqual("read", policy.accept("GET", "/containers/" + NAME + "/json", {}, b""))
        with self.assertRaisesRegex(ProbePolicyError, "duplicate"):
            self.claim(policy)
        self.assertEqual(2, policy.create_attempts)

    def test_invalid_json_and_duplicate_fields_are_rejected(self):
        for body in (b"not-json", b"null", b"[]", b'{"Image":"one","Image":"two"}'):
            with self.subTest(body=body), self.assertRaises(ProbePolicyError):
                ProxyPolicy(self.config(), ATTEMPT).accept("POST", "/containers/create?name=" + NAME, {}, body)

    def test_oversized_create_body_is_rejected(self):
        with self.assertRaises(ProbePolicyError):
            ProxyPolicy(self.config(), ATTEMPT).accept("POST", "/containers/create?name=" + NAME, {}, b"a" * (MAX_BODY + 1))

    def test_foreign_ownership_image_and_command_are_rejected(self):
        for field, value in (("Image", "floating:latest"), ("Labels", {}), ("Cmd", ["/bin/sh"])):
            payload = self.payload()
            payload[field] = value
            with self.subTest(field=field), self.assertRaises(ProbePolicyError):
                self.claim(ProxyPolicy(self.config(), ATTEMPT), payload)

    def test_mount_escape_and_writable_input_are_rejected(self):
        for mutate in (lambda payload: payload["HostConfig"]["Mounts"][0].update({"ReadOnly": False}),
                       lambda payload: payload["HostConfig"]["Mounts"][0]["VolumeOptions"].update({"Subpath": "../control"}),
                       lambda payload: payload["HostConfig"].update({"Privileged": True})):
            payload = self.payload()
            mutate(payload)
            with self.assertRaises(ProbePolicyError):
                self.claim(ProxyPolicy(self.config(), ATTEMPT), payload)

    def test_volume_copy_up_is_rejected(self):
        for index in range(2):
            with self.subTest(index=index), self.assertRaises(ProbePolicyError):
                payload = self.payload()
                payload["HostConfig"]["Mounts"][index]["VolumeOptions"]["NoCopy"] = False
                self.claim(ProxyPolicy(self.config(), ATTEMPT), payload)

    def test_parser_accepts_fixed_length_json(self):
        parsed = parse_request(io.BytesIO(b"POST /containers/create?name=fixture HTTP/1.1\r\nHost: local\r\nContent-Length: 2\r\n\r\n{}"))
        self.assertEqual(("POST", "/containers/create?name=fixture"), parsed[:2])
        self.assertEqual(b"{}", parsed[3])

    def test_parser_rejects_chunked_duplicate_length_and_sensitive_headers(self):
        for headers in (b"Transfer-Encoding: chunked\r\n", b"Content-Length: 0\r\nContent-Length: 0\r\n",
                        b"Authorization: secret\r\n", b"X-Registry-Auth: secret\r\n", b"Expect: 100-continue\r\n"):
            with self.subTest(header=headers.split(b":", 1)[0]), self.assertRaises(ProbePolicyError):
                parse_request(io.BytesIO(b"POST /containers/create HTTP/1.1\r\n" + headers + b"\r\n"))

    def test_parser_rejects_oversized_or_truncated_body(self):
        for value in (b"Content-Length: 131073\r\n\r\n", b"Content-Length: 3\r\n\r\n{}",
                      b"Content-Length: -1\r\n\r\n", b"Content-Length: 0002\r\n\r\n{}"):
            with self.subTest(value=value[:25]), self.assertRaises(ProbePolicyError):
                parse_request(io.BytesIO(b"POST /containers/create HTTP/1.1\r\n" + value))

    def test_parser_rejects_read_body_and_unbounded_header(self):
        for request in (b"GET /version HTTP/1.1\r\nContent-Length: 2\r\n\r\n{}",
                        b"GET /version HTTP/1.1\r\nX-Header: " + b"a" * 2048 + b"\r\n\r\n"):
            with self.assertRaises(ProbePolicyError):
                parse_request(io.BytesIO(request))

    def test_parser_deadline_is_checked_before_read(self):
        with self.assertRaisesRegex(ProbePolicyError, "deadline"):
            parse_request(io.BytesIO(b"GET /version HTTP/1.1\r\n\r\n"), deadline=time.monotonic() - 1)

    def test_unknown_journal_is_accepted_only_as_unresolved(self):
        self.assertEqual(STAGE, validate_unknown(self.journal(), ATTEMPT, self.config().stage_volume))

    def test_cleaned_confirmed_and_wrong_version_are_not_unknown_evidence(self):
        for field, value in (("state", "CLEANED"), ("state", "CREATE_REQUESTED"),
                             ("creationState", "CONFIRMED"), ("journalVersion", True), ("journalVersion", 2)):
            journal = self.journal()
            journal[field] = value
            with self.subTest(field=field, value=value), self.assertRaises(ProbePolicyError):
                validate_unknown(journal, ATTEMPT, self.config().stage_volume)

    def test_foreign_journal_and_stage_labels_are_rejected(self):
        for field, value in (("attempt", "foreign"), ("volume", "foreign"), ("role", "ORACLE"), ("labels", {}), ("stage", 123)):
            journal = copy.deepcopy(self.journal())
            journal[field] = value
            with self.subTest(field=field), self.assertRaises(ProbePolicyError):
                validate_unknown(journal, ATTEMPT, self.config().stage_volume)


if __name__ == "__main__":
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(StageRecoveryProbeContractsTest))
    print(json.dumps({"tests": result.testsRun, "failures": len(result.failures), "errors": len(result.errors),
                      "skipped": len(result.skipped), "scope": "proxy policy and evidence contracts only; no real daemon evidence"}))
    sys.exit(0 if result.wasSuccessful() and result.testsRun == 17 else 1)
