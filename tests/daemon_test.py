from contextlib import closing, suppress
import base64
import hashlib
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import threading
import json
import os
from pathlib import Path
import secrets
import selectors
import ssl
import subprocess
import sys
import tempfile
import time
import unittest
from urllib.parse import urlencode


DAEMON = Path(sys.argv.pop(1)).resolve()


def anthropic_stream(blocks, stop_reason):
    """Encode assistant blocks as an Anthropic Messages SSE stream."""
    events = [{"type": "message_start", "message": {"id": "msg_test", "type": "message", "role": "assistant",
               "model": "mock-claude", "content": [], "usage": {"input_tokens": 11, "output_tokens": 1}}}]
    for index, block in enumerate(blocks):
        kind = block["type"]
        if kind == "text":
            events.append({"type": "content_block_start", "index": index, "content_block": {"type": "text", "text": ""}})
            text = block["text"]
            for part in (text[: len(text) // 2], text[len(text) // 2:]):
                events.append({"type": "content_block_delta", "index": index, "delta": {"type": "text_delta", "text": part}})
        elif kind == "thinking":
            events.append({"type": "content_block_start", "index": index,
                           "content_block": {"type": "thinking", "thinking": "", "signature": ""}})
            events.append({"type": "content_block_delta", "index": index,
                           "delta": {"type": "thinking_delta", "thinking": block["thinking"]}})
            events.append({"type": "content_block_delta", "index": index,
                           "delta": {"type": "signature_delta", "signature": block["signature"]}})
        elif kind == "tool_use":
            events.append({"type": "content_block_start", "index": index, "content_block":
                           {"type": "tool_use", "id": block["id"], "name": block["name"], "input": {}}})
            raw = json.dumps(block["input"])
            for part in (raw[:5], raw[5:]):
                events.append({"type": "content_block_delta", "index": index,
                               "delta": {"type": "input_json_delta", "partial_json": part}})
        events.append({"type": "content_block_stop", "index": index})
    events.append({"type": "message_delta", "delta": {"stop_reason": stop_reason}, "usage": {"output_tokens": 7}})
    events.append({"type": "message_stop"})
    return "".join(f"event: {e['type']}\ndata: {json.dumps(e)}\n\n" for e in events).encode()


def openai_stream(content=None, reasoning=None, calls=(), finish="stop"):
    """Encode a Chat Completions SSE stream with optional reasoning and tool calls."""
    chunks = []
    if reasoning:
        chunks.append({"choices": [{"index": 0, "delta": {"reasoning_content": reasoning}}]})
    if content:
        for part in (content[: len(content) // 2], content[len(content) // 2:]):
            chunks.append({"choices": [{"index": 0, "delta": {"content": part}}]})
    for index, (call_id, name, arguments) in enumerate(calls):
        raw = json.dumps(arguments)
        chunks.append({"choices": [{"index": 0, "delta": {"tool_calls": [
            {"index": index, "id": call_id, "type": "function", "function": {"name": name, "arguments": raw[:4]}}]}}]})
        chunks.append({"choices": [{"index": 0, "delta": {"tool_calls": [
            {"index": index, "function": {"arguments": raw[4:]}}]}}]})
    chunks.append({"model": "mock-local", "choices": [{"index": 0, "delta": {}, "finish_reason": finish}]})
    chunks.append({"choices": [], "usage": {"prompt_tokens": 20, "completion_tokens": 5}})
    return "".join(f"data: {json.dumps(c)}\n\n" for c in chunks).encode() + b"data: [DONE]\n\n"


SEARCH_KEY_VARIABLES = ("BRAVE_SEARCH_API_KEY", "BRAVE_SEARCH_API_KEYS", "TAVILY_API_KEY", "TAVILY_API_KEYS",
                        "FIRECRAWL_API_KEY", "FIRECRAWL_API_KEYS")


class MockProvider:
    """A scripted model API: each request pops the next canned stream."""

    def __init__(self):
        self.script = []
        self.requests = []
        self.lock = threading.Lock()
        provider = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                with provider.lock:
                    provider.requests.append({"path": self.path, "headers": dict(self.headers), "body": body})
                    reply = provider.script.pop(0) if provider.script else None
                if callable(reply):
                    reply = reply(body)
                if reply is None:
                    payload = json.dumps({"error": {"type": "invalid_request_error", "message": "script exhausted"}}).encode()
                    status, content_type = 400, "application/json"
                else:
                    payload = reply
                    status, content_type = 200, "text/event-stream"
                # Stop closes the client socket before a stalled mock sends its reply.
                with suppress(BrokenPipeError, ConnectionResetError):
                    self.send_response(status)
                    self.send_header("Content-Type", content_type)
                    self.send_header("Content-Length", str(len(payload)))
                    self.end_headers()
                    self.wfile.write(payload)

        self.release = threading.Event()
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.server.block_on_close = False
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    @property
    def url(self):
        return f"http://127.0.0.1:{self.server.server_address[1]}"

    def hang(self, _body):
        self.release.wait(20)  # a model that says nothing for a long time
        return None

    def close(self):
        self.release.set()
        self.server.shutdown()
        self.server.server_close()


class DaemonTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="tsun-test-")
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        self.workspace = root / "repo"
        self.workspace.mkdir()
        self.text = self.workspace / "hello & 日本語.txt"
        self.text.write_text("hello\n", encoding="utf-8")
        self.text.chmod(0o751)
        (self.workspace / "empty").touch()
        (self.workspace / "sub").mkdir()
        (self.workspace / "sub" / "inside.txt").write_text("nested", encoding="utf-8")
        self.binary = bytes(range(256)) * 1000
        (self.workspace / "sample.bin").write_bytes(self.binary)
        (self.workspace / ".secret").write_text("not visible", encoding="utf-8")
        outside = root / "outside"
        outside.write_text("outside", encoding="utf-8")
        os.link(outside, self.workspace / "hard")
        (self.workspace / "symlink").symlink_to(outside)
        (self.workspace / "linked-dir").symlink_to(root, target_is_directory=True)
        os.mkfifo(self.workspace / "named-pipe")

        self.repo = root / "git project"
        self.repo.mkdir()
        self.git(self.repo, "init", "--quiet", "--initial-branch=main", "--template=")
        (self.repo / "notes.txt").write_text("committed base\n", encoding="utf-8")
        self.git(self.repo, "add", "notes.txt")
        self.git(self.repo, "-c", "user.name=Tsun Test", "-c", "user.email=test@example.invalid",
                 "-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "Test base")
        (self.repo / "notes.txt").write_text("uncommitted base change\n", encoding="utf-8")
        unborn = root / "unborn"
        unborn.mkdir()
        self.git(unborn, "init", "--quiet", "--initial-branch=main", "--template=")
        isolated_preset = {"id": "edit", "argv": ["/bin/sh", "-c",
            "printf 'preset output\\n' > result.txt; pwd; echo ready; exec /bin/sleep 60"]}

        self.home = root / "home"
        self.home.mkdir()
        self.state = root / "state"
        self.state.mkdir(mode=0o700)
        self.token_file = self.state / "token"
        self.token = secrets.token_hex(32)
        self.token_file.write_text(self.token + "\n", encoding="ascii")
        self.token_file.chmod(0o600)
        self.cert = self.state / "cert.pem"
        key = self.state / "key.pem"
        subprocess.run([
            "openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1",
            "-noenc", "-days", "2", "-sha256", "-subj", "/CN=Tsun test",
            "-addext", "subjectAltName=IP:127.0.0.1", "-keyout", str(key), "-out", str(self.cert),
        ], capture_output=True, check=True, timeout=5)
        key.chmod(0o600)
        self.tls = ssl.create_default_context(cafile=str(self.cert))
        self.model = MockProvider()
        self.addCleanup(self.model.close)
        key_file = self.state / "anthropic-key"
        key_file.write_text("test-key\n", encoding="ascii")
        key_file.chmod(0o600)
        self.config = config = self.state / "config.json"
        config.write_text(json.dumps({
            "port": 0,
            "agent": {"default": "claude", "providers": [
                {"id": "claude", "kind": "anthropic", "label": "Mock Claude", "model": "mock-claude",
                 "baseUrl": self.model.url},
                {"id": "local", "kind": "openai", "label": "Mock local", "model": "mock-local",
                 "baseUrl": self.model.url + "/v1", "apiKeyEnv": ""},
            ]},
            "workspaces": [{
                "id": "demo", "root": str(self.workspace), "worktrees": False, "commands": [
                    {"id": "linger", "argv": ["/bin/sh", "-c", "pwd; echo running; exec /bin/sleep 60"]},
                    {"id": "finish", "argv": ["/bin/sh", "-c", "echo finished; echo stderr >&2; exit 7"]},
                    {"id": "success", "argv": ["/bin/true"]},
                    {"id": "flood", "argv": [sys.executable, "-c", "import sys; sys.stdout.buffer.write(b'a'*200000 + b'\\xffEND')"]},
                    {"id": "child", "argv": ["/bin/sh", "-c", "trap '' TERM; /bin/sleep 60 & echo $!; wait"]},
                    {"id": "missing", "argv": ["/no-such-executable-tsun-test"]},
                ],
            }, {
                "id": "isolated", "root": str(self.repo), "commands": [isolated_preset],
            }, {
                "id": "unborn", "root": str(unborn), "commands": [isolated_preset],
            }, {
                "id": "not-git", "root": str(self.workspace), "commands": [isolated_preset],
            }],
        }), encoding="utf-8")
        self.process = None
        self.start_daemon()
        self.addCleanup(self.shutdown)

    def start_daemon(self, extra_env=None):
        # Search keys from the host are dropped, so a developer's own keys never reach a test.
        env = {k: v for k, v in os.environ.items() if k not in SEARCH_KEY_VARIABLES}
        env["HOME"] = str(self.home)
        env.update(extra_env or {})
        self.process = subprocess.Popen(
            [str(DAEMON), str(self.state)], stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, text=True, encoding="utf-8", env=env,
        )
        with selectors.DefaultSelector() as selector:
            selector.register(self.process.stdout, selectors.EVENT_READ)
            self.assertTrue(selector.select(timeout=5), "daemon did not announce a listening port")
        line = self.process.stdout.readline().strip()
        self.assertTrue(line.startswith("Tsun listening on https://127.0.0.1:"), line)
        self.port = int(line.rsplit(":", 1)[1])

    def restart(self):
        self.shutdown()
        self.start_daemon()

    @staticmethod
    def git(directory, *args):
        return subprocess.run(["/usr/bin/git", "-C", str(directory), "-c", "core.hooksPath=/dev/null", *args],
                              check=True, capture_output=True, text=True, timeout=5).stdout.strip()

    def shutdown(self):
        if self.process is None:
            return
        if self.process.poll() is None:
            self.process.terminate()
        try:
            _, errors = self.process.communicate(timeout=6)
        except subprocess.TimeoutExpired:
            self.process.kill()
            _, errors = self.process.communicate()
            self.fail("daemon did not stop on SIGTERM: " + errors)
        self.assertEqual(self.process.returncode, 0, errors)

    def request(self, method, path, *, headers=None, body=None, auth=True, status=200):
        fields = {"Authorization": "Bearer " + self.token} if auth else {}
        fields.update(headers or {})
        connection = http.client.HTTPSConnection("127.0.0.1", self.port, timeout=3, context=self.tls)
        try:
            connection.request(method, path, body, fields)
            response = connection.getresponse()
            data = response.read()
            self.assertEqual(response.status, status, data[:200])
            return data, response.headers
        finally:
            connection.close()

    def api(self, method, path, **options):
        return json.loads(self.request(method, path, **options)[0])

    def file_path(self, endpoint, path="", *, workspace="demo", run=None):
        params = {"workspace": workspace, "path": path}
        if run is not None:
            params["run"] = run
        return "/v1/" + endpoint + "?" + urlencode(params)

    def launch(self, command, status=201, *, workspace="demo", title=None):
        body = {"workspace": workspace, "command": command}
        if title is not None:
            body["title"] = title
        return self.api("POST", "/v1/jobs", status=status, headers={"Content-Type": "application/json"},
                        body=json.dumps(body))

    def until(self, job_id, predicate, timeout=5):
        deadline = time.monotonic() + timeout
        while True:
            job = self.api("GET", "/v1/jobs/" + job_id)
            if predicate(job):
                return job
            if time.monotonic() >= deadline:
                self.fail("Job did not reach expected state: " + str({**job, "log": job["log"][-200:]}))
            time.sleep(0.03)

    def test_pairing_and_catalog(self):
        self.api("GET", "/v1/workspaces", auth=False, status=401)
        self.api("GET", "/v1/workspaces", headers={"Authorization": "Bearer wrong"}, status=401)
        self.api("GET", "/v1/workspaces", headers={"Origin": "https://preview.invalid"}, status=403)
        self.api("GET", "/v1/workspaces", headers={"Host": "example.test"}, status=403)
        data, headers = self.request("GET", "/v1/workspaces")
        self.assertEqual(headers["Cache-Control"], "no-store")
        catalog = json.loads(data)
        self.assertEqual(catalog["protocol"], 3)
        self.assertEqual(catalog["agent"]["default"], "claude")
        self.assertEqual([p["kind"] for p in catalog["agent"]["providers"]], ["anthropic", "openai"])
        self.assertTrue(all(p["enabled"] for p in catalog["agent"]["providers"]))
        self.assertEqual(catalog["workspaces"][0]["id"], "demo")
        self.assertFalse(catalog["workspaces"][0]["worktrees"])
        self.assertTrue(catalog["workspaces"][1]["worktrees"])
        self.assertNotIn(b"argv", data)
        self.assertNotIn(os.fsencode(self.workspace), data)
        self.api("GET", "/v1/missing", status=404)
        self.api("GET", "/v1/workspaces?overview=1", auth=False, status=401)
        self.api("GET", "/v1/workspaces?overview=1&overview=1", status=400)
        overview = self.api("GET", "/v1/workspaces?overview=1")
        self.assertEqual(overview.pop("runs"), self.api("GET", "/v1/jobs"))
        self.assertEqual(overview.pop("threads"), self.api("GET", "/v1/threads"))
        self.assertEqual(overview, catalog)

    def test_tls_and_pairing_code(self):
        with self.assertRaises(ssl.SSLCertVerificationError):
            with closing(http.client.HTTPSConnection("127.0.0.1", self.port, timeout=3)) as other:
                other.connect()
        settings = json.loads(self.config.read_text(encoding="utf-8"))
        settings["port"] = self.port
        self.config.write_text(json.dumps(settings), encoding="utf-8")
        arguments = [str(DAEMON), "--pairing-code", str(self.state)]
        result = subprocess.run(arguments, capture_output=True, check=True, text=True, timeout=3)
        digest = hashlib.sha256(ssl.PEM_cert_to_DER_cert(self.cert.read_text(encoding="ascii"))).hexdigest()
        expected = f"tsun1:{self.port}:{self.token}:{digest}"
        self.assertTrue(secrets.compare_digest(result.stdout.strip(), expected), "Incorrect pairing code")
        self.token_file.chmod(0o644)
        refused = subprocess.run(arguments, capture_output=True, text=True, timeout=3)
        self.assertNotEqual(refused.returncode, 0)
        self.assertFalse(refused.stdout, "Must not print a pairing code from a public token file")

    def test_browse_edit_and_detect_stale_version(self):
        listing = self.api("GET", self.file_path("entries"))
        self.assertEqual([row["name"] for row in listing["entries"]], ["sub", "empty", self.text.name, "sample.bin"])
        self.assertFalse(listing["truncated"])
        file = self.file_path("file", self.text.name)
        original = self.api("GET", file)
        self.assertEqual(original["text"], "hello\n")
        self.api("PUT", file, headers={"Content-Type": "text/plain"}, body=b"no version", status=428)
        headers = {"Content-Type": "text/plain; charset=utf-8", "If-Match": original["revision"]}
        updated = self.api("PUT", file, headers=headers, body="edited from Android ✓\n".encode())
        self.assertNotEqual(original["revision"], updated["revision"])
        self.assertEqual(self.text.read_text(encoding="utf-8"), "edited from Android ✓\n")
        self.assertEqual(self.text.stat().st_mode & 0o777, 0o751)
        self.api("PUT", file, headers=headers, body=b"stale edit", status=409)
        self.text.write_text("changed outside\n", encoding="utf-8")
        self.api("PUT", file, headers={**headers, "If-Match": updated["revision"]}, body=b"another stale edit", status=409)
        nested_path = self.file_path("file", "sub/inside.txt")
        nested = self.api("GET", nested_path)
        self.assertEqual(nested["text"], "nested")
        self.api("PUT", nested_path, headers={**headers, "If-Match": nested["revision"]}, body=b"")
        self.assertEqual(self.api("GET", nested_path)["text"], "")
        self.assertEqual(self.text.read_text(encoding="utf-8"), "changed outside\n")

    def test_download_and_path_boundaries(self):
        asset = self.file_path("download", "sample.bin")
        body, headers = self.request("GET", asset)
        self.assertEqual(body, self.binary)
        self.assertEqual(headers["Content-Disposition"], "attachment")
        self.assertEqual(headers["Content-Type"], "application/octet-stream")
        partial, _ = self.request("GET", asset, headers={"Range": "bytes=2-7"}, status=206)
        self.assertEqual(partial, self.binary[2:8])
        self.assertEqual(self.request("GET", self.file_path("download", "empty"))[0], b"")
        self.api("GET", self.file_path("file", "sample.bin"), status=415)
        for name in ("../outside", "/outside", "sub/../empty", ".secret", "sub//inside.txt", "sub\\inside.txt"):
            with self.subTest(name=name):
                self.api("GET", self.file_path("download", name), status=400)
        for name in ("symlink", "linked-dir/outside", "hard", "named-pipe"):
            with self.subTest(name=name):
                self.api("GET", self.file_path("download", name), status=404)
        self.api("GET", "/v1/entries?workspace=demo&workspace=demo", status=400)
        self.api("GET", "/v1/entries?workspace=unknown", status=404)

    def test_two_jobs_continue_between_connections(self):
        first, second = self.launch("linger"), self.launch("linger")
        self.assertNotEqual(first["id"], second["id"])
        self.launch("linger", status=409)
        visible = self.api("GET", "/v1/jobs")
        self.assertEqual(visible["capacity"], 2)
        self.assertEqual({item["id"] for item in visible["jobs"]}, {first["id"], second["id"]})
        for job_id in (first["id"], second["id"]):
            running = self.until(job_id, lambda job: "running\n" in job["log"])
            self.assertEqual(running["state"], "running")
            self.assertTrue(running["log"].startswith(str(self.workspace) + "\n"))
        self.api("POST", "/v1/jobs/" + first["id"] + "/stop")
        self.until(first["id"], lambda job: job["state"] == "stopped")
        failed = self.until(self.launch("finish")["id"], lambda job: job["state"] == "failed")
        self.assertEqual(failed["exitCode"], 7)
        self.assertEqual(failed["log"], "finished\nstderr\n")
        succeeded = self.until(self.launch("success")["id"], lambda job: job["state"] == "succeeded")
        self.assertEqual(succeeded["exitCode"], 0)
        self.launch("missing", status=500)
        self.launch("not-allowed", status=404)
        self.assertEqual(self.api("GET", "/v1/jobs/" + second["id"])["state"], "running")
        # Leave this job running to exercise the daemon's shutdown cleanup.

    def test_two_tasks_have_separate_branches_files_and_commands(self):
        first = self.launch("edit", workspace="isolated", title="  First task  ")
        second = self.launch("edit", workspace="isolated", title="Second task")
        self.assertEqual(first["label"], "First task")
        self.assertEqual(first["state"], "preparing")
        self.assertFalse(first["filesReady"])
        self.assertNotEqual(first["branch"], second["branch"])
        self.launch("edit", status=409, workspace="isolated")

        def path(endpoint, name="", job=None):
            return self.file_path(endpoint, name, workspace="isolated", run=job and job["id"])

        for index, job in enumerate((first, second), start=1):
            running = self.until(job["id"], lambda result: "ready\n" in result["log"])
            self.assertTrue(running["filesReady"])
            checkout = self.state / "worktrees" / ("isolated-" + job["id"])
            self.assertIn(str(checkout) + "\n", running["log"])
            self.assertEqual(self.git(checkout, "branch", "--show-current"), job["branch"])
            self.assertEqual(self.git(checkout, "rev-parse", "HEAD"), self.git(self.repo, "rev-parse", "HEAD"))
            self.assertEqual(self.request("GET", path("download", "result.txt", job))[0], b"preset output\n")
            entries = self.api("GET", path("entries", job=job))["entries"]
            self.assertEqual({item["name"] for item in entries}, {"notes.txt", "result.txt"})
            file = path("file", "notes.txt", job)
            original = self.api("GET", file)
            self.assertEqual(original["text"], "committed base\n")
            self.api("PUT", file, headers={"Content-Type": "text/plain", "If-Match": original["revision"]},
                     body=f"edit in task {index}\n".encode())

        self.api("POST", f"/v1/jobs/{first['id']}/stop")
        self.until(first["id"], lambda run: run["state"] == "stopped")
        self.assertEqual(self.api("GET", path("file", "notes.txt", first))["text"], "edit in task 1\n")
        self.assertEqual(self.api("GET", path("file", "notes.txt", second))["text"], "edit in task 2\n")
        self.assertEqual(self.api("GET", path("file", "notes.txt"))["text"], "uncommitted base change\n")
        self.assertFalse((self.repo / "result.txt").exists())
        self.assertEqual(self.git(self.repo, "branch", "--show-current"), "main")
        self.api("GET", self.file_path("entries", run=first["id"]), status=404)
        self.api("GET", self.file_path("file", "notes.txt", workspace="isolated", run="f" * 24), status=404)

    def test_isolation_fails_closed_without_a_committed_git_root(self):
        self.launch("edit", status=409, workspace="not-git")
        self.launch("edit", status=400, workspace="isolated", title="two\nlines")
        job = self.launch("edit", workspace="unborn")
        failed = self.until(job["id"], lambda result: result["state"] == "failed")
        self.assertFalse(failed["filesReady"])
        self.assertIn("The preset did not run", failed["log"])
        self.api("GET", self.file_path("entries", workspace="unborn", run=job["id"]), status=409)

    def test_log_limit_and_process_group_stop(self):
        flooded = self.until(self.launch("flood")["id"], lambda job: job["state"] == "succeeded")
        self.assertTrue(flooded["truncated"])
        self.assertTrue(flooded["log"].endswith("\ufffdEND"))
        job_id = self.launch("child")["id"]
        child = self.until(job_id, lambda job: job["log"].endswith("\n"))
        pid = int(child["log"].strip())
        self.api("POST", f"/v1/jobs/{job_id}/stop")
        stopped = self.until(job_id, lambda job: job["state"] == "stopped")
        self.assertEqual(stopped["exitCode"], 137)
        deadline = time.monotonic() + 2
        while True:
            try:
                stat = Path(f"/proc/{pid}/stat").read_text(encoding="ascii")
                if stat.rsplit(") ", 1)[1].startswith(("Z ", "X ")):
                    break  # Reaped by the OS later; cannot execute anymore.
            except (FileNotFoundError, ProcessLookupError):
                break
            self.assertLess(time.monotonic(), deadline, "a child survived Stop")
            time.sleep(0.03)


    # ---- Agent threads ---------------------------------------------------------

    def post(self, path, body, status=200):
        return self.api("POST", path, status=status, headers={"Content-Type": "application/json"},
                        body=json.dumps(body))

    def thread_until(self, thread_id, predicate, timeout=10):
        deadline = time.monotonic() + timeout
        items, rev = {}, 0
        while True:
            page = self.api("GET", f"/v1/threads/{thread_id}?since={rev}&wait=300")
            if page["reset"]:
                items = {}
            for entry in page["items"]:
                items[entry["id"]] = entry
            rev = page["rev"]
            ordered = sorted(items.values(), key=lambda entry: int(entry["id"][1:]) if entry["id"][1:].isdigit() else -1)
            if predicate(page["thread"], ordered):
                return page["thread"], ordered
            if time.monotonic() >= deadline:
                self.fail("Thread did not reach expected state: " + json.dumps(page["thread"]) + json.dumps(ordered)[-1500:])

    def test_agent_loop_approvals_checkpoints_and_provider_switch(self):
        self.model.script += [
            anthropic_stream([
                {"type": "thinking", "thinking": "Plan: write a file.", "signature": "sig-abc"},
                {"type": "text", "text": "Adding the note."},
                {"type": "tool_use", "id": "toolu_1", "name": "write_file",
                 "input": {"path": "hello.txt", "content": "hi\n"}},
            ], "tool_use"),
            anthropic_stream([{"type": "tool_use", "id": "toolu_2", "name": "bash",
                               "input": {"command": "cat hello.txt; echo $TERM"}}], "tool_use"),
            anthropic_stream([{"type": "text", "text": "Done: hello.txt says hi."}], "end_turn"),
        ]
        thread = self.post("/v1/threads", {"workspace": "isolated", "prompt": "Add a hello note", "mode": "edits"}, 201)
        self.assertEqual(thread["provider"], "claude")
        self.assertTrue(thread["branch"].startswith("tsun/agent-"))
        thread_id = thread["id"]

        # Edits run on their own in "edits" mode; the shell command waits for approval.
        thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "waiting")
        tools = [entry for entry in items if entry["kind"] == "tool"]
        self.assertEqual([(t["name"], t["status"]) for t in tools], [("write_file", "done"), ("bash", "waiting")])
        self.assertEqual(thread["pendingCall"], tools[1]["id"])
        self.assertIn("cat hello.txt", tools[1]["detail"])
        self.assertEqual(next(e for e in items if e["kind"] == "thinking")["text"], "Plan: write a file.")
        worktree = self.state / "worktrees" / f"isolated-{thread_id}"
        self.assertEqual((worktree / "hello.txt").read_text(encoding="utf-8"), "hi\n")
        self.assertFalse((self.repo / "hello.txt").exists())

        self.post(f"/v1/threads/{thread_id}/approval", {"call": "wrong", "decision": "allow"}, 409)
        self.post(f"/v1/threads/{thread_id}/approval", {"call": thread["pendingCall"], "decision": "allow"})
        thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "idle")
        bash = [e for e in items if e["kind"] == "tool"][1]
        self.assertEqual((bash["status"], bash["exitCode"]), ("done", 0))
        self.assertIn("hi\ndumb", bash["output"])
        self.assertEqual(items[-2]["text"], "Done: hello.txt says hi.")
        self.assertEqual(items[-1]["kind"], "run")
        self.assertEqual((items[-1]["additions"], items[-1]["files"][0]["path"]), (1, "hello.txt"))
        self.assertEqual((thread["additions"], thread["filesChanged"]), (1, 1))

        # The wire format: tools, headers, and the reasoning block replayed unchanged.
        first, second, third = self.model.requests[:3]
        self.assertEqual(first["path"], "/v1/messages")
        self.assertEqual(first["headers"]["x-api-key"], "test-key")
        self.assertEqual({t["name"] for t in first["body"]["tools"]},
                         {"bash", "read_file", "write_file", "edit_file", "list_files", "search", "delegate_task",
                          "message_session", "web_fetch", "web_search"})
        self.assertNotIn("eager_input_streaming", first["body"]["tools"][0])  # not the first-party API
        replayed = second["body"]["messages"][1]["content"]
        self.assertEqual(replayed[0], {"type": "thinking", "thinking": "Plan: write a file.", "signature": "sig-abc"})
        self.assertEqual(second["body"]["messages"][2]["content"][0]["tool_use_id"], "toolu_1")
        self.assertEqual(third["body"]["messages"][:3], second["body"]["messages"])  # append-only history

        # A hidden checkpoint ref records the run without touching the branch.
        refs = self.git(self.repo, "for-each-ref", "--format=%(refname)", "refs/tsun/")
        self.assertEqual(refs, f"refs/tsun/checkpoints/{thread_id}/1")
        self.assertEqual(self.git(worktree, "status", "--porcelain"), "?? hello.txt")
        diff = self.api("GET", f"/v1/threads/{thread_id}/diff")
        self.assertIn("+hi", diff["diff"])
        self.assertEqual(diff["files"][0]["path"], "hello.txt")

        # Switch to the OpenAI-compatible provider mid-thread; the history carries over.
        self.model.script += [
            openai_stream(reasoning="Edit it.", calls=[("call_9", "edit_file",
                          {"path": str(worktree / "hello.txt"), "old_text": "hi", "new_text": "hello"})], finish="tool_calls"),
            openai_stream(content="Changed to hello."),
        ]
        self.post(f"/v1/threads/{thread_id}/messages", {"text": "Say hello instead", "provider": "local"}, 202)
        thread, items = self.thread_until(thread_id, lambda t, i: t["state"] == "idle" and t["runs"] == 2)
        self.assertEqual(thread["provider"], "local")
        self.assertEqual((worktree / "hello.txt").read_text(encoding="utf-8"), "hello\n")
        fourth, fifth = self.model.requests[3:5]
        self.assertEqual(fourth["path"], "/v1/chat/completions")
        self.assertNotIn("Authorization", fourth["headers"])
        messages = fourth["body"]["messages"]
        self.assertEqual(messages[0]["role"], "system")
        self.assertEqual(messages[2]["tool_calls"][0]["function"]["name"], "write_file")
        self.assertEqual((messages[3]["role"], messages[3]["tool_call_id"]), ("tool", "toolu_1"))
        self.assertEqual(messages[-1], {"role": "user", "content": "Say hello instead"})
        self.assertEqual(fifth["body"]["messages"][-1]["role"], "tool")
        self.assertEqual(fifth["body"]["messages"][-1]["tool_call_id"], "call_9")
        self.assertEqual(items[-1]["additions"], 1)
        self.assertEqual(self.git(self.repo, "for-each-ref", "--format=%(refname)", "refs/tsun/").splitlines()[-1],
                         f"refs/tsun/checkpoints/{thread_id}/2")

    def test_copied_files_are_not_counted_as_agent_edits(self):
        vendor = self.workspace.parent / "vendor"
        vendor.mkdir()
        body = "".join(f"int vendored_{index};\n" for index in range(40))
        (vendor / "lib.c").write_text(body, encoding="utf-8")
        self.model.script += [
            anthropic_stream([
                {"type": "tool_use", "id": "toolu_cp", "name": "bash",
                 "input": {"command": "cp '" + str(vendor / "lib.c") + "' lib.c"}},
                {"type": "tool_use", "id": "toolu_own", "name": "write_file",
                 "input": {"path": "own.txt", "content": "one\ntwo\n"}},
            ], "tool_use"),
            anthropic_stream([{"type": "text", "text": "Vendored lib.c and wrote own.txt."}], "end_turn"),
        ]
        thread_id = self.post("/v1/threads", {"workspace": "isolated", "prompt": "Vendor it", "mode": "full"}, 201)["id"]
        thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "idle")
        run = items[-1]
        self.assertEqual(run["kind"], "run")
        # The copied file is listed, but its 40 lines are not the agent's edits.
        self.assertEqual((run["additions"], run["deletions"], run["copiedFiles"], run["copiedLines"]), (2, 0, 1, 40))
        files = {entry["path"]: entry for entry in run["files"]}
        self.assertEqual((files["own.txt"]["additions"], files["own.txt"].get("copied", False)), (2, False))
        self.assertEqual((files["lib.c"]["additions"], files["lib.c"]["copied"]), (40, True))
        self.assertEqual((thread["additions"], thread["filesChanged"]), (2, 2))
        self.assertEqual((thread["copiedFiles"], thread["copiedLines"]), (1, 40))

        review = self.api("GET", f"/v1/threads/{thread_id}/diff")
        self.assertEqual((review["additions"], review["deletions"]), (2, 0))
        self.assertEqual((review["copiedFiles"], review["copiedLines"]), (1, 40))
        copied = next(entry for entry in review["files"] if entry["path"] == "lib.c")
        self.assertTrue(copied["copied"])
        self.assertIn("+int vendored_39;", review["diff"])  # still reviewable in full

        # A later run that edits the copied file reports only its own change.
        self.model.script += [
            anthropic_stream([{"type": "tool_use", "id": "toolu_edit", "name": "edit_file",
                               "input": {"path": "lib.c", "old_text": "int vendored_0;",
                                         "new_text": "int vendored_0 = 1;"}}], "tool_use"),
            anthropic_stream([{"type": "text", "text": "Set the first value."}], "end_turn"),
        ]
        self.post(f"/v1/threads/{thread_id}/messages", {"text": "Set the first value"}, 202)
        thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "idle" and t["runs"] == 2)
        self.assertEqual((items[-1]["additions"], items[-1]["deletions"], items[-1]["copiedFiles"]), (1, 1, 0))
        # The branch-wide diff still shows the whole copied file, so it stays marked copied there.
        self.assertEqual((thread["additions"], thread["copiedFiles"]), (2, 1))

    def test_read_file_returns_images_for_both_providers(self):
        pixel = base64.b64decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==")
        (self.workspace / "pixel.png").write_bytes(pixel)
        encoded = base64.b64encode(pixel).decode()
        self.model.script += [
            anthropic_stream([{"type": "tool_use", "id": "toolu_png", "name": "read_file",
                               "input": {"path": "pixel.png"}}], "tool_use"),
            anthropic_stream([{"type": "text", "text": "A one-pixel image."}], "end_turn"),
            openai_stream(calls=[("call_png", "read_file", {"path": "pixel.png"})], finish="tool_calls"),
            openai_stream(content="Still a one-pixel image."),
            openai_stream(calls=[("call_bin", "read_file", {"path": "sample.bin"})], finish="tool_calls"),
            openai_stream(content="That one is not text."),
        ]
        thread = self.post("/v1/threads", {"workspace": "demo", "prompt": "What is in pixel.png?"}, 201)
        thread_id = thread["id"]
        thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "idle")
        tool = next(entry for entry in items if entry["kind"] == "tool")
        self.assertEqual((tool["name"], tool["status"], tool["image"]), ("read_file", "done", "image/png"))
        self.assertEqual(tool["bytes"], len(pixel))

        # Anthropic: the tool_result carries the image block next to a text summary.
        result = self.model.requests[1]["body"]["messages"][2]["content"][0]
        self.assertEqual((result["type"], result["tool_use_id"]), ("tool_result", "toolu_png"))
        text, image = result["content"]
        self.assertEqual(image, {"type": "image", "source": {"type": "base64", "media_type": "image/png",
                                                              "data": encoded}})
        self.assertIn("image/png", text["text"])

        # OpenAI: tool messages are text-only, so the image follows as a user turn.
        self.post(f"/v1/threads/{thread_id}/messages", {"text": "Describe it again", "provider": "local"}, 202)
        thread, items = self.thread_until(thread_id, lambda t, i: t["state"] == "idle" and t["runs"] == 2)
        messages = self.model.requests[2]["body"]["messages"]
        self.assertEqual(messages[-1], {"role": "user", "content": "Describe it again"})
        follow_up = self.model.requests[3]["body"]["messages"]
        self.assertEqual(follow_up[-2]["role"], "tool")
        self.assertEqual(follow_up[-1]["role"], "user")
        self.assertEqual(follow_up[-1]["content"][1], {"type": "image_url", "image_url": {
            "url": "data:image/png;base64," + encoded}})

        # Other binaries stay text-only failures.
        self.post(f"/v1/threads/{thread_id}/messages", {"text": "Now the binary", "provider": "local"}, 202)
        thread, items = self.thread_until(thread_id, lambda t, i: t["state"] == "idle" and t["runs"] == 3)
        binary = [entry for entry in items if entry["kind"] == "tool"][-1]
        self.assertEqual((binary["status"], "image" in binary), ("error", False))
        self.assertIn("not UTF-8 text", binary["output"])

    def test_effort_command_overrides_reasoning_effort(self):
        self.model.script += [
            anthropic_stream([{"type": "text", "text": "First reply."}], "end_turn"),
            anthropic_stream([{"type": "text", "text": "Low reply."}], "end_turn"),
            openai_stream(content="Xhigh reply."),
        ]
        thread = self.post("/v1/threads", {"workspace": "demo", "prompt": "Hi"}, 201)
        thread_id = thread["id"]
        thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "idle")
        self.assertEqual(self.model.requests[0]["body"]["output_config"], {"effort": "high"})  # provider default

        # Unknown levels are rejected with an error notice; nothing is sent to the model.
        self.post(f"/v1/threads/{thread_id}/messages", {"text": "/effort turbo"}, 202)
        thread, items = self.thread_until(thread_id, lambda t, i: any(
            e["kind"] == "notice" and e.get("tone") == "error" and "turbo" in e["text"] for e in i))
        self.assertEqual(thread["runs"], 1)

        # A level applies to that session without starting a run.
        self.post(f"/v1/threads/{thread_id}/messages", {"text": "/effort low"}, 202)
        thread, items = self.thread_until(thread_id, lambda t, _: t["effort"] == "low")
        self.assertIn("Reasoning effort set to low", [e["text"] for e in items if e["kind"] == "notice"][-1])
        self.assertEqual(thread["runs"], 1)

        self.post(f"/v1/threads/{thread_id}/messages", {"text": "go"}, 202)
        thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "idle" and t["runs"] == 2)
        self.assertEqual(self.model.requests[1]["body"]["output_config"], {"effort": "low"})

        # The override survives a provider switch and maps to reasoning_effort.
        self.post(f"/v1/threads/{thread_id}/messages", {"text": "/effort xhigh", "provider": "local"}, 202)
        self.post(f"/v1/threads/{thread_id}/messages", {"text": "go again"}, 202)
        thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "idle" and t["runs"] == 3)
        self.assertEqual(self.model.requests[2]["body"]["reasoning_effort"], "xhigh")

        # /effort default clears the override; /efforts without a space stays a normal message.
        self.post(f"/v1/threads/{thread_id}/messages", {"text": "/effort default"}, 202)
        thread, items = self.thread_until(thread_id, lambda t, _: t["effort"] is None)
        self.assertIn("reset to the provider default", [e["text"] for e in items if e["kind"] == "notice"][-1])

    def test_compact_command_summarizes_history(self):
        # Each turn is ~5000 tokens by the chars/4 estimate, so the 20000-token
        # recent window covers the newest turns and leaves the oldest to summarize.
        filler = "x" * 20000
        self.model.script += [
            anthropic_stream([
                {"type": "text", "text": filler + " NEEDLE-ONE"},
                {"type": "tool_use", "id": "toolu_read", "name": "read_file",
                 "input": {"path": "hello & 日本語.txt"}},
            ], "tool_use"),
            anthropic_stream([{"type": "text", "text": filler + " NEEDLE-ONE-DONE"}], "end_turn"),
            anthropic_stream([{"type": "text", "text": filler + " NEEDLE-TWO"}], "end_turn"),
            anthropic_stream([{"type": "text", "text": filler + " NEEDLE-THREE"}], "end_turn"),
            anthropic_stream([{"type": "text", "text": filler + " NEEDLE-FOUR"}], "end_turn"),
            anthropic_stream([{"type": "text", "text": "## Goal\nKeep the notes tidy."}], "end_turn"),
            anthropic_stream([{"type": "text", "text": "Continuing after compaction."}], "end_turn"),
        ]
        thread_id = self.post("/v1/threads", {"workspace": "demo", "prompt": "Start"}, 201)["id"]
        route = f"/v1/threads/{thread_id}"
        self.thread_until(thread_id, lambda t, _: t["state"] == "idle")

        # A short conversation has nothing outside the recent window; no model call.
        self.post(route + "/messages", {"text": "/compact"}, 202)
        thread, items = self.thread_until(thread_id, lambda t, i: any(
            e["kind"] == "notice" and "Nothing to compact yet" in e["text"] for e in i))
        self.assertEqual(thread["runs"], 1)
        self.assertEqual(len(self.model.requests), 2)  # the read_file step and the reply

        for index in (1, 2, 3):
            prompt = f"Turn {index} NEEDLE-PROMPT-{index}"
            self.post(route + "/messages", {"text": filler + " " + prompt}, 202)
            thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "idle" and t["runs"] == index + 1)
        self.assertEqual(len(self.model.requests), 5)

        # A failed summary leaves the conversation alone: the provider error is
        # reported and no runs, items of history are lost.
        replies, self.model.script = self.model.script, []
        self.post(route + "/messages", {"text": "/compact"}, 202)
        thread, items = self.thread_until(thread_id, lambda t, i: any(
            e["kind"] == "notice" and e["text"].startswith("Compaction failed") for e in i))
        self.assertEqual(thread["runs"], 4)
        self.assertFalse(thread["compacting"])
        self.model.script = replies

        # /compact is a session command: it never reaches the model as a prompt, and
        # it does not count as a run or checkpoint the worktree.
        attempted = len(self.model.requests)  # the failed summary is the request before this one
        self.post(route + "/messages", {"text": "/compact focus on the notes"}, 202)
        thread, items = self.thread_until(thread_id, lambda t, i: any(
            e["kind"] == "notice" and e["text"].startswith("Compacted") for e in i))
        self.assertEqual(thread["runs"], 4)
        self.assertFalse(thread["compacting"])
        note = next(e["text"] for e in reversed(items) if e["kind"] == "notice")
        self.assertIn("into a summary and kept the", note)

        # The summary request is a standalone call: its own system prompt, no tools,
        # and only the turns that fall outside the kept tail, flattened as text.
        summary = self.model.requests[attempted]["body"]
        self.assertTrue(summary["system"][0]["text"].startswith("You are a context summarization assistant."))
        self.assertNotIn("Tsun's coding agent", summary["system"][0]["text"])
        self.assertEqual(summary["tools"], [])
        self.assertEqual(len(summary["messages"]), 1)
        asked = summary["messages"][0]["content"][0]["text"]
        self.assertIn("<conversation>\n[User]: ", asked)
        self.assertIn("[Assistant]: ", asked)
        # Tool calls and their output are described, with long results shortened.
        self.assertIn('[Assistant tool calls]: read_file(path="hello & 日本語.txt")', asked)
        self.assertIn("[Tool result]:      1\thello", asked)  # read_file numbers its lines
        self.assertIn("NEEDLE-PROMPT-1", asked)
        self.assertNotIn("NEEDLE-PROMPT-3", asked)
        self.assertTrue(asked.endswith("Additional focus: focus on the notes"))

        # The next reply replays the summary as the head of the conversation, and the
        # turns it replaced are gone.
        self.post(route + "/messages", {"text": "Carry on"}, 202)
        thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "idle" and t["runs"] == 5)
        messages = self.model.requests[attempted + 1]["body"]["messages"]
        self.assertEqual(messages[0]["role"], "user")
        self.assertTrue(messages[0]["content"][0]["text"].startswith(
            "The conversation history before this point was compacted into the following summary:"))
        self.assertIn("## Goal\nKeep the notes tidy.", messages[0]["content"][0]["text"])
        self.assertIn("<read-files>\nhello & 日本語.txt\n</read-files>", messages[0]["content"][0]["text"])
        replay = json.dumps(messages)
        self.assertNotIn("NEEDLE-PROMPT-1", replay)
        self.assertIn("NEEDLE-PROMPT-3", replay)

        # A command mid-run is refused, like /effort.
        self.model.script.append(self.model.hang)
        self.post(route + "/messages", {"text": "Wait"}, 202)
        deadline = time.monotonic() + 5
        while len(self.model.requests) <= attempted + 2:
            self.assertLess(time.monotonic(), deadline, "the model was never called")
            time.sleep(0.02)
        self.post(route + "/messages", {"text": "/compact", "steer": True}, 409)
        self.post(route + "/interrupt", {})
        self.thread_until(thread_id, lambda t, _: t["state"] == "idle")

    def test_steering_replaces_an_unapproved_tool_plan(self):
        self.model.script += [
            anthropic_stream([
                {"type": "thinking", "thinking": "Plan: write a file.", "signature": "sig-steer"},
                {"type": "tool_use", "id": "toolu_write", "name": "write_file",
                 "input": {"path": "steered.txt", "content": "do not write"}},
                {"type": "tool_use", "id": "toolu_list", "name": "list_files", "input": {}},
            ], "tool_use"),
            anthropic_stream([{"type": "text", "text": "I will explain instead."}], "end_turn"),
        ]
        thread_id = self.post("/v1/threads", {"workspace": "demo", "prompt": "Write a file", "mode": "ask"}, 201)["id"]
        route = f"/v1/threads/{thread_id}"
        waiting, _ = self.thread_until(thread_id, lambda t, _: t["state"] == "waiting")
        self.assertTrue(waiting["canSteer"])
        self.post(route + "/messages", {"text": "Plain reply"}, 409)
        self.post(route + "/messages", {"text": "/effort low", "steer": True}, 409)
        self.post(route + "/messages", {"text": "Switch", "steer": True, "provider": "local"}, 400)
        self.post(route + "/messages", {"text": "Allow", "steer": True, "mode": "full"}, 400)
        self.post(route + "/messages", {"text": "Explain instead", "steer": True}, 202)

        thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "idle")
        self.assertEqual((thread["runs"], thread["mode"], thread["provider"], thread["pendingCall"]), (1, "ask", "claude", None))
        self.post(route + "/approval", {"call": waiting["pendingCall"], "decision": "allow"}, 409)
        self.assertFalse((self.workspace / "steered.txt").exists())
        self.assertEqual([e["status"] for e in items if e["kind"] == "tool"], ["cancelled", "cancelled"])
        message = next(e for e in items if e.get("steering") == "sent")
        self.assertEqual((message["run"], message["text"]), (1, "Explain instead"))
        messages = self.model.requests[1]["body"]["messages"]
        self.assertEqual(messages[-2]["content"][0]["signature"], "sig-steer")
        first, second, text = messages[-1]["content"]
        self.assertEqual([(b["type"], b["tool_use_id"], b["is_error"]) for b in (first, second)],
                         [("tool_result", "toolu_write", True), ("tool_result", "toolu_list", True)])
        self.assertEqual(text, {"type": "text", "text": "Explain instead"})

    def test_steering_after_a_final_reply_starts_a_followup_in_order(self):
        started, release = threading.Event(), threading.Event()

        def final_reply(_body):
            started.set()
            release.wait(8)
            return openai_stream(content="The first reply.")

        self.addCleanup(release.set)
        self.model.script += [final_reply, openai_stream(content="The steered reply."), openai_stream(content="OK.")]
        thread_id = self.post("/v1/threads", {"workspace": "demo", "provider": "local", "prompt": "First"}, 201)["id"]
        route = f"/v1/threads/{thread_id}"
        self.assertTrue(started.wait(3))
        for text in ("Second", "Third"):
            self.post(route + "/messages", {"text": text, "steer": True}, 202)
        page = self.api("GET", route)
        queued = [e for e in page["items"] if e.get("steering") == "queued"]
        self.assertEqual([e["text"] for e in queued], ["Second", "Third"])
        release.set()

        thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "idle")
        self.assertEqual(thread["runs"], 2)
        self.assertFalse(thread["canSteer"])
        self.assertEqual([e["run"] for e in items if e["kind"] == "run"], [1, 2])
        sent = [e for e in items if e.get("steering") == "sent"]
        self.assertEqual([(e["id"], e["run"]) for e in sent], [(e["id"], 2) for e in queued])
        messages = self.model.requests[1]["body"]["messages"]
        self.assertEqual(messages[-3:], [
            {"role": "assistant", "content": "The first reply."},
            {"role": "user", "content": "Second"},
            {"role": "user", "content": "Third"},
        ])

        # A steer racing with an idle transition becomes an ordinary reply.
        self.post(route + "/messages", {"text": "Fourth", "steer": True}, 202)
        thread, _ = self.thread_until(thread_id, lambda t, _: t["state"] == "idle")
        self.assertEqual(thread["runs"], 3)
        self.assertEqual(self.model.requests[2]["body"]["messages"][-1], {"role": "user", "content": "Fourth"})

    def test_steering_leaves_the_running_tool_alone(self):
        self.model.script += [
            openai_stream(calls=[
                ("running", "bash", {"command": "until test -f release-tool; do sleep 0.03; done; echo finished",
                                      "timeout_seconds": 10}),
                ("queued", "write_file", {"path": "steered.txt", "content": "do not write"}),
            ], finish="tool_calls"),
            openai_stream(content="I will stop editing."),
        ]
        thread_id = self.post("/v1/threads", {"workspace": "demo", "provider": "local", "mode": "full",
                              "prompt": "Make a file after the command"}, 201)["id"]
        self.thread_until(thread_id, lambda _, i: any(e.get("call") == "running" and e["status"] == "running" for e in i))
        route = f"/v1/threads/{thread_id}"
        self.post(route + "/messages", {"text": "No more edits", "steer": True}, 202)
        self.assertEqual(len(self.model.requests), 1)
        (self.workspace / "release-tool").touch()

        thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "idle")
        self.assertEqual(thread["runs"], 1)
        self.assertEqual([e["status"] for e in items if e["kind"] == "tool"], ["done", "cancelled"])
        self.assertFalse((self.workspace / "steered.txt").exists())
        messages = self.model.requests[1]["body"]["messages"]
        self.assertEqual([m["role"] for m in messages[-4:]], ["assistant", "tool", "tool", "user"])
        self.assertEqual([m["tool_call_id"] for m in messages[-3:-1]], ["running", "queued"])
        self.assertIn("finished", messages[-3]["content"])
        self.assertIn("Skipped", messages[-2]["content"])
        self.assertEqual(messages[-1], {"role": "user", "content": "No more edits"})

    def test_background_bash_requires_approval_and_wakes_idle_session(self):
        self.model.script += [
            openai_stream(calls=[("build", "bash", {"background": True, "command":
                "touch build-started; until test -f release-build; do sleep 0.03; done; echo build-complete >&2; exit 7"})],
                finish="tool_calls"),
            openai_stream(content="I'll wait for the build result."),
            openai_stream(content="The build failed with exit code 7."),
        ]
        thread_id = self.post("/v1/threads", {"workspace": "demo", "provider": "local", "prompt": "Run the build"}, 201)["id"]
        route = f"/v1/threads/{thread_id}"
        thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "waiting")
        self.assertEqual(thread["backgroundCommands"], 0)
        self.assertFalse((self.workspace / "build-started").exists())
        card = next(e for e in items if e.get("call") == "build")
        self.assertEqual(card["title"], "Background bash")
        self.post(route + "/approval", {"call": thread["pendingCall"], "decision": "allow"})
        thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "idle")
        self.assertEqual((thread["runs"], thread["backgroundCommands"]), (1, 1))
        self.assertTrue(thread["canStop"])
        self.assertEqual(next(e for e in items if e["id"] == card["id"])["status"], "background")
        bash = next(t["function"] for t in self.model.requests[0]["body"]["tools"] if t["function"]["name"] == "bash")
        self.assertEqual(bash["parameters"]["properties"]["background"]["type"], "boolean")
        launch = self.model.requests[1]["body"]["messages"][-1]
        self.assertEqual((launch["role"], launch["tool_call_id"]), ("tool", "build"))
        self.assertIn(card["id"], launch["content"])
        self.assertNotIn("build-complete", launch["content"])

        (self.workspace / "release-build").touch()
        thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "idle" and t["runs"] == 2)
        self.assertEqual(thread["backgroundCommands"], 0)
        settled = next(e for e in items if e["id"] == card["id"])
        self.assertEqual((settled["status"], settled["exitCode"], settled["output"].strip()), ("error", 7, "build-complete"))
        self.assertEqual(sum(e["kind"] == "user" for e in items), 1)
        messages = self.model.requests[2]["body"]["messages"]
        self.assertEqual(sum(m.get("tool_call_id") == "build" for m in messages), 1)
        self.assertIn("Exit code 7", messages[-1]["content"])
        self.assertIn("build-complete", messages[-1]["content"])
        self.assertEqual(len(self.model.requests), 3)

    def test_background_bash_delivery_waits_until_tool_batch_is_complete(self):
        self.model.script += [
            anthropic_stream([
                {"type": "tool_use", "id": "background", "name": "bash", "input": {"background": True,
                    "command": "until test -f release-background; do sleep 0.03; done; printf '\\377result'"}},
                {"type": "tool_use", "id": "foreground", "name": "bash", "input": {"background": False,
                    "command": "until test -f release-foreground; do sleep 0.03; done; echo foreground-done"}},
            ], "tool_use"),
            anthropic_stream([{"type": "text", "text": "Both commands finished."}], "end_turn"),
        ]
        thread_id = self.post("/v1/threads", {"workspace": "demo", "mode": "full", "prompt": "Run two commands"}, 201)["id"]
        self.thread_until(thread_id, lambda _, i: any(e.get("call") == "foreground" and e["status"] == "running" for e in i))
        (self.workspace / "release-background").touch()
        self.thread_until(thread_id, lambda _, i: any(e.get("call") == "background" and e["status"] == "done" for e in i))
        self.assertEqual(len(self.model.requests), 1)
        (self.workspace / "release-foreground").touch()
        thread, _ = self.thread_until(thread_id, lambda t, _: t["state"] == "idle")
        self.assertEqual(thread["runs"], 1)
        blocks = self.model.requests[1]["body"]["messages"][-1]["content"]
        self.assertEqual([b["type"] for b in blocks], ["tool_result", "tool_result", "text"])
        self.assertEqual([b["tool_use_id"] for b in blocks[:2]], ["background", "foreground"])
        self.assertIn("foreground-done", blocks[1]["content"])
        self.assertIn("\ufffdresult", blocks[-1]["text"])
        self.restart()
        reloaded = self.api("GET", f"/v1/threads/{thread_id}")
        self.assertEqual(reloaded["thread"]["runs"], 1)
        self.assertIn("\ufffdresult", next(e for e in reloaded["items"] if e.get("call") == "background")["output"])

    def test_background_bash_times_out_and_limits_pending_commands(self):
        self.model.script += [
            openai_stream(calls=[(f"job-{i}", "bash", {"background": True, "command": "echo waiting; exec sleep 30",
                "timeout_seconds": 1}) for i in range(3)], finish="tool_calls"),
            openai_stream(content="Waiting for the results."),
            openai_stream(content="The jobs timed out."),
            openai_stream(content="Both timed out."),
        ]
        thread_id = self.post("/v1/threads", {"workspace": "demo", "provider": "local", "mode": "full", "prompt": "Run jobs"}, 201)["id"]
        thread, items = self.thread_until(thread_id, lambda t, i: t["state"] == "idle" and t["backgroundCommands"] == 0
            and t["runs"] >= 2 and sum(e["kind"] == "notice" and "timed out" in e["text"] for e in i) == 2)
        cards = [e for e in items if e["kind"] == "tool"]
        self.assertEqual([e["status"] for e in cards], ["error", "error", "error"])
        self.assertTrue(all(e["timedOut"] and "waiting" in e["output"] for e in cards[:2]))
        self.assertIn("already has two", cards[2]["output"])
        report = self.model.requests[-1]["body"]["messages"]
        self.assertEqual(sum("Tsun background command result" in (m.get("content") or "") for m in report), 2)
        self.assertIn("Timed out after 1 second.", json.dumps(report))

    def test_background_bash_rejects_non_boolean_and_does_not_run_if_declined(self):
        self.model.script += [
            openai_stream(calls=[("invalid", "bash", {"background": "true", "command": "touch invalid"}),
                                 ("null", "bash", {"background": None, "command": "touch null"}),
                                 ("denied", "bash", {"background": True, "command": "touch denied"})], finish="tool_calls"),
            openai_stream(content="Neither command ran."),
        ]
        thread_id = self.post("/v1/threads", {"workspace": "demo", "provider": "local", "prompt": "Run two jobs"}, 201)["id"]
        route = f"/v1/threads/{thread_id}"
        thread, _ = self.thread_until(thread_id, lambda t, _: t["state"] == "waiting")
        self.post(route + "/approval", {"call": thread["pendingCall"], "decision": "deny"})
        thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "idle")
        self.assertEqual(thread["backgroundCommands"], 0)
        calls = [e for e in items if e["kind"] == "tool"]
        self.assertEqual([e["status"] for e in calls], ["error", "error", "denied"])
        self.assertTrue(all("background must be a boolean" in e["output"] for e in calls[:2]))
        self.assertFalse(any((self.workspace / name).exists() for name in ("invalid", "null", "denied")))

    def test_background_bash_stays_in_worktree_and_checkpoints_on_completion(self):
        self.model.script += [
            openai_stream(calls=[("build", "bash", {"background": True, "command":
                "until test -f release-build; do sleep 0.03; done; echo generated > generated.txt"})], finish="tool_calls"),
            openai_stream(content="The build is running."),
            openai_stream(content="The build generated a file."),
        ]
        thread_id = self.post("/v1/threads", {"workspace": "isolated", "provider": "local", "mode": "full",
                               "prompt": "Generate a file"}, 201)["id"]
        thread, _ = self.thread_until(thread_id, lambda t, _: t["state"] == "idle")
        self.assertEqual(thread["backgroundCommands"], 1)
        root = self.state / "worktrees" / f"isolated-{thread_id}"
        (root / "release-build").touch()
        thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "idle" and t["runs"] == 2)
        self.assertFalse((self.repo / "generated.txt").exists())
        self.assertEqual((root / "generated.txt").read_text(), "generated\n")
        checkpoint = next(e["checkpoint"] for e in items if e["kind"] == "run" and e["run"] == 2)
        self.assertEqual(self.git(root, "show", f"{checkpoint}:generated.txt"), "generated")

    def test_stop_and_shutdown_cancel_background_bash_while_idle(self):
        for shutdown in (False, True):
            with self.subTest(shutdown=shutdown):
                pid_file = f"pid-{shutdown}"
                self.model.script += [
                    openai_stream(calls=[("build", "bash", {"background": True, "command":
                        f"echo $$ > {pid_file}; trap '' TERM; exec sleep 30"})], finish="tool_calls"),
                    openai_stream(content="Still running."),
                ]
                thread_id = self.post("/v1/threads", {"workspace": "demo", "provider": "local", "mode": "full", "prompt": "Run a job"}, 201)["id"]
                thread, _ = self.thread_until(thread_id, lambda t, _: t["state"] == "idle" and (self.workspace / pid_file).exists())
                self.assertEqual(thread["backgroundCommands"], 1)
                pid = int((self.workspace / pid_file).read_text())
                requests = len(self.model.requests)
                if shutdown:
                    self.restart()
                else:
                    self.post(f"/v1/threads/{thread_id}/interrupt", {})
                thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "idle" and t["backgroundCommands"] == 0)
                self.assertEqual(thread["runs"], 1)
                self.assertFalse(thread["canStop"])
                self.assertEqual(next(e for e in items if e.get("call") == "build")["status"], "cancelled")
                self.assertEqual(len(self.model.requests), requests)
                with self.assertRaises(ProcessLookupError):
                    os.kill(pid, 0)

    def test_stop_discards_unsent_steering(self):
        started = threading.Event()

        def hang(body):
            started.set()
            return self.model.hang(body)

        self.model.script += [hang, openai_stream(content="OK.")]
        thread_id = self.post("/v1/threads", {"workspace": "demo", "provider": "local", "prompt": "Wait"}, 201)["id"]
        route = f"/v1/threads/{thread_id}"
        self.assertTrue(started.wait(3))
        for n in range(8):
            self.post(route + "/messages", {"text": f"Discard me {n}", "steer": True}, 202)
        self.post(route + "/messages", {"text": "Too many", "steer": True}, 409)
        rev = self.api("GET", route)["rev"]
        stopped = self.post(route + "/interrupt", {})
        self.assertFalse(stopped["canSteer"])
        thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "idle")
        self.assertEqual(thread["runs"], 1)
        self.assertEqual(len(self.model.requests), 1)
        updated = self.api("GET", route + f"?since={rev}")["items"]
        self.assertEqual([e["steering"] for e in updated if e.get("steering")], ["cancelled"] * 8)
        self.assertTrue(items[-1]["stopped"])
        self.post(route + "/messages", {"text": "Try again"}, 202)
        self.thread_until(thread_id, lambda t, _: t["state"] == "idle")
        self.assertNotIn("Discard me", json.dumps(self.model.requests[-1]["body"]["messages"]))

    def test_agent_denials_confinement_and_interrupt(self):
        self.model.script += [
            anthropic_stream([
                {"type": "tool_use", "id": "toolu_a", "name": "write_file", "input": {"path": "../escape.txt", "content": "x"}},
                {"type": "tool_use", "id": "toolu_b", "name": "read_file", "input": {"path": "/etc/passwd"}},
                {"type": "tool_use", "id": "toolu_c", "name": "read_file", "input": {"path": ".git/config"}},
                {"type": "tool_use", "id": "toolu_d", "name": "bash", "input": {"command": "touch denied"}},
            ], "tool_use"),
            anthropic_stream([{"type": "tool_use", "id": "toolu_e", "name": "bash",
                               "input": {"command": "exec sleep 30"}}], "tool_use"),
        ]
        thread_id = self.post("/v1/threads", {"workspace": "isolated", "prompt": "Try things", "mode": "ask"}, 201)["id"]
        thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "waiting")
        tools = [e for e in items if e["kind"] == "tool"]
        self.assertEqual([t["status"] for t in tools], ["error", "error", "error", "waiting"])
        self.assertIn("inside the workspace", tools[1]["output"])
        self.assertIn(".git", tools[2]["output"])
        self.post(f"/v1/threads/{thread_id}/approval", {"call": thread["pendingCall"], "decision": "deny"})

        # Widening the mode answers the next request; then Stop kills the running command.
        thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "waiting")
        self.post(f"/v1/threads/{thread_id}/settings", {"mode": "full"})
        self.thread_until(thread_id, lambda t, i: any(e.get("call") == "toolu_e" and e["status"] == "running" for e in i))
        started = time.monotonic()
        self.post(f"/v1/threads/{thread_id}/interrupt", {})
        thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "idle")
        self.assertLess(time.monotonic() - started, 5)
        self.assertEqual(next(e for e in items if e.get("call") == "toolu_d")["status"], "denied")
        self.assertEqual(next(e for e in items if e.get("call") == "toolu_e")["status"], "error")
        self.assertTrue(items[-1]["stopped"])
        worktree = self.state / "worktrees" / f"isolated-{thread_id}"
        self.assertFalse((worktree / "denied").exists())
        self.assertFalse((self.state / "worktrees" / "escape.txt").exists())

        # The next run starts from a valid history: every tool call has a result.
        self.model.script.append(anthropic_stream([{"type": "text", "text": "OK."}], "end_turn"))
        self.post(f"/v1/threads/{thread_id}/messages", {"text": "Never mind"}, 202)
        self.thread_until(thread_id, lambda t, _: t["state"] == "idle" and t["runs"] == 2)
        history = self.model.requests[-1]["body"]["messages"]
        results = [b for m in history if m["role"] == "user" for b in m["content"] if b["type"] == "tool_result"]
        self.assertEqual([r["tool_use_id"] for r in results], ["toolu_a", "toolu_b", "toolu_c", "toolu_d", "toolu_e"])
        self.assertTrue(all(r.get("is_error") for r in results))
        self.assertEqual(history[-1]["content"][-1], {"type": "text", "text": "Never mind"})

    def write_skill(self, name, description, instructions, *, location=None, extra=""):
        directory = (location or self.workspace / ".agents" / "skills") / name
        directory.mkdir(parents=True, exist_ok=True)
        (directory / "SKILL.md").write_text(
            f"---\nname: {name}\ndescription: {description}\n{extra}---\n\n{instructions}\n", encoding="utf-8")
        return directory

    def test_skill_discovery_precedence_and_diagnostics(self):
        self.write_skill("review", ">-\n  Review code: check edge cases\n  and test coverage.", "PROJECT_BODY",
                         location=self.workspace / ".agents" / "skills" / "engineering",
                         extra='metadata:\n  owner: "Developer tools"\n')
        self.write_skill("review", "Daemon default", "DAEMON_BODY", location=self.state / "skills")
        self.write_skill("review", "Personal default", "USER_BODY", location=self.home / ".agents" / "skills")
        self.write_skill("personal", "Personal workflow", "USER_BODY", location=self.home / ".pi" / "agent" / "skills")
        self.write_skill("manual", '"Explicit only: 日本語"', "MANUAL_BODY", location=self.state / "skills",
                         extra="disable-model-invocation: true\n")
        self.write_skill("wrong-folder", "Invalid name", "BAD_BODY", extra="name: duplicate\n")
        self.write_skill("huge", "Big", "x" * (33 * 1024))
        self.write_skill("linked", "Links aren't skills", "LINKED", location=self.workspace.parent / "outside-skills")
        skills = self.workspace / ".agents" / "skills"
        (skills / "linked").symlink_to(self.workspace.parent / "outside-skills" / "linked", target_is_directory=True)
        (skills / "file-link").mkdir()
        (skills / "file-link" / "SKILL.md").symlink_to(skills / "engineering" / "review" / "SKILL.md")
        (skills / "hard-link").mkdir()
        os.link(self.workspace.parent / "outside-skills" / "linked" / "SKILL.md", skills / "hard-link" / "SKILL.md")
        (skills / "pipe").mkdir()
        os.mkfifo(skills / "pipe" / "SKILL.md")
        self.api("GET", "/v1/skills?workspace=demo", auth=False, status=401)
        catalog = self.api("GET", "/v1/skills?workspace=demo")
        self.assertEqual(catalog["skills"], [
            {"name": "manual", "description": "Explicit only: 日本語", "source": "daemon",
             "path": "STATE_DIR/skills/manual", "manual": True},
            {"name": "personal", "description": "Personal workflow", "source": "user",
             "path": "~/.pi/agent/skills/personal", "manual": False},
            {"name": "review", "description": "Review code: check edge cases and test coverage.", "source": "project",
             "path": ".agents/skills/engineering/review", "manual": False},
        ])
        self.assertIn("Shadowed by .agents/skills/engineering/review", str(catalog["warnings"]))
        for invalid in ("wrong-folder", "huge", "file-link", "hard-link", "pipe"):
            self.assertIn(invalid + "/SKILL.md", str(catalog["warnings"]))
        self.assertNotIn("_BODY", json.dumps(catalog))
        preview = self.api("GET", "/v1/skills?workspace=demo&name=review")
        self.assertIn("PROJECT_BODY", preview["text"])
        self.assertNotIn("DAEMON_BODY", preview["text"])
        self.assertEqual(preview["path"], ".agents/skills/engineering/review/SKILL.md")
        self.api("GET", "/v1/skills?workspace=demo&name=missing", status=404)
        self.api("GET", "/v1/skills?workspace=demo&name=../token", status=400)

    def test_model_activates_skill_reads_resources_and_still_needs_approval(self):
        directory = self.write_skill("release-review", "Review release notes.", "REVIEW_WORKFLOW", location=self.state / "skills",
                                     extra='allowed-tools: "Bash(*)"\n')
        (directory / "references").mkdir()
        (directory / "references" / "checks.md").write_text("REFERENCE_CHECKLIST", encoding="utf-8")
        (directory / "references" / "link").symlink_to(self.workspace / ".secret")
        self.write_skill("manual", "HIDDEN_DESCRIPTION", "MANUAL_BODY", extra="disable-model-invocation: true\n")
        self.write_skill("unrelated", "Different workflow.", "UNRELATED_BODY")
        self.model.script += [
            openai_stream(calls=[
                ("not-yet", "read_skill_resource", {"name": "release-review", "resource": "references/checks.md"}),
                ("manual", "load_skill", {"name": "manual"}),
                ("load", "load_skill", {"name": "release-review"}),
                ("repeat", "load_skill", {"name": "release-review"}),
            ], finish="tool_calls"),
            openai_stream(calls=[
                ("ref", "read_skill_resource", {"name": "release-review", "resource": "references/checks.md"}),
                ("escape", "read_skill_resource", {"name": "release-review", "resource": "../token"}),
                ("absolute", "read_skill_resource", {"name": "release-review", "resource": str(self.state / "token")}),
                ("link", "read_skill_resource", {"name": "release-review", "resource": "references/link"}),
                ("command", "bash", {"command": "touch skill-script-ran"}),
            ], finish="tool_calls"),
            openai_stream(content="Reviewed."),
        ]
        thread_id = self.post("/v1/threads", {"workspace": "demo", "prompt": "Review a release.", "provider": "local"}, 201)["id"]
        waiting, _ = self.thread_until(thread_id, lambda t, _: t["state"] == "waiting")
        self.assertFalse((self.workspace / "skill-script-ran").exists())
        self.post(f"/v1/threads/{thread_id}/approval", {"call": waiting["pendingCall"], "decision": "deny"})
        thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "idle")
        self.assertIsNone(thread["error"])
        calls = {e["call"]: e for e in items if e["kind"] == "tool"}
        self.assertEqual({k: v["status"] for k, v in calls.items()}, {
            "not-yet": "error", "manual": "error", "load": "done", "repeat": "done", "ref": "done",
            "escape": "error", "absolute": "error", "link": "error", "command": "denied",
        })
        first, second, third = [r["body"] for r in self.model.requests]
        self.assertIn("Review release notes.", first["messages"][0]["content"])
        self.assertIn("Different workflow.", first["messages"][0]["content"])
        for private in ("REVIEW_WORKFLOW", "REFERENCE_CHECKLIST", "UNRELATED_BODY", "HIDDEN_DESCRIPTION", "MANUAL_BODY"):
            self.assertNotIn(private, json.dumps(first))
        self.assertEqual(json.dumps(second).count("REVIEW_WORKFLOW"), 1)
        self.assertNotIn("REFERENCE_CHECKLIST", json.dumps(second))
        self.assertIn("REFERENCE_CHECKLIST", json.dumps(third))
        self.assertNotIn(self.token, json.dumps(third))
        self.assertFalse((self.workspace / "skill-script-ran").exists())

    def test_explicit_skill_uses_committed_worktree_then_refreshes_on_reply(self):
        directory = self.write_skill("manual", "Private routing metadata", "COMMITTED_WORKFLOW",
                                     location=self.repo / ".claude" / "skills", extra="disable-model-invocation: true\n")
        self.git(self.repo, "add", ".claude")
        self.git(self.repo, "-c", "user.name=Tsun Test", "-c", "user.email=test@example.invalid",
                 "-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "Add a skill")
        (directory / "SKILL.md").write_text((directory / "SKILL.md").read_text().replace("COMMITTED", "CHECKOUT"))
        self.model.script += [
            anthropic_stream([{"type": "text", "text": "Reviewed."}], "end_turn"),
            openai_stream(content="Reviewed again."),
        ]
        thread_id = self.post("/v1/threads", {"workspace": "isolated", "prompt": "/skill:manual Check notes.txt"}, 201)["id"]
        messages = f"/v1/threads/{thread_id}/messages"
        thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "idle")
        self.assertIsNone(thread["error"])
        self.assertEqual(next(e["text"] for e in items if e["kind"] == "user"), "/skill:manual Check notes.txt")
        self.assertTrue(any(e.get("text") == "Activated skill: manual" for e in items))
        first = self.model.requests[0]["body"]
        self.assertNotIn("Private routing metadata", json.dumps(first["system"]))
        self.assertNotIn("COMMITTED_WORKFLOW", json.dumps(first["system"]))
        self.assertIn("COMMITTED_WORKFLOW", json.dumps(first["messages"]))
        self.assertIn("User task:\\nCheck notes.txt", json.dumps(first["messages"]))
        self.assertNotIn("CHECKOUT_WORKFLOW", json.dumps(first))
        self.assertIn("read_skill_resource", {t["name"] for t in first["tools"]})
        self.assertNotIn("load_skill", {t["name"] for t in first["tools"]})
        route = f"/v1/skills?workspace=isolated&thread={thread_id}"
        self.api("GET", f"/v1/skills?workspace=demo&thread={thread_id}", status=404)
        self.assertTrue(self.api("GET", route)["skills"][0]["manual"])
        worktree, = (self.state / "worktrees").glob(f"*-{thread_id}")
        self.write_skill("manual", "Updated routing metadata", "WORKTREE_WORKFLOW", location=worktree / ".claude" / "skills",
                         extra="disable-model-invocation: true\n")
        self.assertEqual(self.api("GET", route)["skills"][0]["description"], "Updated routing metadata")
        self.post(messages, {"text": "/skill:../manual"}, 400)
        self.post(messages, {"text": "/skill:missing"}, 202)
        thread, _ = self.thread_until(thread_id, lambda t, _: t["state"] == "idle" and t["runs"] == 2)
        self.assertIn("Skill not found: missing", thread["error"])
        self.assertEqual(len(self.model.requests), 1)
        self.post(messages, {"text": "/skill:manual Do it again", "provider": "local"}, 202)
        thread, _ = self.thread_until(thread_id, lambda t, _: t["state"] == "idle" and t["runs"] == 3)
        self.assertIsNone(thread["error"])
        second = self.model.requests[1]["body"]
        self.assertIn("WORKTREE_WORKFLOW", second["messages"][-1]["content"])
        self.assertNotIn("/skill:missing", json.dumps(second))

    def test_agent_loads_worktree_guidance_once_per_turn(self):
        guidance = self.repo / "AGENTS.md"
        guidance.write_text("Use committed guidance.\n", encoding="utf-8")
        self.git(self.repo, "add", "AGENTS.md")
        self.git(self.repo, "-c", "user.name=Tsun Test", "-c", "user.email=test@example.invalid",
                 "-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "Add project guidance")
        guidance.write_text("Uncommitted checkout guidance.\n", encoding="utf-8")
        self.model.script += [
            anthropic_stream([
                {"type": "tool_use", "id": "toolu_edit", "name": "edit_file",
                 "input": {"path": "AGENTS.md", "old_text": "Use committed guidance.",
                           "new_text": "Use updated worktree guidance."}},
                {"type": "tool_use", "id": "toolu_child", "name": "delegate_task",
                 "input": {"task": "Review the project conventions."}},
            ], "tool_use"),
            anthropic_stream([{"type": "text", "text": "Reviewed."}], "end_turn"),
            anthropic_stream([{"type": "text", "text": "Updated."}], "end_turn"),
            openai_stream(content="Ready."),
        ]
        thread_id = self.post("/v1/threads", {"workspace": "isolated", "prompt": "Update the guidance and delegate a review."}, 201)["id"]
        thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "idle")
        self.assertIsNone(thread["error"])
        self.assertEqual([(e["name"], e["status"]) for e in items if e["kind"] == "tool"],
                         [("edit_file", "done"), ("delegate_task", "done")])
        parent, child, followup = [r["body"]["system"][0]["text"] for r in self.model.requests]
        for system in (parent, child, followup):
            self.assertEqual(system.count("Use committed guidance."), 1)
            self.assertNotIn("Uncommitted checkout guidance.", system)
            self.assertNotIn("Use updated worktree guidance.", system)
        self.assertEqual(parent, followup)
        self.assertIn("read-only subagent", child)
        self.assertEqual(self.model.requests[1]["body"]["messages"][0]["content"],
                         [{"type": "text", "text": "Review the project conventions."}])

        self.post(f"/v1/threads/{thread_id}/messages", {"text": "Continue.", "provider": "local"}, 202)
        thread, _ = self.thread_until(thread_id, lambda t, _: t["state"] == "idle" and t["runs"] == 2)
        self.assertIsNone(thread["error"])
        system_message = self.model.requests[-1]["body"]["messages"][0]
        self.assertEqual(system_message["role"], "system")
        self.assertIn("Use updated worktree guidance.", system_message["content"])
        self.assertNotIn("Use committed guidance.", system_message["content"])
        self.assertNotIn("Uncommitted checkout guidance.", system_message["content"])

    def test_agent_skips_unsupported_workspace_guidance(self):
        path = self.workspace / "AGENTS.md"
        outside = self.workspace.parent / "outside"
        (self.workspace.parent / "AGENTS.md").write_text("Parent directory guidance.\n", encoding="utf-8")
        cases = {
            "missing": lambda: None,
            "blank": lambda: path.write_bytes(b" \t\r\n"),
            "invalid UTF-8": lambda: path.write_bytes(b"Invalid guidance: \xff"),
            "oversized": lambda: path.write_bytes(b"x" * (16 * 1024 + 1)),
            "symlink": lambda: path.symlink_to(outside),
            "hard link": lambda: os.link(outside, path),
            "FIFO": lambda: os.mkfifo(path),
        }
        for index, (case, create) in enumerate(cases.items(), 1):
            with self.subTest(case=case):
                create()
                try:
                    self.model.script.append(anthropic_stream([{"type": "text", "text": "OK."}], "end_turn"))
                    thread_id = self.post("/v1/threads", {"workspace": "demo", "prompt": "Hello."}, 201)["id"]
                    thread, _ = self.thread_until(thread_id, lambda t, _: t["state"] == "idle")
                    self.assertIsNone(thread["error"])
                    self.assertEqual(len(self.model.requests), index)
                    system = self.model.requests[-1]["body"]["system"][0]["text"]
                    self.assertNotIn("<workspace_guidance>", system)
                    self.assertNotIn("Parent directory guidance.", system)
                finally:
                    path.unlink(missing_ok=True)

    def test_delegate_uses_fresh_read_only_context_and_returns_findings(self):
        task = "Inspect notes.txt and report the first line."
        self.model.script += [
            openai_stream(calls=[("delegate_1", "delegate_task", {"task": task})], finish="tool_calls"),
            openai_stream(calls=[("read_1", "read_file", {"path": "notes.txt"}),
                                 ("write_1", "write_file", {"path": "forbidden.txt", "content": "no"}),
                                 ("shell_1", "bash", {"command": "touch forbidden.txt"}),
                                 ("nested_1", "delegate_task", {"task": "Nest a child"}),
                                 ("escape_1", "read_file", {"path": "../outside"})], finish="tool_calls"),
            openai_stream(content="notes.txt:1 says committed base."),
            openai_stream(content="The note starts with 'committed base'."),
        ]
        thread_id = self.post("/v1/threads", {"workspace": "isolated", "provider": "local", "mode": "full",
                           "prompt": "Parent-only context. Ask a subagent about the note."}, 201)["id"]
        thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "idle")
        self.assertIsNone(thread["error"])
        parent, child, child_followup, parent_followup = [r["body"] for r in self.model.requests]
        self.assertEqual(parent["model"], child["model"])
        self.assertEqual({t["function"]["name"] for t in child["tools"]}, {"read_file", "list_files"})
        self.assertIn("read-only subagent", child["messages"][0]["content"])
        self.assertEqual(child["messages"][1:], [{"role": "user", "content": task}])
        child_results = {m["tool_call_id"]: m["content"] for m in child_followup["messages"] if m["role"] == "tool"}
        self.assertIn("committed base", child_results["read_1"])
        self.assertNotIn("uncommitted", child_results["read_1"])
        for call_id in ("write_1", "shell_1", "nested_1"):
            self.assertIn("only use read_file and list_files", child_results[call_id])
        self.assertIn("Paths cannot contain ..", child_results["escape_1"])
        tools = [entry for entry in items if entry["kind"] == "tool"]
        self.assertEqual([(e["name"], e["status"], e["title"]) for e in tools], [("delegate_task", "done", "Subagent")])
        self.assertIn("notes.txt:1", tools[0]["output"])
        self.assertEqual(parent_followup["messages"][-1]["tool_call_id"], "delegate_1")
        self.assertEqual(parent_followup["messages"][-1]["content"], tools[0]["output"])
        self.assertEqual((thread["inputTokens"], thread["outputTokens"]), (80, 20))
        worktree = self.state / "worktrees" / f"isolated-{thread_id}"
        self.assertFalse((worktree / "forbidden.txt").exists())

    def test_parallel_tools_publish_out_of_order_but_replay_in_order(self):
        release = threading.Event()
        self.addCleanup(release.set)

        def child(body):
            if "first area" in body["messages"][-1]["content"]:
                if not release.wait(10):
                    return None
                return openai_stream(content="Slow findings.")
            return openai_stream(content="Fast findings.")

        self.model.script += [
            openai_stream(calls=[
                ("slow", "delegate_task", {"task": "Inspect the first area"}),
                ("fast", "delegate_task", {"task": "Inspect the second area"}),
                ("root", "list_files", {}),
                ("sub", "list_files", {"path": "sub"}),
                ("next_batch", "read_file", {"path": "sub/inside.txt"}),
                ("write", "write_file", {"path": "after.txt", "content": "after the reads"}),
                ("read_back", "read_file", {"path": "after.txt"}),
            ], finish="tool_calls"),
            # Either subagent can reach the mock first; route by its brief.
            child, child,
            openai_stream(content="Finished."),
        ]
        thread_id = self.post("/v1/threads", {"workspace": "demo", "provider": "local", "mode": "ask",
                              "prompt": "Inspect both areas and write a note"}, 201)["id"]
        _, items = self.thread_until(thread_id, lambda _, items: sum(
            e.get("call") in ("fast", "root", "sub") and e["status"] == "done" for e in items) == 3, timeout=5)
        tools = [e for e in items if e["kind"] == "tool"]
        self.assertEqual([e["status"] for e in tools], ["running", "done", "done", "done", "queued", "queued", "queued"])
        self.assertIn("Fast findings.", tools[1]["output"])
        self.assertIn("sub/inside.txt", tools[2]["output"])
        self.assertIn("inside.txt", tools[3]["output"])
        self.assertEqual(len(self.model.requests), 3)  # parent cannot continue with a partial batch
        release.set()

        thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "waiting")
        tools = [e for e in items if e["kind"] == "tool"]
        self.assertEqual([e["status"] for e in tools], ["done"] * 5 + ["waiting", "queued"])
        self.assertEqual(thread["pendingCall"], tools[5]["id"])
        self.assertFalse((self.workspace / "after.txt").exists())
        self.post(f"/v1/threads/{thread_id}/approval", {"call": thread["pendingCall"], "decision": "allow"})
        thread, _ = self.thread_until(thread_id, lambda t, _: t["state"] == "idle")
        self.assertIsNone(thread["error"])
        results = [m for m in self.model.requests[-1]["body"]["messages"] if m["role"] == "tool"]
        self.assertEqual([m["tool_call_id"] for m in results], [e["call"] for e in tools])
        self.assertIn("Slow findings.", results[0]["content"])
        self.assertIn("after the reads", results[-1]["content"])
        self.assertEqual((thread["inputTokens"], thread["outputTokens"]), (80, 20))

    def test_stop_cancels_parallel_tools_and_keeps_a_complete_history(self):
        calls = [(call_id, "delegate_task", {"task": f"Read {call_id}"}) for call_id in ("one", "two")]
        calls.append(("queued", "write_file", {"path": "not-written.txt", "content": "no"}))
        self.model.script += [openai_stream(calls=calls, finish="tool_calls"), self.model.hang, self.model.hang]
        thread_id = self.post("/v1/threads", {"workspace": "demo", "provider": "local", "mode": "full",
                              "prompt": "Read both areas"}, 201)["id"]
        deadline = time.monotonic() + 5
        while len(self.model.requests) < 3:
            self.assertLess(time.monotonic(), deadline, "both subagents must start before either replies")
            time.sleep(0.02)
        route = f"/v1/threads/{thread_id}"
        self.post(route + "/interrupt", {})
        thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "idle", timeout=5)
        self.assertIsNone(thread["error"])
        self.assertEqual([e["status"] for e in items if e["kind"] == "tool"], ["cancelled"] * 3)
        self.assertFalse((self.workspace / "not-written.txt").exists())
        self.assertEqual(len(self.model.requests), 3)

        self.model.script.append(openai_stream(content="OK."))
        self.post(route + "/messages", {"text": "Never mind"}, 202)
        self.thread_until(thread_id, lambda t, _: t["state"] == "idle" and t["runs"] == 2)
        results = [m for m in self.model.requests[-1]["body"]["messages"] if m["role"] == "tool"]
        self.assertEqual([m["tool_call_id"] for m in results], ["one", "two", "queued"])
        self.assertTrue(all("stopped" in m["content"] or "Interrupted" in m["content"] for m in results))

    def test_steering_waits_for_parallel_tools_but_skips_ordered_edits(self):
        release = threading.Event()
        self.addCleanup(release.set)

        def child(_body):
            if not release.wait(10):
                return None
            return openai_stream(content="Finished reading.")

        calls = [(call_id, "delegate_task", {"task": f"Read {call_id}"}) for call_id in ("one", "two")]
        calls.append(("queued", "write_file", {"path": "not-written.txt", "content": "no"}))
        self.model.script += [openai_stream(calls=calls, finish="tool_calls"), child, child,
                              openai_stream(content="I will leave the files alone.")]
        thread_id = self.post("/v1/threads", {"workspace": "demo", "provider": "local", "mode": "full",
                              "prompt": "Read both areas and write a note"}, 201)["id"]
        self.thread_until(thread_id, lambda _, items: sum(
            e.get("call") in ("one", "two") and e["status"] == "running" for e in items) == 2)
        self.post(f"/v1/threads/{thread_id}/messages", {"text": "No more edits", "steer": True}, 202)
        release.set()
        thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "idle")
        self.assertIsNone(thread["error"])
        self.assertEqual([e["status"] for e in items if e["kind"] == "tool"], ["done", "done", "cancelled"])
        self.assertFalse((self.workspace / "not-written.txt").exists())
        messages = self.model.requests[-1]["body"]["messages"]
        self.assertEqual([m["tool_call_id"] for m in messages[-4:-1]], ["one", "two", "queued"])
        self.assertIn("steering", messages[-2]["content"])
        self.assertEqual(messages[-1], {"role": "user", "content": "No more edits"})

    def test_delegate_caps_children_per_message(self):
        calls = [{"type": "tool_use", "id": f"toolu_{i}", "name": "delegate_task", "input": {"task": f"Inspect part {i}"}}
                 for i in range(3)]
        self.model.script += [anthropic_stream(calls, "tool_use")]
        self.model.script += [anthropic_stream([{"type": "text", "text": "One finding."}], "end_turn")] * 3
        thread_id = self.post("/v1/threads", {"workspace": "demo", "prompt": "Ask three children", "mode": "ask"}, 201)["id"]
        _, items = self.thread_until(thread_id, lambda t, _: t["state"] == "idle")
        tools = [e for e in items if e["kind"] == "tool"]
        self.assertEqual([e["status"] for e in tools], ["done", "done", "error"])
        self.assertIn("already used its two subagents", tools[-1]["output"])
        self.assertEqual(len(self.model.requests), 4)
        result = self.model.requests[-1]["body"]["messages"][-1]["content"][-1]
        self.assertEqual(result["tool_use_id"], "toolu_2")
        self.assertTrue(result["is_error"])

    def test_stop_interrupts_a_silent_delegate(self):
        self.model.script += [
            anthropic_stream([{"type": "tool_use", "id": "toolu_child", "name": "delegate_task",
                               "input": {"task": "Inspect the note"}}], "tool_use"),
            self.model.hang,
        ]
        thread_id = self.post("/v1/threads", {"workspace": "demo", "prompt": "Delegate the inspection"}, 201)["id"]
        deadline = time.monotonic() + 5
        while len(self.model.requests) < 2:
            self.assertLess(time.monotonic(), deadline, "the child model was never called")
            time.sleep(0.02)
        started = time.monotonic()
        self.post(f"/v1/threads/{thread_id}/interrupt", {})
        thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "idle", timeout=5)
        self.assertLess(time.monotonic() - started, 2)
        self.assertIsNone(thread["error"])
        tool = next(e for e in items if e["kind"] == "tool")
        self.assertEqual(tool["status"], "cancelled")
        self.assertTrue(items[-1]["stopped"])
        self.assertEqual(len(self.model.requests), 2)
        self.model.script.append(anthropic_stream([{"type": "text", "text": "OK."}], "end_turn"))
        self.post(f"/v1/threads/{thread_id}/messages", {"text": "Never mind"}, 202)
        self.thread_until(thread_id, lambda t, _: t["state"] == "idle" and t["runs"] == 2)
        history = self.model.requests[-1]["body"]["messages"]
        result = next(b for m in history if m["role"] == "user" for b in m["content"] if b["type"] == "tool_result")
        self.assertEqual(result["tool_use_id"], "toolu_child")
        self.assertTrue(result["is_error"])

    def test_session_message_requires_approval_and_uses_the_targets_context(self):
        self.model.script.append(openai_stream(content="I am ready."))
        target_id = self.post("/v1/threads", {"workspace": "isolated", "provider": "local", "mode": "full",
                              "prompt": "Target-only context"}, 201)["id"]
        target, _ = self.thread_until(target_id, lambda t, _: t["state"] == "idle")
        self.assertIsNone(target["error"])
        self.model.script += [
            openai_stream(calls=[("peer", "message_session", {"session_id": target_id, "message": "What is the first line?"})],
                          finish="tool_calls"),
            openai_stream(calls=[("read", "read_file", {"path": "notes.txt"}),
                                 ("write", "write_file", {"path": "forbidden.txt", "content": "no"}),
                                 ("cascade", "message_session", {"session_id": "a" * 24, "message": "Ping"})], finish="tool_calls"),
            openai_stream(content="The first line is committed base."),
            openai_stream(content="I heard back from the other session."),
        ]
        # Auto-edit must still ask before a privacy-sensitive session message.
        source_id = self.post("/v1/threads", {"workspace": "demo", "provider": "local", "mode": "edits",
                              "prompt": "Source-only context"}, 201)["id"]
        source, _ = self.thread_until(source_id, lambda t, _: t["state"] == "waiting")
        # Changing modes must not implicitly approve privacy-sensitive messages.
        self.post(f"/v1/threads/{source_id}/settings", {"mode": "full"})
        self.assertEqual(self.api("GET", f"/v1/threads/{target_id}")["thread"]["runs"], 1)
        self.post(f"/v1/threads/{source_id}/approval", {"call": source["pendingCall"], "decision": "allow"})
        source, items = self.thread_until(source_id, lambda t, _: t["state"] == "idle")
        self.assertIsNone(source["error"])
        self.assertEqual(source["mode"], "full")
        tool = next(e for e in items if e.get("name") == "message_session")
        self.assertEqual(tool["status"], "done")
        self.assertIn("The first line is committed base.", tool["output"])
        target_page = self.api("GET", f"/v1/threads/{target_id}")
        self.assertEqual((target_page["thread"]["runs"], target_page["thread"]["mode"]), (2, "full"))
        self.assertTrue(any(f"Message from session {source_id}" in e.get("text", "") for e in target_page["items"]))

        target_request = self.model.requests[2]["body"]
        self.assertEqual({t["function"]["name"] for t in target_request["tools"]}, {"read_file", "list_files"})
        self.assertIn("Target-only context", json.dumps(target_request["messages"]))
        self.assertNotIn("Source-only context", json.dumps(target_request["messages"]))
        results = {m["tool_call_id"]: m["content"] for m in self.model.requests[3]["body"]["messages"] if m["role"] == "tool"}
        self.assertIn("committed base", results["read"])
        self.assertIn("can only read and list", results["write"])
        self.assertIn("can only read and list", results["cascade"])
        self.assertFalse((self.state / "worktrees" / f"isolated-{target_id}" / "forbidden.txt").exists())
        returned = self.model.requests[4]["body"]["messages"][-1]
        self.assertEqual(returned["content"], tool["output"])
        self.assertNotIn("Target-only context", json.dumps(self.model.requests[4]["body"]["messages"]))

        self.model.script.append(openai_stream(content="OK."))
        self.post(f"/v1/threads/{target_id}/messages", {"text": "Back to work"}, 202)
        self.thread_until(target_id, lambda t, _: t["state"] == "idle" and t["runs"] == 3)
        self.assertNotIn("What is the first line?", json.dumps(self.model.requests[-1]["body"]["messages"]))

    def test_session_message_in_full_mode_runs_without_approval(self):
        self.model.script.append(openai_stream(content="Ready to answer."))
        target_id = self.post("/v1/threads", {"workspace": "demo", "provider": "local",
                              "prompt": "Target"}, 201)["id"]
        self.thread_until(target_id, lambda t, _: t["state"] == "idle")
        self.model.script += [
            openai_stream(calls=[("peer", "message_session", {"session_id": target_id, "message": "What is the first line?"})],
                          finish="tool_calls"),
            openai_stream(content="The target says the first line is committed base."),
            openai_stream(content="The target says the first line is committed base."),
        ]
        source_id = self.post("/v1/threads", {"workspace": "demo", "provider": "local", "mode": "full",
                              "prompt": "Ask the target"}, 201)["id"]
        # Full access sends the session message without waiting for approval.
        source, items = self.thread_until(source_id, lambda t, _: t["state"] == "idle")
        self.assertIsNone(source["error"])
        self.assertIsNone(source["pendingCall"])
        tool = next(e for e in items if e.get("name") == "message_session")
        self.assertEqual(tool["status"], "done")
        self.assertIn("committed base", tool["output"])

    def test_stopping_a_session_message_cancels_the_targets_read_only_turn(self):
        self.model.script.append(openai_stream(content="Ready."))
        target_id = self.post("/v1/threads", {"workspace": "demo", "provider": "local", "prompt": "Target"}, 201)["id"]
        self.thread_until(target_id, lambda t, _: t["state"] == "idle")
        self.model.script += [
            openai_stream(calls=[("peer", "message_session", {"session_id": target_id, "message": "Check the notes."})],
                          finish="tool_calls"),
            self.model.hang,
        ]
        source_id = self.post("/v1/threads", {"workspace": "demo", "provider": "local", "mode": "full",
                              "prompt": "Ask the target"}, 201)["id"]
        deadline = time.monotonic() + 5
        while len(self.model.requests) < 3:
            self.assertLess(time.monotonic(), deadline, "the target model was never called")
            time.sleep(0.02)
        target_route = f"/v1/threads/{target_id}"
        self.assertFalse(self.api("GET", target_route)["thread"]["canSteer"])
        self.post(target_route + "/messages", {"text": "Redirect the peer", "steer": True}, 409)
        self.post(f"/v1/threads/{source_id}/interrupt", {})
        source, items = self.thread_until(source_id, lambda t, _: t["state"] == "idle", timeout=5)
        target, target_items = self.thread_until(target_id, lambda t, _: t["state"] == "idle", timeout=5)
        self.assertIsNone(source["error"])
        self.assertIsNone(target["error"])
        self.assertEqual(next(e for e in items if e.get("name") == "message_session")["status"], "cancelled")
        self.assertEqual(target_items[-1]["text"], "Stopped.")
        self.assertEqual(len(self.model.requests), 3)

    def test_web_tools_block_nonpublic_urls_and_report_when_search_is_unconfigured(self):
        calls = [("loopback", "web_fetch", {"url": "http://127.0.0.1/"}),
                 ("local_file", "web_fetch", {"url": "file:///etc/passwd"}),
                 ("search", "web_search", {"query": "example documentation"})]
        self.model.script += [openai_stream(calls=calls, finish="tool_calls"), openai_stream(content="Done.")]
        thread_id = self.post("/v1/threads", {"workspace": "demo", "provider": "local", "mode": "full",
                               "prompt": "Use the web tools"}, 201)["id"]
        _, items = self.thread_until(thread_id, lambda t, _: t["state"] == "idle")
        tools = [e for e in items if e["kind"] == "tool"]
        self.assertEqual([e["status"] for e in tools], ["error", "error", "error"])
        self.assertIn("Private and reserved", tools[0]["output"])
        self.assertIn("public HTTP or HTTPS", tools[1]["output"])
        self.assertIn("BRAVE_SEARCH_API_KEY, TAVILY_API_KEY or FIRECRAWL_API_KEY", tools[2]["output"])

    def test_web_search_rejects_a_malformed_key_and_skips_unset_providers(self):
        # The key has a space, so it is refused before any request leaves the daemon. Tavily is
        # set but empty, so only Brave is reported.
        self.shutdown()
        self.start_daemon({"BRAVE_SEARCH_API_KEY": "not a key", "TAVILY_API_KEY": " , "})
        calls = [("search", "web_search", {"query": "example documentation"})]
        self.model.script += [openai_stream(calls=calls, finish="tool_calls"), openai_stream(content="Done.")]
        thread_id = self.post("/v1/threads", {"workspace": "demo", "provider": "local", "mode": "full",
                               "prompt": "Use the web tools"}, 201)["id"]
        _, items = self.thread_until(thread_id, lambda t, _: t["state"] == "idle")
        output = next(e for e in items if e.get("name") == "web_search")["output"]
        self.assertIn("Brave: BRAVE_SEARCH_API_KEY contains invalid characters", output)
        self.assertNotIn("Tavily", output)
        self.assertNotIn("Firecrawl", output)

    def test_agent_interrupts_a_silent_model(self):
        self.model.script.append(self.model.hang)
        thread_id = self.post("/v1/threads", {"workspace": "demo", "prompt": "Think hard"}, 201)["id"]
        deadline = time.monotonic() + 5
        while not self.model.requests:
            self.assertLess(time.monotonic(), deadline, "the model was never called")
            time.sleep(0.02)
        started = time.monotonic()
        self.post(f"/v1/threads/{thread_id}/interrupt", {})
        thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "idle", timeout=5)
        self.assertLess(time.monotonic() - started, 2)
        self.assertIsNone(thread["error"])
        self.assertEqual(items[-2]["text"], "Stopped.")

    def test_agent_reports_provider_errors(self):
        thread_id = self.post("/v1/threads", {"workspace": "demo", "prompt": "Hello"}, 201)["id"]
        thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "idle")
        self.assertIn("script exhausted", thread["error"])
        self.assertEqual(items[-2]["tone"], "error")
        self.assertIsNone(thread["branch"])
        self.post("/v1/threads", {"workspace": "demo", "prompt": "x", "provider": "nope"}, 400)
        self.post("/v1/threads", {"workspace": "demo", "prompt": "   "}, 400)
        self.api("GET", "/v1/threads/" + "0" * 24, status=404)

    def test_delete_session_permanently_without_removing_worktree(self):
        self.model.script += [anthropic_stream([{"type": "text", "text": "Done."}], "end_turn")] * 2
        keep = self.post("/v1/threads", {"workspace": "demo", "prompt": "Keep me"}, 201)["id"]
        self.thread_until(keep, lambda t, _: t["state"] == "idle")
        thread = self.post("/v1/threads", {"workspace": "isolated", "prompt": "Delete me"}, 201)
        thread_id = thread["id"]
        route = f"/v1/threads/{thread_id}"
        thread, _ = self.thread_until(thread_id, lambda t, _: t["state"] == "idle")
        self.assertTrue(thread["canDelete"])
        worktree = self.state / "worktrees" / f"isolated-{thread_id}"
        (worktree / "draft.txt").write_text("uncommitted work", encoding="utf-8")
        branch_before = self.git(self.repo, "rev-parse", thread["branch"])

        self.request("DELETE", route, auth=False, status=401)
        self.assertEqual(self.request("DELETE", route, status=204)[0], b"")
        self.assertEqual([t["id"] for t in self.api("GET", "/v1/threads")["threads"]], [keep])
        self.api("GET", route, status=404)
        self.api("DELETE", route, status=404)
        self.api("GET", f"/v1/entries?workspace=isolated&thread={thread_id}", status=404)
        self.restart()
        self.api("GET", route, status=404)
        self.assertFalse((self.state / "threads" / f"{thread_id}.json").exists())
        self.assertEqual([t["id"] for t in self.api("GET", "/v1/threads")["threads"]], [keep])
        self.assertEqual((worktree / "draft.txt").read_text(encoding="utf-8"), "uncommitted work")
        self.assertEqual(self.git(self.repo, "rev-parse", thread["branch"]), branch_before)

    def test_delete_session_requires_work_to_stop(self):
        self.model.script += [
            openai_stream(calls=[("build", "bash", {"command": "exec sleep 60", "background": True})],
                          finish="tool_calls"),
            openai_stream(content="Build started."),
        ]
        thread_id = self.post("/v1/threads", {"workspace": "demo", "provider": "local", "prompt": "Build"}, 201)["id"]
        route = f"/v1/threads/{thread_id}"
        thread, _ = self.thread_until(thread_id, lambda t, _: t["state"] == "waiting")
        self.assertFalse(thread["canDelete"])
        self.api("DELETE", route, status=409)
        self.post(route + "/approval", {"call": thread["pendingCall"], "decision": "allow"})
        thread, _ = self.thread_until(thread_id, lambda t, _: t["state"] == "idle")
        self.assertEqual(thread["backgroundCommands"], 1)
        self.assertFalse(thread["canDelete"])
        self.api("DELETE", route, status=409)
        self.post(route + "/interrupt", {})
        self.thread_until(thread_id, lambda t, _: t["canDelete"])
        self.request("DELETE", route, status=204)

    def test_threads_and_transcripts_survive_a_daemon_restart(self):
        self.model.script += [anthropic_stream([{"type": "text", "text": "Remember this note."}], "end_turn")]
        thread = self.post("/v1/threads", {"workspace": "isolated", "prompt": "Keep this thread"}, 201)
        thread_id = thread["id"]
        thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "idle")
        self.assertEqual(items[-2]["text"], "Remember this note.")
        worktree = self.state / "worktrees" / f"isolated-{thread_id}"
        head = self.git(self.repo, "rev-parse", thread["branch"])

        # Threads live in memory, so the transcript has to reach the state directory before a restart.
        stored = self.state / "threads" / (thread_id + ".json")
        self.assertTrue(stored.is_file())
        self.restart()

        listed = next(t for t in self.api("GET", "/v1/threads")["threads"] if t["id"] == thread_id)
        self.assertEqual((listed["title"], listed["state"], listed["runs"]), ("Keep this thread", "idle", 1))
        self.assertEqual((listed["branch"], listed["provider"], listed["mode"]),
                         (thread["branch"], "claude", "edits"))
        self.assertEqual((listed["filesReady"], listed["updatedAt"]), (True, thread["updatedAt"]))
        _, restored = self.thread_until(thread_id, lambda t, _: t["runs"] == 1)
        self.assertEqual([e["id"] for e in restored], [e["id"] for e in items])
        self.assertEqual(restored[-1]["kind"], "run")

        # The restored history is replayed, so the model still sees its earlier reply.
        self.model.script += [anthropic_stream([{"type": "text", "text": "Still here."}], "end_turn")]
        self.post(f"/v1/threads/{thread_id}/messages", {"text": "What did you just say?"}, 202)
        thread, items = self.thread_until(thread_id, lambda t, _: t["state"] == "idle" and t["runs"] == 2)
        replay = self.model.requests[-1]["body"]["messages"]
        self.assertEqual(replay[1]["content"][0]["text"], "Remember this note.")
        self.assertEqual(replay[-1], {"role": "user", "content": [{"type": "text", "text": "What did you just say?"}]})
        self.assertEqual((self.git(self.repo, "rev-parse", thread["branch"]), worktree.is_dir()), (head, True))


if __name__ == "__main__":
    unittest.main(verbosity=2)
