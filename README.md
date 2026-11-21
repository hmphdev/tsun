# Tsun

A native Android workbench for your existing Debian chroot. The Kotlin/Compose app browses, edits, previews, and exports workspace files. The C++20 daemon runs up to two configured commands at once and keeps their output when the app disconnects. There is no Pi or Node dependency. V0 runs presets, not a model or chat agent.

## Run the daemon in Debian

You need Linux with glibc 2.34+, a C++20 compiler, CMake 3.22+, OpenSSL 3, and Python 3. On Debian, install any missing packages with `apt install build-essential cmake libssl-dev openssl python3`. CMake fetches two pinned header dependencies on the first build.

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

In the app, start **Demo report** and **Demo checks**. Use the run switcher above the bottom controls, or open the drawer. Under **Files**, edit `notes.txt`, render `artifacts/report.html`, or zoom and export `artifacts/field.png` via Android's save picker. The demo lives in a copy outside the source checkout.

## Use your own workspace

Edit `config.json` inside your daemon state directory, then restart the daemon. Use [the demo config](examples/config.json) as a template. Workspace roots are absolute Debian project paths, separate from the state directory. A preset is a fixed `argv` array with an absolute executable as its first element. Use `/usr/bin/git`, `/usr/bin/curl`, or your Debian build tools as appropriate. Presets use the workspace as their working directory and inherit the daemon's environment, including its `PATH`. Keep installs and root maintenance manual.

- The daemon binds HTTPS to `127.0.0.1` only. The app never needs root or direct access to `/root`. Don't expose the port over a tunnel or use this on a shared device you don't trust.
- **Presets are not sandboxed.** They run with the daemon's user privileges, even if they invoke files editable through the app. Use an unprivileged Debian account where the repo permits it, and only configure repos and presets you trust. Stop targets the job's process group, not programs that detach from it. Presets should not start daemons.
- Text edits use revision checks and atomic replacement. V0 doesn't create or delete files, show dotfiles, or follow symlinks. The API caps text at 1 MiB and downloads at 64 MiB; the phone editor caps editing at 128 Ki characters. HTML preview is static and offline: use inline CSS and embedded data assets. Unsaved drafts are not durable.
- Runs and the last 64 KiB of each run's output are kept in memory until the daemon exits (up to 32 runs). There are no automatic task worktrees; parallel presets that modify the same files can conflict.
