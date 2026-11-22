# Tsun

A native Android workbench for your existing Debian chroot. Start a coding-agent session from your phone: the C++20 daemon runs the agent loop on Debian, in its own Git worktree, against the model provider you choose (the Anthropic API or any OpenAI-compatible endpoint). The Kotlin/Compose app shows the conversation, asks you to approve commands and edits, and lets you review the diff and browse, edit, preview, and export files. Fixed preset commands still work alongside agent sessions. There is no Pi or Node dependency.

## Run the daemon in Debian

You need Linux with glibc 2.34+, a C++20 compiler, CMake 3.22+, OpenSSL 3, Git, and Python 3. On Debian, install any missing packages with `apt install build-essential cmake libssl-dev libxml2-dev ca-certificates openssl git python3`. CMake fetches two pinned header dependencies on the first build. Use the daemon and APK from the same version.

```sh
cd /root/mobile-harness
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --parallel 2
ctest --test-dir build --output-on-failure

bash scripts/init-demo.sh
state="${XDG_STATE_HOME:-$HOME/.local/state}/tsun-demo/state"
./build/mobile-harness "$state"
```

For manual startup, leave the last command running in Debian. If you use the Android root launcher below, stop the manual instance after testing; the app can start it on demand. The app and the daemon must share the phone's loopback network namespace.

## Install and pair

Push this project to a GitHub repo with Actions enabled. The **Tsun CI** workflow builds and tests the daemon and builds/lints a debug APK without using the phone's Android toolchain. In the Actions run, download **Tsun-release** (recommended) or **Tsun-debug**, unzip it, and sideload the APK (Android 8+). The release APK is R8-optimized and much smoother to use; the debug APK is for diagnosing crashes. Both are signed with the CI runner's debug signing key, so a later CI build may use a different key, in which case Android will require uninstalling the old build before installing the new one.

### Start from the app (rooted Android)

For the standard Termux `chroot-distro login debian` setup, grant Tsun (`dev.tsun.app`) root in KernelSU and choose **Pair via root**. If you previously paired with a code, forget that pairing first to opt in. The launcher expects the built daemon at `/root/mobile-harness/build/mobile-harness` and an initialized state at `/root/.local/state/tsun-demo/state`, both inside Debian.

Tsun reads that state's pairing locally. If the paired port refuses the connection, it starts the daemon with a non-interactive `chroot-distro login debian` command and reconnects over pinned HTTPS. Termux need not be open, but its installation and Debian container must remain installed. The daemon starts on demand, not at boot, and is detached from the activity. On failure, check `STATE_DIR/launcher.log` in Debian. The launcher does not load interactive shell settings; put keys in `STATE_DIR/env` (mode 0600, `KEY=value` lines) or use `apiKeyFile` for provider keys. Forgetting the pairing disables Tsun's launcher; revoke the app's root grant separately in KernelSU.

### Pair manually

For other layouts or if you don't want to grant the app root, start the daemon yourself, then print the private code in another Debian shell and paste it into the app:

```sh
cd /root/mobile-harness
./build/mobile-harness --pairing-code "${XDG_STATE_HOME:-$HOME/.local/state}/tsun-demo/state"
```

Don't post the code or commit the state directory. Pairing pins the daemon certificate and authenticates with a random key. If you rotate the certificate or key, forget the old pairing and enter the new code.

To try the agent, save an Anthropic API key as `STATE_DIR/anthropic-key` (mode 0600) before starting the daemon, then type a task in the app and send it. Without a key, the app explains what is missing and the presets still work.

`init-demo.sh` starts with an empty Git workspace. To try the sample files and presets, run `bash scripts/init-demo.sh --samples /path/to/a/new/demo`. **Files** opens the selected session's worktree; the file icon next to a project opens its original checkout. Existing workspaces are not rewritten.

## Agent sessions

A session is a conversation with a coding agent about one project. The daemon runs the loop itself (stream the model's reply, run the tools it asks for, send back the results, repeat until it answers). The design follows [T3 Code](https://github.com/pingdotgg/t3code): a thread holds runs (one per message you send), a run holds items (text, reasoning, tool calls, approvals, notices), and the app catches up with a revision cursor instead of re-reading the conversation.

- **Tools.** Every provider gets the same JSON-schema tools. Use `read_file`, `write_file`, `edit_file`, `list_files`, and `search` to work with files. `read_file` on a PNG, JPEG, GIF, or WebP image up to 2 MiB returns the image itself, so the agent can look at screenshots and diagrams. Each `bash` call is a fresh non-interactive shell in the worktree with a time limit.
- **Background commands.** `bash` accepts `background: true` for builds and tests that can run while the agent does other work. Approval is unchanged. The command card shows **In background**, then the final status and bounded output. Tsun delivers the result at the next model request boundary or wakes an idle session to handle it. You can still reply while a command is running; **Stop** cancels the session's background commands too. The existing `timeout_seconds` limit still applies (120 seconds by default); foreground commands still stop at their timeout. Tsun allows up to two pending background commands or results per session and six running commands daemon-wide. Commands are stopped on clean shutdown and are not resumed after a restart. Do not use `&` to detach commands.
- **Project instructions.** Tsun loads the active workspace root's `AGENTS.md` into the system prompt at the start of each turn and shares that snapshot with subagents. On an isolated session, this is the session's Git worktree. The configured model provider receives this guidance. Tsun skips files over 16 KiB, non-UTF-8 files, and links. The agent looks for more specific instructions near affected files as needed.
- **Subagents.** Inspired by T3 Code's `delegate_task`: the agent can send a focused brief to a fresh, read-only child using the same workspace and model. The child's conversation starts with the brief. The child can read and list workspace files but cannot use the shell, edit, or delegate. It runs in the foreground, counts toward provider usage, and returns findings in an expandable **Subagent** card. Stop cancels it along with the parent. Up to two children per message, one at a time, with a two-minute and eight-step budget each. Background command support is separate; subagents still have no background mode or separate child sessions.
- **Permission modes.** *Ask first* asks before every edit and command. *Auto-edit* (the default) applies edits and asks before commands. *Full access* runs edits and commands without asking. The app remembers your last explicitly selected mode for new sessions until you forget the pairing. Session messaging asks for permission outside Full access.
- **Web tools.** `web_fetch` reads text or HTML from public HTTP(S) URLs on default ports, with no redirects, cookies, or private network addresses. `web_search` uses Brave Search, Tavily, or Firecrawl when `BRAVE_SEARCH_API_KEY`, `TAVILY_API_KEY`, or `FIRECRAWL_API_KEY` is set in the daemon environment or in `STATE_DIR/env`, and tries the providers in that order. Each variable may hold several keys separated by commas or newlines (the `…_API_KEYS` alias works too); a rejected, rate-limited, or exhausted key falls through to the next one, then to the next provider. Queries are sent to the provider; don't include private information. Web requests require approval outside Full access.
- **Session messaging.** Tap the branch chip (or **Session ID** for a shared workspace) to copy the ID. Ask another session to consult that ID. `message_session` asks the idle target a read-only question using its own history and workspace (after approval outside Full access). The answer appears in both conversations. Busy sessions are not interrupted; the target cannot edit, run commands, or relay the request to other sessions.
- **Worktrees and checkpoints.** A session on a Git workspace gets a `tsun/agent-…` branch under `STATE_DIR/worktrees/`. After each run, the daemon snapshots the worktree as a commit under the hidden ref `refs/tsun/checkpoints/<thread>/<run>`, without adding commits to the branch, and reports the run's diff. Line counts cover edits; a file that appears whole without an edit tool writing it, such as a directory copied in with a command, is listed as copied and kept out of the totals. **Review changes** shows everything the session changed since it branched.
- **Steering.** While the agent is working, type a correction or follow-up and tap the send arrow next to **Stop**. The message goes into the next model request; it cannot change a reply already streaming. Tools already running finish, but tools that haven’t started (including one waiting for approval) are skipped so the model can reconsider. If the current reply finishes without another model request, Tsun starts a follow-up run. **Stop** discards steering messages that haven’t been sent. Steering doesn’t switch models or grant tool permissions.
- **Switching models.** Pick a different model in the composer before sending; the conversation carries over. The daemon keeps a provider-neutral transcript and replays each provider's native reply (including Claude's reasoning blocks) unchanged when the same model continues.
- **Reasoning effort.** Type `/effort low` in a session (levels: `minimal`, `low`, `medium`, `high`, `xhigh`, `max`) to override the provider's configured effort from the next reply; `/effort default` resets it and `/effort` alone shows the current level. The setting is per session and also applies to its subagents.
- **Compaction.** Type `/compact` in a session to replace its older turns with a structured summary (goal, constraints, progress, decisions, next steps, files read or changed) and keep roughly the newest 20,000 tokens word for word, so a long conversation keeps working instead of outgrowing the model's context. The summary comes from one extra request to the session's model, counts toward provider usage, and never appears as a normal run. `/compact <focus>` steers what the summary emphasizes. Only the model's view changes: every item the app shows, the worktree, and the checkpoints stay as they were. A conversation that still fits in the recent window reports that it has nothing to compact. The summary format is ported from [Pi](https://github.com/badlogic/pi-mono).
- **Delete a session.** In the session switcher or **Recent**, long-press a session (or tap its **⋯** menu), choose **Delete session**, and confirm. Finish or stop any running work first. This permanently removes the conversation from Tsun, not the project files, Git worktree, branch, or checkpoints.

Sessions are saved under `STATE_DIR/threads/` and reload when the daemon restarts (up to 24, three working at once), as long as the daemon has a chance to exit cleanly. Their worktrees, branches, and checkpoint refs stay on disk.

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

- The daemon binds HTTPS to `127.0.0.1` only. Manual pairing does not need app root access. **Pair via root** is optional and runs fixed startup and pairing commands via `su`; agent traffic, file access, and approvals still use the pinned daemon API. Don't expose the port over a tunnel or use this on a shared device you don't trust.
- **Presets and agents are not sandboxed.** They run with the daemon's user privileges. An agent in *Auto-edit* or *Full access* mode runs model-chosen code without asking first, so treat it like a person with a shell on your Debian account. Model replies, tool output, and file contents go to the provider you configured. Use an unprivileged Debian account where the repo permits it, and only configure repos and presets you trust. Stop targets the job's process group, not programs that detach from it. Presets should not start daemons.
- Text edits use revision checks and atomic replacement. V0 doesn't create or delete files, show dotfiles, or follow symlinks. The API caps text at 1 MiB and downloads at 64 MiB; the phone editor caps editing at 128 Ki characters. HTML preview is static and offline: use inline CSS and embedded data assets. Unsaved drafts are not durable.
- Preset run history and the last 64 KiB of each run's output are kept in memory until the daemon exits (up to 32 runs). Worktrees and branches stay on disk after Stop or restart, but old tasks are not rediscovered by the app. Use `git worktree list` from the original checkout to find them. Review and commit any changes you want to keep before removing a finished worktree manually. Tsun never merges, pushes, or deletes task branches automatically.
