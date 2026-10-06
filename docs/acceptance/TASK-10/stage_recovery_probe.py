import hashlib
import http.client
import json
import os
import re
import shutil
import socket
import socketserver
import stat
import threading
import time
import uuid
from dataclasses import asdict, replace
from pathlib import Path
from urllib.parse import parse_qsl, urlsplit


MAX_BODY = 128 * 1024
MAX_HEADERS = 16 * 1024
MAX_REQUESTS = 64
MAX_CONCURRENT = 4
UPSTREAM_SOCKET = "/run/wjc/docker.sock"


class ProbePolicyError(ValueError):
    pass


class RecoveryProbeFailure(RuntimeError):
    requires_domain_stop = True


def require(condition, message):
    if not condition:
        raise ProbePolicyError(message)


def digest_bytes(data):
    return hashlib.sha256(data).hexdigest()


def unique_fields(pairs):
    result = {}
    for name, value in pairs:
        require(name not in result, "duplicate JSON field")
        result[name] = value
    return result


def parse_request(stream, deadline=None, set_timeout=None):
    def bounded_read(method, size):
        if deadline is not None:
            remaining = deadline - time.monotonic()
            require(remaining > 0, "request read deadline expired")
            if set_timeout is not None:
                set_timeout(remaining)
        data = getattr(stream, method)(size)
        require(deadline is None or time.monotonic() <= deadline, "request read deadline expired")
        return data

    line = bounded_read("readline", 4097)
    require(len(line) <= 4096 and line.endswith(b"\r\n"), "invalid request line")
    try:
        method, target, version = line[:-2].decode("ascii").split(" ")
    except (UnicodeError, ValueError):
        raise ProbePolicyError("invalid request line") from None
    require(version in {"HTTP/1.0", "HTTP/1.1"} and method in {"GET", "HEAD", "POST"}, "unsupported request method")
    require(target.startswith("/") and not target.startswith("//") and all(32 < ord(char) < 127 for char in target),
            "invalid request target")
    headers = {}
    header_bytes = 0
    for _ in range(33):
        line = bounded_read("readline", 2049)
        header_bytes += len(line)
        require(len(line) <= 2048 and header_bytes <= MAX_HEADERS and line.endswith(b"\r\n"), "headers exceed bounds")
        if line == b"\r\n":
            break
        require(len(headers) < 32 and not line.startswith((b" ", b"\t")), "invalid header layout")
        try:
            name, value = line[:-2].decode("ascii").split(":", 1)
        except (UnicodeError, ValueError):
            raise ProbePolicyError("invalid header") from None
        name = name.lower()
        require(re.fullmatch(r"[a-z0-9-]+", name) is not None and name not in headers, "invalid or duplicate header")
        value = value.strip()
        require(all(32 <= ord(char) < 127 for char in value), "invalid header value")
        require(name not in {"transfer-encoding", "authorization", "proxy-authorization", "x-registry-auth", "upgrade", "expect"},
                "unsupported request header")
        headers[name] = value
    else:
        raise ProbePolicyError("unterminated headers")
    length_text = headers.get("content-length", "0")
    require(re.fullmatch(r"0|[1-9][0-9]{0,5}", length_text) is not None, "invalid content length")
    length = int(length_text)
    require(length <= MAX_BODY and (method == "POST" or length == 0), "request body exceeds policy")
    body = bounded_read("read", length)
    require(len(body) == length, "truncated request body")
    return method, target, headers, body


class ProxyPolicy:
    def __init__(self, config, attempt):
        self.config = config
        self.attempt = attempt
        self.lock = threading.Lock()
        self.create = None
        self.create_attempts = 0
        self.created_id = None

    def accept(self, method, target, headers, body):
        require(len(body) <= MAX_BODY and "%" not in target and ".." not in target, "invalid request target or body")
        parsed = urlsplit(target)
        require(not parsed.scheme and not parsed.netloc and not parsed.fragment, "nonlocal request target")
        path = re.sub(r"^/v[0-9]+[.][0-9]+(?=/)", "", parsed.path)
        if method in {"GET", "HEAD"}:
            require(not body and not parsed.query, "read request must not contain body or query")
            if path == "/_ping" or (path == "/version" and method == "GET"):
                return "read"
            require(method == "GET", "unsupported HEAD target")
            if path == "/volumes/" + self.config.stage_volume:
                return "read"
            with self.lock:
                names = {self.config.volume_holder_id}
                if self.create is not None:
                    names.add(self.create["name"])
                if self.created_id is not None:
                    names.add(self.created_id)
            require(any(path == "/containers/" + name + "/json" for name in names), "container inspect is outside probe ownership")
            return "read"
        require(method == "POST" and path == "/containers/create", "runtime mutation is not permitted")
        with self.lock:
            self.create_attempts += 1
            require(self.create is None, "duplicate create is not permitted")
            try:
                query = parse_qsl(parsed.query, keep_blank_values=True, strict_parsing=True, max_num_fields=2)
                payload = json.loads(body.decode("utf-8"), object_pairs_hook=unique_fields)
            except (ValueError, UnicodeError, RecursionError):
                raise ProbePolicyError("invalid create payload") from None
            require(isinstance(payload, dict) and len(query) == 1 and query[0][0] == "name", "invalid create query or payload")
            name = query[0][1]
            require(name.startswith("wjc-stage-"), "invalid stage name")
            stage = name.removeprefix("wjc-stage-")
            try:
                require(str(uuid.UUID(stage)) == stage, "invalid stage UUID")
            except ValueError:
                raise ProbePolicyError("invalid stage UUID") from None
            expected_labels = {"io.webjudge.attempt": self.attempt, "io.webjudge.stage": stage,
                               "io.webjudge.role": "USER", "io.webjudge.managed": "stage"}
            require(payload.get("Labels") == expected_labels and payload.get("Image") == self.config.executor_image,
                    "create ownership or image differs")
            require(payload.get("User") == str(self.config.uid) + ":" + str(self.config.gid), "create user differs")
            require(payload.get("Cmd") == ["g++", "/input/source.cpp", "-o", "/scratch/program", "-O2", "-std=c++17"],
                    "create command is not the fixed compile stage")
            host = payload.get("HostConfig", {})
            require(isinstance(host, dict) and host.get("NetworkMode") == "none" and host.get("ReadonlyRootfs") is True
                    and host.get("Privileged", False) is False and not host.get("Binds") and not host.get("VolumesFrom")
                    and not host.get("Devices") and host.get("PidMode", "") == "", "unsafe create host configuration")
            require(host.get("CapDrop") == ["ALL"] and isinstance(host.get("SecurityOpt"), list)
                    and "no-new-privileges" in host["SecurityOpt"],
                    "create privileges differ")
            mounts = host.get("Mounts")
            require(isinstance(mounts, list) and len(mounts) == 2 and all(isinstance(mount, dict) for mount in mounts)
                    and {mount.get("Target") for mount in mounts} == {"/input", "/scratch"},
                    "create mounts differ")
            for mount in mounts:
                target_name = mount["Target"].removeprefix("/")
                require(mount.get("Type") == "volume" and mount.get("Source") == self.config.stage_volume
                        and mount.get("ReadOnly", False) is (target_name == "input")
                        and mount.get("VolumeOptions", {}).get("Subpath") == stage + "/" + target_name
                        and mount.get("VolumeOptions", {}).get("NoCopy") is True,
                        "create volume subpath differs")
            self.create = {"name": name, "stage": stage, "labels": expected_labels,
                           "bodySha256": digest_bytes(body), "bodyBytes": len(body)}
            return "create"


class DedicatedConnection(http.client.HTTPConnection):
    def __init__(self):
        super().__init__("localhost", timeout=10)

    def connect(self):
        connection = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        connection.settimeout(self.timeout)
        try:
            connection.connect(UPSTREAM_SOCKET)
        except BaseException:
            connection.close()
            raise
        self.sock = connection


class RecoveryProxy:
    def __init__(self, config, attempt):
        require(config.runtime_endpoint == "unix://" + UPSTREAM_SOCKET, "probe upstream must be the dedicated daemon")
        require(stat.S_ISSOCK(Path(UPSTREAM_SOCKET).lstat().st_mode), "dedicated daemon socket is missing")
        self.path = Path("/run/wjc") / ("recovery-" + attempt + ".sock")
        require(not self.path.exists() and not self.path.is_symlink(), "probe socket already exists")
        self.policy = ProxyPolicy(config, attempt)
        self.lock = threading.Lock()
        self.release = threading.Event()
        self.abort = threading.Event()
        self.held = threading.Event()
        self.terminal = threading.Event()
        self.transcript = []
        self.threads = []
        self.active = 0
        self.request_count = 0
        self.forwarded_creates = 0
        self.create_response = None
        self.faults = []
        self.deadline = time.monotonic() + 60
        self.closed = False
        proxy = self

        class Server(socketserver.UnixStreamServer):
            request_queue_size = MAX_CONCURRENT

            def process_request(server, request, address):
                with proxy.lock:
                    if proxy.request_count >= MAX_REQUESTS or proxy.active >= MAX_CONCURRENT or time.monotonic() > proxy.deadline:
                        if "request budget exhausted" not in proxy.faults:
                            proxy.faults.append("request budget exhausted")
                        server.shutdown_request(request)
                        return
                    proxy.request_count += 1
                    proxy.active += 1
                    worker = threading.Thread(target=server.run_request, args=(request, address), daemon=True)
                    proxy.threads.append(worker)
                    worker.start()

            def run_request(server, request, address):
                try:
                    server.finish_request(request, address)
                finally:
                    server.shutdown_request(request)
                    with proxy.lock:
                        proxy.active -= 1

        class Handler(socketserver.StreamRequestHandler):
            timeout = 5

            def handle(handler):
                method = None
                try:
                    method, target, headers, body = parse_request(handler.rfile, min(proxy.deadline, time.monotonic() + 5),
                                                                  handler.connection.settimeout)
                    handler.connection.settimeout(5)
                    action = proxy.policy.accept(method, target, headers, body)
                    proxy.note("request-accepted", method=method, target=target, action=action,
                               bodyBytes=len(body), bodySha256=digest_bytes(body))
                    if action == "create":
                        proxy.held.set()
                        try:
                            require(proxy.release.wait(timeout=25), "held create release deadline expired")
                            require(not proxy.abort.is_set(), "held create was aborted before forwarding")
                            with proxy.lock:
                                proxy.forwarded_creates += 1
                            response = proxy.forward(method, target, headers, body)
                            status_code, _, response_body = response
                            require(status_code == 201, "dedicated daemon did not create the held container")
                            payload = json.loads(response_body)
                            require(re.fullmatch(r"[0-9a-f]{64}", payload.get("Id", "")) is not None,
                                    "late create response has no complete container ID")
                            with proxy.policy.lock:
                                proxy.policy.created_id = payload["Id"]
                            proxy.create_response = {"status": status_code, "containerId": payload["Id"],
                                                     "bodyBytes": len(response_body), "bodySha256": digest_bytes(response_body)}
                            proxy.note("late-create-response", **proxy.create_response)
                        finally:
                            proxy.terminal.set()
                    else:
                        response = proxy.forward(method, target, headers, body)
                    handler.respond(*response, head=method == "HEAD")
                except (BrokenPipeError, ConnectionResetError):
                    proxy.note("client-disconnected", heldCreateWasForwarded=proxy.create_response is not None)
                except Exception as exception:
                    with proxy.lock:
                        proxy.faults.append(type(exception).__name__)
                    proxy.note("request-rejected-or-failed", errorType=type(exception).__name__)
                    try:
                        handler.respond(502, [], b'{"message":"bounded recovery probe rejected request"}', head=method == "HEAD")
                    except (OSError, ValueError):
                        pass

            def respond(handler, status_code, headers, body, head=False):
                require(len(body) <= MAX_BODY, "response body exceeds bound")
                reason = http.client.responses.get(status_code, "Probe")
                prefix = "HTTP/1.1 " + str(status_code) + " " + reason + "\r\n"
                for name, value in headers:
                    if name.lower() in {"api-version", "docker-experimental", "ostype", "server", "content-type", "builder-version"}:
                        require("\r" not in value and "\n" not in value, "invalid response header")
                        prefix += name + ": " + value + "\r\n"
                prefix += "Content-Length: " + str(0 if head else len(body)) + "\r\nConnection: close\r\n\r\n"
                encoded = prefix.encode("latin-1")
                require(len(encoded) <= MAX_HEADERS, "response headers exceed bound")
                handler.wfile.write(encoded + (b"" if head else body))
                handler.wfile.flush()

        self.server = Server(str(self.path), Handler)
        self.path.chmod(0o600)
        self.socket_identity = (self.path.stat().st_dev, self.path.stat().st_ino)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
        self.thread.start()

    def note(self, event, **details):
        with self.lock:
            require(len(self.transcript) < MAX_REQUESTS * 4, "proxy transcript exceeds bound")
            self.transcript.append({"event": event, **details})

    def forward(self, method, target, headers, body):
        connection = DedicatedConnection()
        try:
            forwarded_headers = {"Connection": "close"}
            if "content-type" in headers:
                forwarded_headers["Content-Type"] = headers["content-type"]
            connection.request(method, target, body=body if method == "POST" else None, headers=forwarded_headers)
            response = connection.getresponse()
            response_headers = response.getheaders()
            require(sum(len(name) + len(value) + 4 for name, value in response_headers) <= MAX_HEADERS,
                    "upstream response headers exceed bound")
            payload = response.read(MAX_BODY + 1)
            require(len(payload) <= MAX_BODY, "upstream response body exceeds bound")
            self.note("upstream-response", method=method, target=target, status=response.status,
                      bodyBytes=len(payload), bodySha256=digest_bytes(payload))
            return response.status, response_headers, payload
        finally:
            connection.close()

    def stop(self):
        if self.closed:
            return self.shutdown_proof()
        self.abort.set()
        self.release.set()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        deadline = time.monotonic() + 12
        for worker in self.threads:
            worker.join(timeout=max(0, deadline - time.monotonic()))
        require(not self.thread.is_alive() and not any(worker.is_alive() for worker in self.threads) and self.active == 0,
                "proxy handlers have not all terminated; stop the dedicated domain")
        require(not self.held.is_set() or self.terminal.is_set(), "held create has not terminated; stop the dedicated domain")
        metadata = self.path.lstat()
        require(stat.S_ISSOCK(metadata.st_mode) and (metadata.st_dev, metadata.st_ino) == self.socket_identity,
                "proxy socket identity changed")
        self.path.unlink()
        self.closed = True
        return self.shutdown_proof()

    def shutdown_proof(self):
        return {"closed": self.closed, "serverThreadAlive": self.thread.is_alive(),
                "handlerThreadsAlive": sum(worker.is_alive() for worker in self.threads), "activeHandlers": self.active,
                "heldCreateTerminal": not self.held.is_set() or self.terminal.is_set(),
                "requestCount": self.request_count, "createRequestCount": self.policy.create_attempts,
                "forwardedCreateCount": self.forwarded_creates, "createResponse": self.create_response,
                "faults": list(self.faults)}


def validate_unknown(journal, attempt, volume):
    require(isinstance(journal, dict), "unknown journal must be an object")
    require(type(journal.get("journalVersion")) is int and journal.get("journalVersion") == 1 and journal.get("state") == "CREATE_UNKNOWN"
            and journal.get("creationState") == "UNKNOWN", "product did not preserve unknown creation state")
    stage = journal.get("stage", "")
    require(isinstance(stage, str), "unknown journal has an invalid stage")
    try:
        require(str(uuid.UUID(stage)) == stage, "unknown journal has an invalid stage")
    except ValueError:
        raise ProbePolicyError("unknown journal has an invalid stage") from None
    require(journal.get("attempt") == attempt and journal.get("volume") == volume and journal.get("role") == "USER"
            and journal.get("phase") == "compile" and journal.get("name") == "wjc-stage-" + stage,
            "unknown journal ownership differs")
    require(journal.get("labels") == {"io.webjudge.attempt": attempt, "io.webjudge.stage": stage,
            "io.webjudge.role": "USER", "io.webjudge.managed": "stage"}, "unknown journal labels differ")
    return stage


def _run_recovery_probe(config, attempt, control, report, client, invoke, record):
    from linux_stage_executor import LinuxStageExecutor, StageSecurityError
    from task_runner import run_process

    require(client[-2:] == ["--host", "unix://" + UPSTREAM_SOCKET], "recovery probe requires the dedicated direct client")
    require(list(config.stage_root.iterdir()) == [], "recovery probe requires an initially empty task volume")
    root = control / "stage-recovery"
    root.mkdir(mode=0o700)
    evidence = report / "stage-recovery"
    evidence.mkdir()
    source = root / "source.cpp"
    source.write_text("int main() { return 0; }\n")
    shutil.copyfile(source, evidence / "source.cpp")
    shutil.copyfile(Path(__file__), evidence / "stage_recovery_probe.py")
    artifact = root / "program"
    journal_root = root / "journal"
    proxy = None
    completed = False
    shutdown = None
    journal_bytes = None
    journal_path = None
    try:
        proxy = RecoveryProxy(config, attempt)
        proxied_config = replace(config, runtime_endpoint="unix://" + str(proxy.path), command_timeout_seconds=2)
        config_payload = asdict(proxied_config)
        config_payload["stage_root"] = str(proxied_config.stage_root)
        (evidence / "proxy-stage-config.json").write_text(json.dumps(config_payload, indent=2) + "\n")
        limits = {"stdin": b"", "timeout_seconds": 10, "memory_limit_bytes": 256 * 1024 ** 2,
                  "output_limit_bytes": 4096, "cancel_path": root / "cancel.requested"}
        started = time.monotonic()
        try:
            LinuxStageExecutor(proxied_config, attempt, journal_root, run_process).compile("USER", source, artifact, **limits)
        except StageSecurityError:
            pass
        else:
            raise ProbePolicyError("held create did not reject through the actual product")
        elapsed = time.monotonic() - started
        require(proxy.held.is_set() and proxy.policy.create_attempts == 1 and proxy.forwarded_creates == 0
                and 1.5 <= elapsed <= 15, "the product failure was not the intended held-create timeout")
        paths = list(journal_root.glob("*.json"))
        require(len(paths) == 1, "held create must have one product journal")
        journal_path = paths[0]
        journal_metadata = journal_path.lstat()
        require(stat.S_ISREG(journal_metadata.st_mode) and journal_metadata.st_nlink == 1
                and 0 < journal_metadata.st_size <= 65536, "unknown product journal is not a bounded regular file")
        journal_bytes = journal_path.read_bytes()
        require(len(journal_bytes) <= 65536, "unknown product journal exceeds bound")
        journal = json.loads(journal_bytes)
        stage = validate_unknown(journal, attempt, config.stage_volume)
        require(journal_path.name == stage + ".json", "unknown product journal filename differs from its stage")
        require(proxy.policy.create["stage"] == stage and not journal.get("containerId"), "held request differs from unknown journal")
        directory = config.stage_root / stage

        def save_retained_state(name):
            require(journal_path.read_bytes() == journal_bytes and not artifact.exists(), "product changed unknown evidence or exported an artifact")
            require(directory.is_dir() and not directory.is_symlink() and (directory / "input/source.cpp").read_bytes() == source.read_bytes()
                    and (directory / "scratch").is_dir(), "unknown stage inputs or scratch were not retained")
            entries = []
            for path in sorted(directory.rglob("*")):
                metadata = path.lstat()
                require(not path.is_symlink(), "retained unknown stage contains a link")
                details = {"path": str(path.relative_to(directory)), "uid": metadata.st_uid, "gid": metadata.st_gid,
                           "mode": format(stat.S_IMODE(metadata.st_mode), "04o"), "bytes": metadata.st_size}
                if stat.S_ISREG(metadata.st_mode):
                    require(metadata.st_nlink == 1 and metadata.st_size <= MAX_BODY, "retained input is not a bounded regular file")
                    details["sha256"] = digest_bytes(path.read_bytes())
                entries.append(details)
            require({entry["path"] for entry in entries} == {"input", "input/source.cpp", "scratch"},
                    "retained directory has unexpected content")
            (evidence / (name + "-journal.json")).write_bytes(journal_bytes)
            (evidence / (name + "-retained.json")).write_text(json.dumps(entries, indent=2) + "\n")
            return entries

        save_retained_state("after-timeout")
        record("recovery-create-timeout-unknown", attempt=attempt, stage=stage, elapsedSeconds=elapsed,
               journalSha256=digest_bytes(journal_bytes), creationState=journal["creationState"], state=journal["state"],
               createForwarded=False, artifactExists=False, directoryRetained=True)

        def reject_new_instance(name):
            before_requests = proxy.request_count
            try:
                LinuxStageExecutor(proxied_config, attempt, journal_root, run_process).compile("USER", source, artifact, **limits)
            except StageSecurityError:
                pass
            else:
                raise ProbePolicyError("a new executor bypassed the persisted unknown-state gate")
            require(proxy.policy.create_attempts == 1 and proxy.request_count == before_requests,
                    "a gated new executor contacted the runtime or retried create")
            save_retained_state(name)
            record("recovery-new-instance-rejected", phase=name, createRequestCount=proxy.policy.create_attempts,
                   runtimeRequestCountUnchanged=True, productJournalSha256=digest_bytes(journal_bytes))

        reject_new_instance("before-late-create")
        proxy.release.set()
        require(proxy.terminal.wait(timeout=15) and proxy.create_response is not None, "late create did not finish at the dedicated daemon")
        identity = proxy.create_response["containerId"]
        inspection = json.loads(invoke(client + ["container", "inspect", identity]).stdout)[0]
        require(inspection.get("Id") == identity and inspection.get("Name") == "/" + journal["name"]
                and inspection.get("Config", {}).get("Labels") == journal["labels"]
                and inspection.get("Image") == config.executor_image, "late container identity, ownership or image differs")
        state = inspection.get("State", {})
        require(state.get("Status") == "created" and state.get("Running") is False and state.get("Pid") == 0,
                "late container was unexpectedly started")
        (evidence / "late-container-inspect.json").write_text(json.dumps(inspection, indent=2) + "\n")
        save_retained_state("after-late-create")
        scratch = (directory / "scratch").stat()
        require(scratch.st_uid == os.geteuid() and scratch.st_gid == os.getegid()
                and stat.S_IMODE(scratch.st_mode) == 0o777, "create changed trusted scratch ownership or mode")
        reject_new_instance("after-late-create-new-instance")
        shutdown = proxy.stop()
        require(shutdown["closed"] and shutdown["heldCreateTerminal"] and shutdown["handlerThreadsAlive"] == 0
                and shutdown["forwardedCreateCount"] == 1 and shutdown["faults"] == [], "proxy cannot prove all create holders terminated")
        (evidence / "proxy-shutdown-before-teardown.json").write_text(json.dumps(shutdown, indent=2) + "\n")
        record("recovery-proxy-closed-before-teardown", **shutdown)
        current = json.loads(invoke(client + ["container", "inspect", identity]).stdout)[0]
        require(current.get("Id") == identity and current.get("Config", {}).get("Labels") == journal["labels"]
                and current.get("State", {}).get("Status") == "created" and current["State"].get("Running") is False,
                "late container ownership changed before experimental teardown")
        invoke(client + ["container", "rm", identity])
        remaining = invoke(client + ["container", "ls", "--all", "--quiet", "--no-trunc"]).stdout.decode().split()
        require(remaining == [config.volume_holder_id], "unexpected containers remain after experimental teardown")
        require(directory.parent == config.stage_root and directory.resolve() == directory and not directory.is_symlink()
                and shutil.rmtree.avoids_symlink_attacks, "retained directory cannot be safely removed")
        shutil.copyfile(directory / "input/source.cpp", evidence / "retained-input-source.cpp")
        shutil.rmtree(directory)
        require(list(config.stage_root.iterdir()) == [] and journal_path.read_bytes() == journal_bytes and not artifact.exists(),
                "experimental teardown changed product journal evidence or left volume data")
        record("recovery-harness-teardown-not-product-recovery", containerId=identity, directoryRemoved=str(directory),
               remainingContainerIds=remaining, productJournalState="CREATE_UNKNOWN", productCreationState="UNKNOWN",
               productJournalSha256=digest_bytes(journal_bytes), productJournalUnchanged=True,
               scope="only experiment-owned late container and retained directory removed after all proxy threads terminated")
        completed = True
    except Exception as exception:
        record("recovery-probe-failure", errorType=type(exception).__name__,
               requiredAction="terminate the entire dedicated runtime domain; do not perform stage-level recovery cleanup")
        raise RecoveryProbeFailure("recovery probe failed; terminate the dedicated domain without stage cleanup") from None
    finally:
        if proxy is not None:
            try:
                shutdown = proxy.stop()
            except Exception as exception:
                shutdown = {**proxy.shutdown_proof(), "stopErrorType": type(exception).__name__}
                record("recovery-proxy-stop-unproven", **shutdown)
            (evidence / "proxy-transcript.json").write_text(json.dumps(proxy.transcript, indent=2) + "\n")
            (evidence / "proxy-final-state.json").write_text(json.dumps(shutdown, indent=2) + "\n")
        if journal_path is not None and journal_path.is_file():
            shutil.copyfile(journal_path, evidence / "product-journal-final.json")
        (evidence / "result.json").write_text(json.dumps({"completed": completed, "productRecoveryClaim": False,
            "proxyShutdown": shutdown, "artifactExists": artifact.exists(),
            "failureTeardownPolicy": "whole dedicated domain only; no stage rm or directory deletion on failure"}, indent=2) + "\n")


def run_recovery_probe(config, attempt, control, report, client, invoke, record):
    try:
        return _run_recovery_probe(config, attempt, control, report, client, invoke, record)
    except RecoveryProbeFailure:
        raise
    except BaseException:
        raise RecoveryProbeFailure("recovery probe did not complete; terminate the dedicated domain without stage cleanup") from None
