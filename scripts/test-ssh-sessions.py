#!/usr/bin/env python3
"""Exercise the real helper with an isolated tmux server and no network access."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest

HELPER = Path(__file__).resolve().parents[1] / "app/src/main/assets/widgets/examples/oma-ssh-sessions"
BASH = shutil.which("bash")
TMUX = shutil.which("tmux")
REMOTE_PATH = 'export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"; '

class SessionsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="oma-session-test-")
        directory = Path(self.temp.name)
        bindir = directory / "bin"
        bindir.mkdir()
        ssh = bindir / "ssh"
        ssh.write_text("""#!/usr/bin/env python3
import json, os, subprocess, sys
if os.environ.get('CAPTURE_SSH'):
    print(json.dumps(sys.argv[1:])); sys.exit(0)
if os.environ.get('FAIL_SSH'):
    sys.exit(255)
remote = sys.argv[-1]
assert remote.startswith('export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"; '), sys.argv
sys.exit(subprocess.call(['/bin/bash', '-c', remote]))
""")
        ssh.chmod(0o700)
        self.env = {**os.environ, "PATH": f"{bindir}:{os.environ['PATH']}", "TMUX_TMPDIR": str(directory), "HOME": str(directory / "home"), "TMPDIR": str(directory)}
        self.env.pop("TMUX", None)
    def tearDown(self):
        subprocess.run([TMUX, "kill-server"], env=self.env, capture_output=True)
        self.temp.cleanup()
    def helper(self, action, host="test-host", user="test-user", *args, capture=False):
        env = {**self.env, **({"CAPTURE_SSH": "1"} if capture else {})}
        return subprocess.run([BASH, str(HELPER), action, host, user, *args], env=env, capture_output=True, text=True, timeout=15)
    def listing(self):
        result = self.helper("list")
        self.assertEqual(0, result.returncode, result.stderr)
        return json.loads(result.stdout)
    def test_no_server_is_an_empty_list_not_a_saved_stale_session(self):
        self.assertEqual({"ok": True, "sessions": [], "host": "test-host", "user": "test-user"}, self.listing())
    def test_real_running_session_has_id_program_folder_and_recent_text(self):
        subprocess.run([TMUX, "-f", "/dev/null", "new-session", "-d", "-s", "workspace", "/bin/bash --noprofile --norc"], env=self.env, check=True)
        subprocess.run([TMUX, "send-keys", "-t", "workspace", "printf 'widget-preview-marker\\n'", "Enter"], env=self.env, check=True)
        for _ in range(20):
            result = self.listing()
            if result["sessions"] and "widget-preview-marker" in result["sessions"][0]["preview"]:
                break
            time.sleep(0.1)
        session = result["sessions"][0]
        self.assertTrue(session["id"].startswith("$"))
        self.assertEqual("workspace", session["name"])
        self.assertEqual("bash", session["command"])
        self.assertIn("widget-preview-marker", session["preview"])
        self.assertLessEqual(len(session["preview"]), 203)
        subprocess.run([TMUX, "kill-session", "-t", "workspace"], env=self.env, check=True)
        self.assertEqual([], self.listing()["sessions"])
    def test_attach_targets_the_exact_id_and_never_creates_a_replacement(self):
        result = self.helper("attach", "test-host", "test-user", "$17", capture=True)
        self.assertEqual(0, result.returncode, result.stderr)
        args = json.loads(result.stdout)
        self.assertIn("-tt", args)
        self.assertEqual(["--", "test-host", REMOTE_PATH + "exec tmux -u attach-session -t '$17'"], args[-3:])
        self.assertNotIn("new-session", args[-1])
    def test_new_is_immediate_and_host_user_are_not_options_or_shell_code(self):
        host = "-oProxyCommand=echo bad; #"
        user = "user'; echo bad; #"
        result = self.helper("new", host, user, capture=True)
        self.assertEqual(0, result.returncode, result.stderr)
        args = json.loads(result.stdout)
        self.assertEqual(user, args[args.index("-l") + 1])
        self.assertEqual(["--", host, REMOTE_PATH + "exec tmux -u new-session"], args[-3:])
    def test_invalid_attach_id_is_rejected_before_ssh(self):
        for action in ("attach", "end"):
            result = self.helper(action, "test-host", "test-user", "$1'; echo bad", capture=True)
            self.assertEqual(2, result.returncode)
            self.assertEqual("", result.stdout)
            self.assertIn("Invalid session ID", result.stderr)
    def test_end_kills_only_the_selected_session_and_refresh_removes_it(self):
        for name in ("first", "second"):
            subprocess.run([TMUX, "-f", "/dev/null", "new-session", "-d", "-s", name, "/bin/bash --noprofile --norc"], env=self.env, check=True)
        first = next(s for s in self.listing()["sessions"] if s["name"] == "first")
        result = self.helper("end", "test-host", "test-user", first["id"])
        self.assertEqual({"ok": True}, json.loads(result.stdout))
        self.assertEqual(["second"], [s["name"] for s in self.listing()["sessions"]])
    def test_end_failure_returns_feedback_instead_of_claiming_success(self):
        self.env["FAIL_SSH"] = "1"
        result = self.helper("end", "test-host", "test-user", "$17")
        self.assertFalse(json.loads(result.stdout)["ok"])
        self.assertIn("Could not end", json.loads(result.stdout)["error"])
    def test_plain_ssh_has_no_tmux_or_python_requirement(self):
        result = self.helper("shell", "other-node", "test-user", capture=True)
        args = json.loads(result.stdout)
        self.assertEqual(["--", "other-node"], args[-2:])
        self.assertEqual("test-user", args[args.index("-l") + 1])
    def test_list_echoes_quoted_target_to_prevent_cross_machine_stale_actions(self):
        host = "node'; printf INJECTED; #"
        user = "user'; printf INJECTED; #"
        result = self.helper("list", host, user)
        self.assertEqual(0, result.returncode, result.stderr)
        value = json.loads(result.stdout)
        self.assertEqual(host, value["host"])
        self.assertEqual(user, value["user"])
        self.assertEqual([], value["sessions"])

if __name__ == "__main__":
    unittest.main()
