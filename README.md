# Tsun

A native Android workbench for your existing Debian chroot. The Kotlin/Compose app groups tasks by project and lets you browse, edit, preview, and export files. The C++20 daemon runs up to two configured tasks at once, each in its own Git worktree by default. There is no Pi or Node dependency. V0 runs presets, not a model or chat agent.

## Run the daemon in Debian

You need Linux with glibc 2.34+, a C++20 compiler, CMake 3.22+, OpenSSL 3, Git, and Python 3. On Debian, install any missing packages with `apt install build-essential cmake libssl-dev openssl git python3`. CMake fetches two pinned header dependencies on the first build. Use the daemon and APK from the same version.

```sh
cd /root/mobile-harness
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --parallel 2
ctest --test-dir build --output-on-failure

bash scripts/init-demo.sh
state="${XDG_STATE_HOME:-$HOME/.local/state}/tsun-demo/state"
./build/mobile-harness "$state"
```

Start it in your normal Debian shell, or keep it in a named shell session while trying the app. A plain chroot shares the phone's loopback. The app and the daemon must share that network namespace.

## Install and pair

Push this project to a GitHub repo with Actions enabled. The **Tsun CI** workflow builds and tests the daemon and builds/lints a debug APK without using the phone's Android toolchain. In the Actions run, download **Tsun-debug**, unzip it, and sideload `app-debug.apk` (Android 8+). These are debug builds; a later CI build may use a different debug signing key, in which case Android will require uninstalling the old build before installing the new one.

From another shell in Debian, print the private code and paste it into the app:

```sh
cd /root/mobile-harness
./build/mobile-harness --pairing-code "${XDG_STATE_HOME:-$HOME/.local/state}/tsun-demo/state"
```

Don't post the code or commit the state directory. Pairing pins the daemon certificate and authenticates with a random key. If you rotate the certificate or key, forget the old pairing and enter the new code.

In the app, choose **Demo report** and tap **Start**. Tap **New task**, choose **Demo checks**, then start it too. Switch between them above the bottom controls or under **Field Notes** in the drawer. **Task files** opens the selected task's worktree: try editing `notes.txt`, opening `artifacts/report.html`, or exporting `artifacts/field.png`. The file icon next to a project in the drawer opens the original checkout instead. The demo is a separate Git repo outside the source checkout.

## Use your own workspace

Edit `config.json` inside your daemon state directory, then restart the daemon. Use [the demo config](examples/config.json) as a template. Set each workspace root to the absolute path of a Git checkout with at least one commit, separate from the daemon state. Each task creates a local `tsun/task-…` branch at the project's committed `HEAD` and checks it out under `STATE_DIR/worktrees/`. Uncommitted and untracked files aren't copied. For a non-Git workspace you can explicitly set `"worktrees": false`; its presets will share the original directory.

A preset is a fixed `argv` array with an absolute executable as its first element. Use `/usr/bin/git`, `/usr/bin/curl`, or your Debian build tools as appropriate. Presets start in the selected worktree and inherit the daemon's environment, including its `PATH`. Use project-relative paths to keep their output in the worktree. Keep installs and root maintenance manual.

- The daemon binds HTTPS to `127.0.0.1` only. The app never needs root or direct access to `/root`. Don't expose the port over a tunnel or use this on a shared device you don't trust.
- **Presets are not sandboxed.** They run with the daemon's user privileges, even if they invoke files editable through the app. Use an unprivileged Debian account where the repo permits it, and only configure repos and presets you trust. Stop targets the job's process group, not programs that detach from it. Presets should not start daemons.
- Text edits use revision checks and atomic replacement. V0 doesn't create or delete files, show dotfiles, or follow symlinks. The API caps text at 1 MiB and downloads at 64 MiB; the phone editor caps editing at 128 Ki characters. HTML preview is static and offline: use inline CSS and embedded data assets. Unsaved drafts are not durable.
- Run history and the last 64 KiB of each run's output are kept in memory until the daemon exits (up to 32 runs). Worktrees and branches stay on disk after Stop or restart, but old tasks are not rediscovered by the app. Use `git worktree list` from the original checkout to find them. Review and commit any changes you want to keep before removing a finished worktree manually. Tsun never merges, pushes, or deletes task branches automatically.
