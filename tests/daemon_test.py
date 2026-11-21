from contextlib import closing
import hashlib
import http.client
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
        self.config = config = self.state / "config.json"
        config.write_text(json.dumps({
            "port": 0,
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
        self.process = subprocess.Popen(
            [str(DAEMON), str(self.state)], stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, text=True, encoding="utf-8",
        )
        self.addCleanup(self.shutdown)
        with selectors.DefaultSelector() as selector:
            selector.register(self.process.stdout, selectors.EVENT_READ)
            self.assertTrue(selector.select(timeout=5), "daemon did not announce a listening port")
        line = self.process.stdout.readline().strip()
        self.assertTrue(line.startswith("Tsun listening on https://127.0.0.1:"), line)
        self.port = int(line.rsplit(":", 1)[1])

    @staticmethod
    def git(directory, *args):
        return subprocess.run(["/usr/bin/git", "-C", str(directory), "-c", "core.hooksPath=/dev/null", *args],
                              check=True, capture_output=True, text=True, timeout=5).stdout.strip()

    def shutdown(self):
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
        self.assertEqual(catalog["protocol"], 2)
        self.assertEqual(catalog["workspaces"][0]["id"], "demo")
        self.assertFalse(catalog["workspaces"][0]["worktrees"])
        self.assertTrue(catalog["workspaces"][1]["worktrees"])
        self.assertNotIn(b"argv", data)
        self.assertNotIn(os.fsencode(self.workspace), data)
        self.api("GET", "/v1/missing", status=404)

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


if __name__ == "__main__":
    unittest.main(verbosity=2)
