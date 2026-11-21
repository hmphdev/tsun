#include "agent.hpp"
#include "process.hpp"

#include <openssl/rand.h>
#include <sys/stat.h>

#include <algorithm>
#include <array>
#include <cerrno>
#include <sstream>

namespace tsun {
namespace {

using namespace std::chrono_literals;
constexpr std::size_t max_active = 3;
constexpr std::size_t max_threads = 24;
constexpr int max_steps = 120;
constexpr std::size_t prompt_limit = 64 * 1024;

std::string random_id() {
    std::array<unsigned char, 12> bytes{};
    if (RAND_bytes(bytes.data(), static_cast<int>(bytes.size())) != 1) throw Error(500, "Could not allocate a thread ID");
    constexpr std::string_view hex = "0123456789abcdef";
    std::string id;
    for (const auto byte : bytes) {
        id += hex[byte >> 4];
        id += hex[byte & 15];
    }
    return id;
}

std::int64_t now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
}

std::string trimmed(const std::string& text) {
    const auto first = text.find_first_not_of(" \t\r\n");
    if (first == text.npos) return {};
    return text.substr(first, text.find_last_not_of(" \t\r\n") - first + 1);
}

std::string valid_text(const Json& body, const char* key, std::size_t limit) {
    const auto it = body.find(key);
    if (it == body.end() || !it->is_string()) throw Error(400, std::string("Expected a string field: ") + key);
    auto text = trimmed(it->get<std::string>());
    if (text.empty()) throw Error(400, "Write a message first");
    if (text.size() > limit) throw Error(413, "Message is too long");
    try {
        (void)Json(text).dump();
    } catch (const Json::type_error&) {
        throw Error(400, "Message must be UTF-8 text");
    }
    return text;
}

std::string title_from(const std::string& prompt) {
    auto line = prompt.substr(0, prompt.find('\n'));
    if (line.size() > 80) {
        line.resize(80);
        while (!line.empty() && (static_cast<unsigned char>(line.back()) & 0xC0) == 0x80) line.pop_back();
        if (!line.empty()) line.pop_back();
        line += "…";
    }
    return line;
}

std::string checked_mode(const std::string& mode) {
    if (mode != "ask" && mode != "edits" && mode != "full") throw Error(400, "Mode must be ask, edits, or full");
    return mode;
}

std::string first_line(const std::string& text) {
    return trimmed(text.substr(0, text.find('\n')));
}

struct NumStat {
    int additions = 0, deletions = 0;
    Json files = Json::array();
};

NumStat parse_numstat(const std::string& output) {
    NumStat stat;
    std::istringstream lines(output);
    std::string line;
    while (std::getline(lines, line)) {
        const auto tab1 = line.find('\t');
        const auto tab2 = tab1 == line.npos ? line.npos : line.find('\t', tab1 + 1);
        if (tab2 == line.npos) continue;
        const auto added = line.substr(0, tab1), removed = line.substr(tab1 + 1, tab2 - tab1 - 1);
        const int a = added == "-" ? 0 : std::atoi(added.c_str());
        const int d = removed == "-" ? 0 : std::atoi(removed.c_str());
        stat.additions += a;
        stat.deletions += d;
        if (stat.files.size() < 60) {
            stat.files.push_back({{"path", line.substr(tab2 + 1)}, {"additions", a}, {"deletions", d}, {"binary", added == "-"}});
        }
    }
    return stat;
}

FileRoot duplicate_root(const FileRoot& root) {
    Fd copy(::fcntl(root.directory.get(), F_DUPFD_CLOEXEC, 0));
    if (!copy) throw Error(500, "Could not open the workspace");
    return {std::move(copy), root.device};
}

std::string system_prompt(const std::string& root_path, const std::string& label, const std::string& branch) {
    std::string text =
        "You are a coding agent working inside Tsun, a workbench the user drives from their Android phone. "
        "You act on a Debian machine through tools: bash, read_file, write_file, edit_file, list_files, and search.\n\n"
        "Workspace: " + root_path + " (project \"" + label + "\").\n";
    if (!branch.empty()) {
        text += "This is a dedicated Git worktree on branch " + branch + ", created from the project's committed HEAD. "
            "Your changes stay on this branch, and the user reviews the diff in Tsun.\n";
    }
    text +=
        "\nWorking rules:\n"
        "- Look before you change: read the relevant files and follow the project's existing conventions.\n"
        "- Prefer edit_file for targeted changes; use write_file for new files or full rewrites.\n"
        "- Each bash call runs in a fresh shell in the workspace root; cd and exports do not persist. Commands have "
        "no stdin, time out (default 120 s), and must not start servers or background processes.\n"
        "- Verify your work with the project's own build or tests when they exist and are quick.\n"
        "- Do not commit, push, or rewrite Git history unless the user asks.\n"
        "- The user reads your replies on a phone: lead with the outcome, keep it short, and use Markdown sparingly "
        "(short paragraphs, lists, fenced code).\n";
    return text;
}

} // namespace

struct Agents::Thread {
    std::string id, workspace, workspace_label, title, branch, worktree_path, root_path, system;
    bool isolated = false;
    std::shared_ptr<const FileRoot> workspace_root; // the original checkout
    std::shared_ptr<const FileRoot> root;           // where tools run, once ready
    std::string mode = "edits";
    std::string provider_id;
    std::string state = "idle"; // preparing, running, waiting, idle
    std::string error;
    std::vector<Turn> history;
    std::vector<Json> items;
    std::map<std::string, std::size_t> index;
    std::uint64_t rev = 0, next_item = 0;
    int runs = 0;
    std::string pending_call;
    std::string decision;
    std::atomic<bool> cancel{false};
    std::thread worker;
    std::string base_commit, last_checkpoint;
    int additions = 0, deletions = 0, files_changed = 0;
    std::int64_t input_tokens = 0, output_tokens = 0;
    std::int64_t created_at = now_ms(), updated_at = now_ms();

    bool active() const { return state != "idle"; }
};

Agents::Agents(std::string state_path, ProviderSet providers)
    : state_path_(std::move(state_path)), providers_(std::move(providers)) {
    const auto scratch = state_path_ + "/tmp";
    if (::mkdir(scratch.c_str(), 0700) < 0 && errno != EEXIST) throw std::runtime_error("Could not create STATE_DIR/tmp");
}

Agents::~Agents() {
    std::list<std::shared_ptr<Thread>> threads;
    {
        const std::lock_guard lock(mutex_);
        shutting_down_ = true;
        for (auto& thread : threads_) thread->cancel = true;
        threads = threads_;
    }
    changed_.notify_all();
    for (auto& thread : threads) if (thread->worker.joinable()) thread->worker.join();
}

Json& Agents::add_item(Thread& thread, Json item) {
    if (!item.contains("id")) item["id"] = "i" + std::to_string(++thread.next_item);
    item["rev"] = ++thread.rev;
    item["run"] = thread.runs;
    item["at"] = now_ms();
    const auto id = item["id"].get<std::string>();
    thread.index[id] = thread.items.size();
    thread.items.push_back(std::move(item));
    thread.updated_at = now_ms();
    changed_.notify_all();
    return thread.items.back();
}

void Agents::touch(Thread& thread, Json& item) {
    item["rev"] = ++thread.rev;
    thread.updated_at = now_ms();
    changed_.notify_all();
}

Json* Agents::item(Thread& thread, const std::string& id) {
    const auto it = thread.index.find(id);
    return it == thread.index.end() ? nullptr : &thread.items[it->second];
}

std::shared_ptr<Agents::Thread> Agents::find(std::string_view id) const {
    for (const auto& thread : threads_) if (thread->id == id) return thread;
    throw Error(404, "Thread not found");
}

Json Agents::summary(const Thread& thread) const {
    const auto* provider = providers_.find(thread.provider_id);
    return {
        {"id", thread.id}, {"workspace", thread.workspace}, {"title", thread.title}, {"state", thread.state},
        {"mode", thread.mode}, {"provider", thread.provider_id},
        {"providerLabel", provider ? provider->settings().label : thread.provider_id},
        {"model", provider ? provider->settings().model : ""},
        {"branch", thread.branch.empty() ? Json(nullptr) : Json(thread.branch)},
        {"filesReady", bool(thread.root)}, {"runs", thread.runs},
        {"pendingCall", thread.pending_call.empty() ? Json(nullptr) : Json(thread.pending_call)},
        {"additions", thread.additions}, {"deletions", thread.deletions}, {"filesChanged", thread.files_changed},
        {"inputTokens", thread.input_tokens}, {"outputTokens", thread.output_tokens},
        {"error", thread.error.empty() ? Json(nullptr) : Json(thread.error)},
        {"createdAt", thread.created_at}, {"updatedAt", thread.updated_at},
    };
}

Json Agents::create(const Workspace& workspace, const Json& body) {
    const auto prompt = valid_text(body, "prompt", prompt_limit);
    const auto provider_id = body.contains("provider") && body["provider"].is_string()
        ? body["provider"].get<std::string>() : providers_.default_id;
    const auto* provider = providers_.find(provider_id);
    if (!provider) throw Error(400, "Unknown model provider");
    if (!provider->settings().enabled()) throw Error(409, provider->settings().label + ": " + provider->settings().disabled_reason);
    auto title = body.contains("title") && body["title"].is_string() ? trimmed(body["title"].get<std::string>()) : "";
    if (title.size() > 240) throw Error(400, "Title must be at most 240 bytes");
    if (title.empty()) title = title_from(prompt);

    const std::lock_guard lock(mutex_);
    if (shutting_down_) throw Error(503, "Daemon is shutting down");
    const auto active = std::count_if(threads_.begin(), threads_.end(), [](const auto& t) { return t->active(); });
    if (static_cast<std::size_t>(active) >= max_active) throw Error(409, "Three agent threads are already working");
    if (workspace.worktrees) {
        struct stat dot_git{};
        if (::fstatat(workspace.root.directory.get(), ".git", &dot_git, AT_SYMLINK_NOFOLLOW) < 0) {
            throw Error(409, "Set this workspace to the root of a Git checkout to start an isolated thread");
        }
    }
    // Make room by forgetting the oldest idle thread; its worktree stays on disk.
    while (threads_.size() >= max_threads) {
        const auto oldest = std::find_if(threads_.rbegin(), threads_.rend(), [](const auto& t) { return !t->active(); });
        if (oldest == threads_.rend()) throw Error(409, "Too many threads are active");
        if ((*oldest)->worker.joinable()) (*oldest)->worker.join();
        threads_.erase(std::prev(oldest.base()));
    }

    auto thread = std::make_shared<Thread>();
    thread->id = random_id();
    thread->workspace = workspace.id;
    thread->workspace_label = workspace.label;
    thread->title = title;
    thread->mode = checked_mode(body.value("mode", std::string("edits")));
    thread->provider_id = provider_id;
    thread->workspace_root = std::make_shared<const FileRoot>(duplicate_root(workspace.root));
    thread->isolated = workspace.worktrees;
    if (thread->isolated) {
        thread->branch = "tsun/agent-" + thread->id;
        thread->worktree_path = state_path_ + "/worktrees/" + workspace.id + "-" + thread->id;
        thread->root_path = thread->worktree_path;
    } else {
        // Resolve the checkout's path for the prompt and absolute tool paths.
        const auto link = "/proc/self/fd/" + std::to_string(thread->workspace_root->directory.get());
        std::array<char, 4096> buffer{};
        const auto n = ::readlink(link.c_str(), buffer.data(), buffer.size() - 1);
        thread->root_path = n > 0 ? std::string(buffer.data(), static_cast<std::size_t>(n)) : "/";
        thread->root = thread->workspace_root;
    }
    thread->system = system_prompt(thread->root_path, workspace.label, thread->branch);
    threads_.push_front(thread);
    start_run(thread, prompt);
    return summary(*thread);
}

void Agents::start_run(const std::shared_ptr<Thread>& thread, std::string prompt) {
    // Called with mutex_ held and the thread idle.
    if (thread->worker.joinable()) thread->worker.join();
    ++thread->runs;
    thread->cancel = false;
    thread->error.clear();
    thread->state = thread->root ? "running" : "preparing";
    add_item(*thread, {{"kind", "user"}, {"text", prompt}});
    thread->history.push_back({Turn::Role::User, std::move(prompt), {}, {}, {}, {}});
    thread->worker = std::thread([this, thread] { work(thread); });
}

Json Agents::list() const {
    const std::lock_guard lock(mutex_);
    auto items = Json::array();
    for (const auto& thread : threads_) items.push_back(summary(*thread));
    return {{"threads", std::move(items)}, {"capacity", max_active}};
}

Json Agents::read(std::string_view id, std::uint64_t since, std::chrono::milliseconds wait) const {
    std::unique_lock lock(mutex_);
    const auto thread = find(id);
    if (since == thread->rev && wait.count() > 0) {
        changed_.wait_for(lock, wait, [&] { return thread->rev != since || shutting_down_; });
    }
    const bool reset = since > thread->rev;
    auto items = Json::array();
    for (const auto& entry : thread->items) {
        if (reset || entry["rev"].get<std::uint64_t>() > since) items.push_back(entry);
    }
    return {{"thread", summary(*thread)}, {"rev", thread->rev}, {"reset", reset}, {"items", std::move(items)}};
}

Json Agents::send(std::string_view id, const Json& body) {
    auto text = valid_text(body, "text", prompt_limit);
    const std::lock_guard lock(mutex_);
    if (shutting_down_) throw Error(503, "Daemon is shutting down");
    const auto thread = find(id);
    if (thread->active()) throw Error(409, "The agent is still working; wait or stop it first");
    const auto active = std::count_if(threads_.begin(), threads_.end(), [](const auto& t) { return t->active(); });
    if (static_cast<std::size_t>(active) >= max_active) throw Error(409, "Three agent threads are already working");
    if (body.contains("mode")) thread->mode = checked_mode(body["mode"].get<std::string>());
    if (body.contains("provider")) {
        const auto* provider = providers_.find(body["provider"].get<std::string>());
        if (!provider) throw Error(400, "Unknown model provider");
        if (!provider->settings().enabled()) throw Error(409, provider->settings().label + ": " + provider->settings().disabled_reason);
        if (provider->settings().id != thread->provider_id) {
            thread->provider_id = provider->settings().id;
            add_item(*thread, {{"kind", "notice"}, {"tone", "info"},
                {"text", "Switched to " + provider->settings().label + ". The conversation so far carries over."}});
        }
    }
    start_run(thread, std::move(text));
    return summary(*thread);
}

Json Agents::update(std::string_view id, const Json& body) {
    const std::lock_guard lock(mutex_);
    const auto thread = find(id);
    if (body.contains("mode")) {
        if (!body["mode"].is_string()) throw Error(400, "Mode must be a string");
        thread->mode = checked_mode(body["mode"].get<std::string>());
        // A wider mode answers the pending request the way the user would.
        if (!thread->pending_call.empty() && thread->decision.empty()) {
            const auto* pending = item(*thread, thread->pending_call);
            const auto risk = pending ? tool_risk(pending->value("name", "")) : ToolRisk::Command;
            if (thread->mode == "full" || (thread->mode == "edits" && risk == ToolRisk::Edit)) thread->decision = "allow";
        }
        thread->updated_at = now_ms();
        ++thread->rev;
        changed_.notify_all();
    }
    if (body.contains("title")) {
        if (!body["title"].is_string()) throw Error(400, "Title must be a string");
        auto title = trimmed(body["title"].get<std::string>());
        if (title.empty() || title.size() > 240) throw Error(400, "Title must be 1–240 bytes");
        thread->title = std::move(title);
        ++thread->rev;
        changed_.notify_all();
    }
    return summary(*thread);
}

Json Agents::interrupt(std::string_view id) {
    const std::lock_guard lock(mutex_);
    const auto thread = find(id);
    if (thread->active()) thread->cancel = true;
    changed_.notify_all();
    return summary(*thread);
}

Json Agents::decide(std::string_view id, const Json& body) {
    const auto call = body.value("call", std::string{});
    const auto decision = body.value("decision", std::string{});
    if (decision != "allow" && decision != "deny") throw Error(400, "Decision must be allow or deny");
    const std::lock_guard lock(mutex_);
    const auto thread = find(id);
    if (thread->pending_call.empty() || thread->pending_call != call) throw Error(409, "That request is no longer waiting");
    thread->decision = decision;
    changed_.notify_all();
    return summary(*thread);
}

std::shared_ptr<const FileRoot> Agents::files(const Workspace& workspace, std::string_view id) const {
    const std::lock_guard lock(mutex_);
    const auto thread = find(id);
    if (thread->workspace != workspace.id) throw Error(404, "Thread not found in this workspace");
    if (!thread->root) throw Error(409, "This thread's worktree is not ready yet");
    return thread->root;
}

Json Agents::diff(std::string_view id) const {
    std::shared_ptr<const FileRoot> root;
    std::string base, thread_id;
    {
        const std::lock_guard lock(mutex_);
        const auto thread = find(id);
        if (!thread->isolated) throw Error(409, "Only isolated threads have a reviewable diff");
        if (!thread->root || thread->base_commit.empty()) return {{"diff", ""}, {"files", Json::array()}, {"truncated", false}};
        root = thread->root;
        base = thread->base_commit;
        thread_id = thread->id;
    }
    // Stage the current worktree into a throwaway index, so the diff includes
    // new files and edits made after the last run, without touching the real index.
    static std::atomic<unsigned long> counter{0};
    const auto index = state_path_ + "/tmp/diff-" + thread_id + "-" + std::to_string(++counter);
    const std::vector<std::string> env{"GIT_INDEX_FILE=" + index};
    struct Remove { std::string path; ~Remove() { ::unlink(path.c_str()); } } remove{index};
    if (run_git(root->directory, {"read-tree", base}, nullptr, env).exit_code != 0
        || run_git(root->directory, {"add", "-A", "--", "."}, nullptr, env).exit_code != 0) {
        throw Error(500, "Could not stage the worktree for review");
    }
    auto patch = run_git(root->directory, {"diff", "--cached", "--no-color", "--no-ext-diff", "-M", base}, nullptr, env);
    const auto stat = parse_numstat(run_git(root->directory, {"diff", "--cached", "--numstat", "-M", base}, nullptr, env).output);
    return {{"diff", patch.output}, {"truncated", patch.truncated}, {"files", stat.files},
        {"additions", stat.additions}, {"deletions", stat.deletions}};
}

// ---- The agent loop ----------------------------------------------------------

void Agents::work(std::shared_ptr<Thread> thread) {
    const auto started = std::chrono::steady_clock::now();
    int run = 0;
    {
        const std::lock_guard lock(mutex_);
        run = thread->runs;
    }
    try {
        if (prepare(*thread)) run_steps(*thread);
    } catch (const std::exception& error) {
        const std::lock_guard lock(mutex_);
        thread->error = error.what();
        add_item(*thread, {{"kind", "notice"}, {"tone", "error"}, {"text", std::string("Tsun: ") + error.what()}});
    }
    try {
        checkpoint(*thread, run, started);
    } catch (const std::exception& error) {
        const std::lock_guard lock(mutex_);
        add_item(*thread, {{"kind", "notice"}, {"tone", "error"}, {"text", std::string("Checkpoint failed: ") + error.what()}});
    }
    const std::lock_guard lock(mutex_);
    thread->state = "idle";
    thread->pending_call.clear();
    thread->decision.clear();
    ++thread->rev;
    thread->updated_at = now_ms();
    changed_.notify_all();
}

bool Agents::prepare(Thread& thread) {
    {
        const std::lock_guard lock(mutex_);
        if (thread.root) return true;
        add_item(thread, {{"id", "setup"}, {"kind", "notice"}, {"tone", "info"},
            {"text", "Preparing a worktree on " + thread.branch + " from committed HEAD…"}});
    }
    auto added = run_git(thread.workspace_root->directory, {"--git-dir=.git", "--work-tree=.", "worktree", "add",
        "--quiet", "-b", thread.branch, "--", thread.worktree_path, "HEAD"}, &thread.cancel);
    std::string error;
    std::shared_ptr<const FileRoot> root;
    std::string base;
    if (added.exit_code != 0) {
        error = thread.cancel ? "Stopped before the worktree was ready." : "Could not create the worktree: " + first_line(added.output);
    } else {
        try {
            root = std::make_shared<const FileRoot>(open_file_root(thread.worktree_path.c_str()));
            base = trimmed(run_git(root->directory, {"rev-parse", "HEAD"}).output);
        } catch (const std::exception& e) {
            error = std::string("Could not open the worktree: ") + e.what();
        }
    }
    const std::lock_guard lock(mutex_);
    auto* setup = item(thread, "setup");
    if (!error.empty()) {
        thread.error = error;
        if (setup) {
            (*setup)["tone"] = "error";
            (*setup)["text"] = error;
            touch(thread, *setup);
        }
        return false;
    }
    thread.root = root;
    thread.base_commit = base;
    thread.last_checkpoint = base;
    thread.state = "running";
    if (setup) {
        (*setup)["text"] = "Working in a fresh worktree on " + thread.branch + ".";
        touch(thread, *setup);
    }
    return true;
}

void Agents::run_steps(Thread& thread) {
    for (int step = 0; step < max_steps; ++step) {
        std::vector<Turn> history;
        std::string system, root_path;
        const Provider* provider = nullptr;
        {
            const std::lock_guard lock(mutex_);
            if (thread.cancel) break;
            provider = providers_.find(thread.provider_id);
            history = thread.history;
            system = thread.system;
            root_path = thread.root_path;
        }
        if (!provider || !provider->settings().enabled()) throw std::runtime_error("The selected model provider is not available");

        // Map stream slots to items as they arrive.
        std::map<std::size_t, std::string> slots;
        StreamEvents events;
        events.started = [&](std::size_t slot, std::string_view kind, const Json& info) {
            const std::lock_guard lock(mutex_);
            Json entry = {{"streaming", true}};
            if (kind == "tool") {
                entry["kind"] = "tool";
                entry["call"] = info.value("id", "");
                entry["name"] = info.value("name", "");
                entry["status"] = "streaming";
                entry["title"] = describe_call({"", info.value("name", ""), Json::object(), ""}, root_path)["title"];
                entry["detail"] = "";
            } else {
                entry["kind"] = kind == "thinking" ? "thinking" : "text";
                entry["text"] = "";
            }
            slots[slot] = add_item(thread, std::move(entry))["id"].get<std::string>();
        };
        events.delta = [&](std::size_t slot, std::string_view kind, std::string_view text) {
            if (kind == "tool") return; // Arguments stream as JSON; show them once complete.
            const std::lock_guard lock(mutex_);
            const auto found = slots.find(slot);
            if (found == slots.end()) return;
            if (auto* entry = item(thread, found->second)) {
                (*entry)["text"] = (*entry)["text"].get<std::string>() + std::string(text);
                touch(thread, *entry);
            }
        };
        events.finished = [&](std::size_t slot, std::string_view kind, const Json& info) {
            const std::lock_guard lock(mutex_);
            const auto found = slots.find(slot);
            if (found == slots.end()) return;
            auto* entry = item(thread, found->second);
            if (!entry) return;
            (*entry)["streaming"] = false;
            if (kind == "tool") {
                ToolCall call{info.value("id", ""), info.value("name", ""), info.value("input", Json::object()), ""};
                const auto described = describe_call(call, root_path);
                (*entry)["call"] = call.id;
                (*entry)["title"] = described["title"];
                (*entry)["detail"] = described["detail"];
                (*entry)["status"] = "queued";
            }
            touch(thread, *entry);
        };

        Reply reply;
        try {
            reply = provider->stream(system, agent_tools(), history, events, thread.cancel);
        } catch (const ProviderError& error) {
            const std::lock_guard lock(mutex_);
            // Close streaming items and keep any visible text as context for the next run.
            std::string partial;
            for (const auto& [slot, id] : slots) {
                auto* entry = item(thread, id);
                if (!entry) continue;
                if ((*entry)["kind"] == "text") partial += (*entry)["text"].get<std::string>();
                if ((*entry)["kind"] == "tool") (*entry)["status"] = "cancelled";
                (*entry)["streaming"] = false;
                touch(thread, *entry);
            }
            if (!partial.empty()) thread.history.push_back({Turn::Role::Assistant, partial + "\n\n[interrupted]", {}, {}, {}, {}});
            if (!thread.cancel) {
                thread.error = error.what();
                add_item(thread, {{"kind", "notice"}, {"tone", "error"}, {"text", error.what()}});
            }
            break;
        }

        // Record usage and the assistant turn, then run its tool calls.
        std::vector<ToolCall> calls = reply.turn.calls;
        std::vector<std::string> call_items;
        {
            const std::lock_guard lock(mutex_);
            thread.input_tokens += reply.input_tokens;
            thread.output_tokens += reply.output_tokens;
            thread.history.push_back(reply.turn);
            for (const auto& call : calls) {
                std::string found;
                for (const auto& [slot, id] : slots) {
                    if (auto* entry = item(thread, id); entry && entry->value("call", "") == call.id) found = id;
                }
                if (found.empty()) {
                    const auto described = describe_call(call, root_path);
                    found = add_item(thread, {{"kind", "tool"}, {"call", call.id}, {"name", call.name},
                        {"title", described["title"]}, {"detail", described["detail"]}, {"status", "queued"},
                        {"streaming", false}})["id"].get<std::string>();
                }
                call_items.push_back(found);
            }
            if (reply.stop == StopReason::Refusal) {
                add_item(thread, {{"kind", "notice"}, {"tone", "error"}, {"text", "The model declined to continue this request."}});
            } else if (reply.stop == StopReason::MaxTokens && calls.empty()) {
                add_item(thread, {{"kind", "notice"}, {"tone", "error"},
                    {"text", "The reply hit the output limit. Ask the agent to continue."}});
            }
        }
        if (calls.empty() || reply.stop == StopReason::Refusal) break;

        Turn results{Turn::Role::Tools, {}, {}, {}, {}, {}};
        for (std::size_t i = 0; i < calls.size(); ++i) {
            const auto& call = calls[i];
            const auto& item_id = call_items[i];
            ToolResult result{call.id, call.name, {}, true};
            auto rejection = reply.stop == StopReason::MaxTokens
                ? std::optional<std::string>("Your reply hit the output limit before this call was complete. Send it again in smaller pieces.")
                : validate_call(call, root_path);

            std::unique_lock lock(mutex_);
            auto set_status = [&](const char* status) {
                if (auto* entry = item(thread, item_id)) {
                    (*entry)["status"] = status;
                    touch(thread, *entry);
                }
            };
            if (thread.cancel) {
                result.content = "Interrupted by the user before this call ran.";
                set_status("cancelled");
            } else if (rejection) {
                result.content = *rejection;
                set_status("error");
                if (auto* entry = item(thread, item_id)) (*entry)["output"] = *rejection;
            } else {
                const auto risk = tool_risk(call.name);
                const bool ask = (thread.mode == "ask" && risk != ToolRisk::Read) || (thread.mode == "edits" && risk == ToolRisk::Command);
                bool allowed = true;
                if (ask) {
                    thread.state = "waiting";
                    thread.pending_call = item_id;
                    thread.decision.clear();
                    set_status("waiting");
                    changed_.wait(lock, [&] { return !thread.decision.empty() || thread.cancel.load(); });
                    allowed = thread.decision == "allow" && !thread.cancel;
                    thread.pending_call.clear();
                    thread.decision.clear();
                    thread.state = "running";
                }
                if (!allowed) {
                    result.content = thread.cancel ? "Interrupted by the user." : "The user declined this action. Ask how they want to proceed, or try another approach.";
                    set_status(thread.cancel ? "cancelled" : "denied");
                } else {
                    set_status("running");
                    const auto root = thread.root;
                    lock.unlock();
                    const auto outcome = run_tool({*root, root_path, thread.cancel}, call);
                    lock.lock();
                    result.content = outcome.content;
                    result.error = outcome.error;
                    if (auto* entry = item(thread, item_id)) {
                        (*entry)["status"] = outcome.error ? "error" : "done";
                        (*entry)["output"] = outcome.content.size() > 24 * 1024 ? outcome.content.substr(0, 24 * 1024) + "…" : outcome.content;
                        for (const auto& [key, value] : outcome.display.items()) (*entry)[key] = value;
                        touch(thread, *entry);
                    }
                }
            }
            results.results.push_back(std::move(result));
        }
        {
            const std::lock_guard lock(mutex_);
            thread.history.push_back(std::move(results));
            if (thread.cancel) break;
        }
    }
    const std::lock_guard lock(mutex_);
    if (thread.cancel) add_item(thread, {{"kind", "notice"}, {"tone", "info"}, {"text", "Stopped."}});
}

void Agents::checkpoint(Thread& thread, int run, std::chrono::steady_clock::time_point started) {
    std::shared_ptr<const FileRoot> root;
    std::string parent, base;
    Json usage;
    {
        const std::lock_guard lock(mutex_);
        root = thread.root;
        parent = thread.last_checkpoint;
        base = thread.base_commit;
    }
    const auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - started).count();
    Json entry = {{"kind", "run"}, {"duration", duration}};
    if (root && thread.isolated && !parent.empty()) {
        // Snapshot the worktree as a commit under a hidden ref, like T3 Code's
        // checkpoints: no commits on the user's branch, but every run is recoverable.
        const auto index = state_path_ + "/tmp/checkpoint-" + thread.id;
        const std::vector<std::string> env{"GIT_INDEX_FILE=" + index};
        struct Remove { std::string path; ~Remove() { ::unlink(path.c_str()); } } remove{index};
        if (run_git(root->directory, {"read-tree", parent}, nullptr, env).exit_code != 0
            || run_git(root->directory, {"add", "-A", "--", "."}, nullptr, env).exit_code != 0) {
            throw std::runtime_error("could not stage the worktree");
        }
        const auto tree = trimmed(run_git(root->directory, {"write-tree"}, nullptr, env).output);
        const auto parent_tree = trimmed(run_git(root->directory, {"rev-parse", parent + "^{tree}"}).output);
        std::string commit = parent;
        if (!tree.empty() && tree != parent_tree) {
            commit = trimmed(run_git(root->directory, {"commit-tree", tree, "-p", parent, "-m",
                "Tsun checkpoint " + thread.id + " run " + std::to_string(run)}).output);
            if (commit.empty()) throw std::runtime_error("could not record the checkpoint");
            run_git(root->directory, {"update-ref", "refs/tsun/checkpoints/" + thread.id + "/" + std::to_string(run), commit});
        }
        const auto turn = parse_numstat(run_git(root->directory, {"diff", "--numstat", "-M", parent, commit}).output);
        const auto total = parse_numstat(run_git(root->directory, {"diff", "--numstat", "-M", base, commit}).output);
        entry["additions"] = turn.additions;
        entry["deletions"] = turn.deletions;
        entry["files"] = turn.files;
        entry["checkpoint"] = commit;
        const std::lock_guard lock(mutex_);
        thread.last_checkpoint = commit;
        thread.additions = total.additions;
        thread.deletions = total.deletions;
        thread.files_changed = static_cast<int>(total.files.size());
    }
    const std::lock_guard lock(mutex_);
    entry["inputTokens"] = thread.input_tokens;
    entry["outputTokens"] = thread.output_tokens;
    entry["stopped"] = thread.cancel.load();
    add_item(thread, std::move(entry));
}

} // namespace tsun
