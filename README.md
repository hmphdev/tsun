# Tsun

A native Android workbench for your existing Debian chroot. Start a coding-agent session from your phone: the C++20 daemon runs the agent loop on Debian, in its own Git worktree, against the model provider you choose (the Anthropic API or any OpenAI-compatible endpoint). The Kotlin/Compose app shows the conversation, asks you to approve commands and edits, and lets you review the diff and browse, edit, preview, and export files. Fixed preset commands still work alongside agent sessions. There is no Pi or Node dependency.

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

To try the agent, save an Anthropic API key as `STATE_DIR/anthropic-key` (mode 0600) before starting the daemon, then type a task in the app and send it. Without a key, the app explains what is missing and the presets still work.

To try presets, open **Presets** in the composer and choose **Demo report**. Start **Demo checks** from a new session too. Switch between sessions under **Field Notes** in the drawer. **Files** opens the selected session's worktree: try editing `notes.txt`, opening `artifacts/report.html`, or exporting `artifacts/field.png`. The file icon next to a project in the drawer opens the original checkout instead. The demo is a separate Git repo outside the source checkout.

## Agent sessions

A session is a conversation with a coding agent about one project. The daemon runs the loop itself (stream the model's reply, run the tools it asks for, send back the results, repeat until it answers). The design follows [T3 Code](https://github.com/pingdotgg/t3code): a thread holds runs (one per message you send), a run holds items (text, reasoning, tool calls, approvals, notices), and the app catches up with a revision cursor instead of re-reading the conversation.

- **Tools.** Every provider gets the same JSON-schema tools: `bash`, `read_file`, `write_file`, `edit_file`, `list_files`, and `search`. File tools stay inside the session's worktree, never follow symlinks, and leave `.git` alone. Each `bash` call is a fresh non-interactive shell in the worktree with a time limit.
- **Permission modes.** *Ask first* asks before every edit and command. *Auto-edit* (the default) applies edits and asks before commands. *Full access* asks for nothing. You can change the mode while the agent waits; widening it answers the pending request.
- **Worktrees and checkpoints.** A session on a Git workspace gets a `tsun/agent-…` branch under `STATE_DIR/worktrees/`. After each run, the daemon snapshots the worktree as a commit under the hidden ref `refs/tsun/checkpoints/<thread>/<run>`, without adding commits to the branch, and reports the run's diff. **Review changes** shows everything the session changed since it branched.
- **Switching models.** Pick a different model in the composer before sending; the conversation carries over. The daemon keeps a provider-neutral transcript and replays each provider's native reply (including Claude's reasoning blocks) unchanged when the same model continues.

Sessions live in memory until the daemon exits (up to 24, three working at once). Their worktrees, branches, and checkpoint refs stay on disk.

### Model providers

Configure providers in the `agent` section of `config.json`. With no `agent` section, the daemon offers Claude Opus 5.5 through the Anthropic API.

```json
"agent": {
  "default": "claude",
  "providers": [
    {"id": "claude", "kind": "anthropic", "label": "Claude Opus 5.5", "model": "claude-opus-5-5", "effort": "high"},
    {"id": "openrouter", "kind": "openai", "label": "OpenRouter", "baseUrl": "https://openrouter.ai/api/v1",
     "model": "<model id>", "apiKeyFile": "openrouter-key"},
    {"id": "local", "kind": "openai", "label": "Ollama", "baseUrl": "http://127.0.0.1:11434/v1",
     "model": "<model name>", "apiKeyEnv": ""}
  ]
}
```

- `kind` is `anthropic` (the Messages API) or `openai` (Chat Completions, which OpenAI, OpenRouter, vLLM, llama.cpp, LM Studio, Ollama, and similar servers accept). The model must support tool calls.
- `baseUrl` defaults to `https://api.anthropic.com` or `https://api.openai.com/v1`. Requests go to `<baseUrl>/v1/messages` or `<baseUrl>/chat/completions`.
- Keys come from `apiKeyFile`, a file in the state directory with mode 0600 (default `anthropic-key` for Anthropic providers), or else from the environment variable named by `apiKeyEnv` (default `ANTHROPIC_API_KEY` or `OPENAI_API_KEY`). Set `"apiKeyEnv": ""` for local servers that need no key. Don't put keys in `config.json`.
- `effort` sets Claude's effort level (`low` to `max`; default `high`) or, for OpenAI-compatible providers, `reasoning_effort` (unset by default). `maxTokens` caps each reply. `extraBody` and `extraHeaders` pass provider-specific options through unchanged; `"streamUsage": false` helps servers that reject `stream_options`.
- Anthropic providers enable server-side refusal fallbacks (`"fallbacks": false` turns them off) and prompt caching. The daemon honors `HTTPS_PROXY` and `NO_PROXY` for plain HTTP CONNECT proxies.

## Use your own workspace

Edit `config.json` inside your daemon state directory, then restart the daemon. Use [the demo config](examples/config.json) as a template. Set each workspace root to the absolute path of a Git checkout with at least one commit, separate from the daemon state. Each task creates a local `tsun/task-…` branch at the project's committed `HEAD` and checks it out under `STATE_DIR/worktrees/`. Uncommitted and untracked files aren't copied. For a non-Git workspace you can explicitly set `"worktrees": false`; its presets will share the original directory.

A preset is a fixed `argv` array with an absolute executable as its first element. Use `/usr/bin/git`, `/usr/bin/curl`, or your Debian build tools as appropriate. Presets start in the selected worktree and inherit the daemon's environment, including its `PATH`. Use project-relative paths to keep their output in the worktree. Keep installs and root maintenance manual.

- The daemon binds HTTPS to `127.0.0.1` only. The app never needs root or direct access to `/root`. Don't expose the port over a tunnel or use this on a shared device you don't trust.
- **Presets and agents are not sandboxed.** They run with the daemon's user privileges. An agent in *Auto-edit* or *Full access* mode runs model-chosen code without asking first, so treat it like a person with a shell on your Debian account. Model replies, tool output, and file contents go to the provider you configured. Use an unprivileged Debian account where the repo permits it, and only configure repos and presets you trust. Stop targets the job's process group, not programs that detach from it. Presets should not start daemons.
- Text edits use revision checks and atomic replacement. V0 doesn't create or delete files, show dotfiles, or follow symlinks. The API caps text at 1 MiB and downloads at 64 MiB; the phone editor caps editing at 128 Ki characters. HTML preview is static and offline: use inline CSS and embedded data assets. Unsaved drafts are not durable.
- Preset run history and the last 64 KiB of each run's output are kept in memory until the daemon exits (up to 32 runs). Worktrees and branches stay on disk after Stop or restart, but old tasks are not rediscovered by the app. Use `git worktree list` from the original checkout to find them. Review and commit any changes you want to keep before removing a finished worktree manually. Tsun never merges, pushes, or deletes task branches automatically.
