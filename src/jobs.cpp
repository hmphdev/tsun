#include "jobs.hpp"

#include <openssl/rand.h>
#include <signal.h>
#include <spawn.h>
#include <sys/stat.h>
#include <sys/wait.h>

#include <algorithm>
#include <array>
#include <cerrno>
#include <cstring>
#include <iterator>

namespace tsun {
namespace {

using namespace std::chrono_literals;
constexpr std::size_t max_running = 2;
constexpr std::size_t max_history = 32;
constexpr std::size_t max_log = 64 * 1024;

std::string worktree_storage(const std::string& state_path) {
    const auto path = state_path + "/worktrees";
    if (::mkdir(path.c_str(), 0700) < 0 && errno != EEXIST) {
        throw std::runtime_error("Could not create the worktrees directory in daemon state");
    }
    const Fd dir(::open(path.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
    struct stat info{};
    if (!dir || ::fstat(dir.get(), &info) < 0 || info.st_uid != ::geteuid() || (info.st_mode & 0077) != 0) {
        throw std::runtime_error("The worktrees directory must be private and owned by the daemon user");
    }
    return path;
}

std::string task_id() {
    std::array<unsigned char, 12> bytes{};
    if (RAND_bytes(bytes.data(), static_cast<int>(bytes.size())) != 1) throw Error(500, "Could not allocate a task ID");
    constexpr std::string_view hex = "0123456789abcdef";
    std::string id;
    for (const auto byte : bytes) {
        id += hex[byte >> 4];
        id += hex[byte & 15];
    }
    return id;
}

std::string job_label(std::string_view title, const Command& command) {
    if (title.size() > 240 || std::any_of(title.begin(), title.end(), [](unsigned char c) { return c < 32 || c == 127; })) {
        throw Error(400, "Task name must be one line and at most 240 UTF-8 bytes");
    }
    const auto first = title.find_first_not_of(' ');
    if (first == title.npos) return command.label;
    return std::string(title.substr(first, title.find_last_not_of(' ') - first + 1));
}

void spawn_check(int error) {
    if (error != 0) throw Error(500, std::string("Could not start process: ") + std::strerror(error));
}

class SpawnSetup {
public:
    SpawnSetup() {
        spawn_check(::posix_spawn_file_actions_init(&actions));
        const int error = ::posix_spawnattr_init(&attrs);
        if (error != 0) {
            ::posix_spawn_file_actions_destroy(&actions);
            spawn_check(error);
        }
    }
    ~SpawnSetup() {
        ::posix_spawnattr_destroy(&attrs);
        ::posix_spawn_file_actions_destroy(&actions);
    }
    posix_spawn_file_actions_t actions;
    posix_spawnattr_t attrs;
};

void require_git_root(const FileRoot& root) {
    struct stat dot_git{};
    if (::fstatat(root.directory.get(), ".git", &dot_git, AT_SYMLINK_NOFOLLOW) < 0
        || (!S_ISDIR(dot_git.st_mode) && !S_ISREG(dot_git.st_mode))) {
        throw Error(409, "Set this workspace to the root of a Git checkout to start an isolated task");
    }
}

} // namespace

Jobs::Jobs(const std::string& state_path) : worktree_storage_(worktree_storage(state_path)), worker_([this] { supervise(); }) {}

Jobs::~Jobs() {
    {
        const std::lock_guard lock(mutex_);
        shutting_down_ = true;
        for (auto& job : jobs_) job.request_stop();
    }
    wake_.notify_one();
    worker_.join();
}

Json Jobs::Job::json(bool include_logs) const {
    const char* state = pid >= 0 ? (stopping ? "stopping" : preparing ? "preparing" : "running")
        : stopping && !prepare_timed_out ? "stopped" : exit_code == 0 ? "succeeded" : "failed";
    Json result = {{"id", id}, {"workspace", workspace}, {"label", label}, {"state", state},
        {"branch", branch.empty() ? Json(nullptr) : Json(branch)}, {"filesReady", branch.empty() || bool(worktree)},
        {"exitCode", exit_code ? Json(*exit_code) : Json(nullptr)}};
    if (include_logs) {
        result["log"] = logs;
        result["truncated"] = truncated;
    }
    return result;
}

void Jobs::Job::spawn(const Fd& cwd, const std::vector<std::string>& args, char* const env[]) {
    int descriptors[2];
    if (::pipe2(descriptors, O_CLOEXEC) < 0) spawn_check(errno);
    Fd reader(descriptors[0]);
    Fd writer(descriptors[1]);
    if (::fcntl(reader.get(), F_SETFL, O_NONBLOCK) < 0) spawn_check(errno);

    SpawnSetup setup;
    spawn_check(::posix_spawn_file_actions_addfchdir_np(&setup.actions, cwd.get()));
    spawn_check(::posix_spawn_file_actions_addopen(&setup.actions, STDIN_FILENO, "/dev/null", O_RDONLY, 0));
    spawn_check(::posix_spawn_file_actions_adddup2(&setup.actions, writer.get(), STDOUT_FILENO));
    spawn_check(::posix_spawn_file_actions_adddup2(&setup.actions, writer.get(), STDERR_FILENO));
    spawn_check(::posix_spawn_file_actions_addclosefrom_np(&setup.actions, 3));

    sigset_t mask, defaults;
    ::sigemptyset(&mask);
    ::sigemptyset(&defaults);
    for (const int sig : {SIGTERM, SIGINT, SIGHUP, SIGPIPE}) ::sigaddset(&defaults, sig);
    spawn_check(::posix_spawnattr_setsigmask(&setup.attrs, &mask));
    spawn_check(::posix_spawnattr_setsigdefault(&setup.attrs, &defaults));
    spawn_check(::posix_spawnattr_setpgroup(&setup.attrs, 0));
    spawn_check(::posix_spawnattr_setflags(&setup.attrs, POSIX_SPAWN_SETPGROUP | POSIX_SPAWN_SETSIGMASK | POSIX_SPAWN_SETSIGDEF));

    std::vector<char*> argv;
    for (const auto& arg : args) argv.push_back(const_cast<char*>(arg.c_str()));
    argv.push_back(nullptr);
    pid_t child = -1;
    spawn_check(::posix_spawn(&child, argv.front(), &setup.actions, &setup.attrs, argv.data(), env));
    output = std::move(reader);
    exit_code.reset();
    pid = child;
}

void Jobs::Job::append_log(std::string_view text) {
    logs.append(text);
    if (logs.size() > max_log) {
        logs.erase(0, logs.size() - max_log);
        truncated = true;
    }
}

void Jobs::Job::read_output() {
    if (!output) return;
    std::array<char, 8192> buffer{};
    // Bound work under the mutex even if a child writes as fast as we read.
    for (int i = 0; i < 16; ++i) {
        const auto n = ::read(output.get(), buffer.data(), buffer.size());
        if (n > 0) {
            append_log(std::string_view(buffer.data(), static_cast<std::size_t>(n)));
        } else if (n == 0) {
            output.reset();
            break;
        } else if (errno != EINTR) {
            if (errno != EAGAIN && errno != EWOULDBLOCK) output.reset();
            break;
        }
    }
}

void Jobs::Job::request_stop() {
    if (pid < 0 || stopping) return;
    stopping = true;
    kill_at = Clock::now() + 1s;
    ::kill(-pid, SIGTERM);
}

void Jobs::Job::update() {
    if (pid < 0) return;
    read_output();
    siginfo_t info{};
    const int observed = ::waitid(P_PID, static_cast<id_t>(pid), &info, WEXITED | WNOHANG | WNOWAIT);
    if (observed < 0) {
        if (errno == ECHILD) {
            pid = -1;
            preparing = false;
            output.reset();
        }
        return;
    }
    if (info.si_pid != pid) {
        const auto now = Clock::now();
        if (preparing && !stopping && now >= prepare_deadline) {
            prepare_timed_out = true;
            append_log("\nTsun: worktree setup timed out.\n");
            request_stop();
        }
        if (stopping && now >= kill_at) ::kill(-pid, SIGKILL);
        return;
    }

    // Keep the exited leader unreaped until after cleaning its group; its PID
    // cannot be reused in this interval. Detached sessions are not contained.
    ::kill(-pid, SIGKILL);
    read_output();
    int status = 0;
    pid_t reaped;
    do { reaped = ::waitpid(pid, &status, 0); } while (reaped < 0 && errno == EINTR);
    if (reaped > 0) {
        if (WIFEXITED(status)) exit_code = WEXITSTATUS(status);
        else if (WIFSIGNALED(status)) exit_code = 128 + WTERMSIG(status);
    }
    pid = -1;
    output.reset();
    if (!std::exchange(preparing, false)) return;
    if (exit_code != 0) {
        if (!stopping) append_log("\nTsun: worktree setup failed. The preset did not run.\n");
        return;
    }
    try {
        worktree = std::make_shared<const FileRoot>(open_file_root(worktree_path.c_str()));
        if (!stopping) {
            append_log("Tsun: worktree ready on " + branch + "\n\n");
            spawn(worktree->directory, preset, environ);
        }
    } catch (const Error& error) {
        exit_code = 1;
        append_log(std::string("Tsun: ") + error.what() + '\n');
    }
    preset.clear();
}

Json Jobs::launch(const Workspace& workspace, const Command& command, std::string_view title) {
    const auto label = job_label(title, command);
    const std::lock_guard lock(mutex_);
    if (shutting_down_) throw Error(503, "Daemon is shutting down");
    const auto active = std::count_if(jobs_.begin(), jobs_.end(), [](const Job& job) { return job.pid >= 0; });
    if (static_cast<std::size_t>(active) >= max_running) throw Error(409, "Two jobs are already running");
    if (workspace.worktrees) require_git_root(workspace.root);

    jobs_.emplace_front();
    auto& job = jobs_.front();
    try {
        job.id = task_id();
        job.label = label;
        job.workspace = workspace.id;
        if (workspace.worktrees) {
            job.branch = "tsun/task-" + job.id;
            job.worktree_path = worktree_storage_ + '/' + workspace.id + '-' + job.id;
            job.preset = command.argv;
            job.preparing = true;
            job.prepare_deadline = Clock::now() + 60s;
            job.append_log("Tsun: preparing a worktree from committed HEAD…\n");
            // Use the configured checkout, not a GIT_DIR or config override from the login shell.
            std::array<std::string, 7> environment{"PATH=/usr/bin:/bin", "LC_ALL=C", "GIT_CONFIG_NOSYSTEM=1",
                "GIT_CONFIG_GLOBAL=/dev/null", "GIT_TERMINAL_PROMPT=0", "GIT_NO_LAZY_FETCH=1", "GIT_NO_REPLACE_OBJECTS=1"};
            std::array<char*, 8> env{};
            std::transform(environment.begin(), environment.end(), env.begin(), [](std::string& entry) { return entry.data(); });
            job.spawn(workspace.root.directory,
                {"/usr/bin/git", "--no-pager", "--git-dir=.git", "--work-tree=.", "-c", "core.hooksPath=/dev/null",
                    "-c", "core.fsmonitor=false", "-c", "submodule.recurse=false", "worktree", "add", "--quiet",
                    "-b", job.branch, "--", job.worktree_path, "HEAD"}, env.data());
        } else {
            job.spawn(workspace.root.directory, command.argv, environ);
        }
    } catch (...) {
        jobs_.pop_front();
        throw;
    }
    if (jobs_.size() > max_history) {
        const auto oldest_done = std::find_if(jobs_.rbegin(), jobs_.rend(), [](const Job& job) { return job.pid < 0; });
        jobs_.erase(std::prev(oldest_done.base()));
    }
    wake_.notify_one();
    return jobs_.front().json(false);
}

std::shared_ptr<const FileRoot> Jobs::files(const Workspace& workspace, std::string_view id) const {
    const std::lock_guard lock(mutex_);
    for (const auto& job : jobs_) if (job.id == id && job.workspace == workspace.id) {
        if (!job.branch.empty() && !job.worktree) throw Error(409, "This task's worktree is not available");
        return job.worktree;
    }
    throw Error(404, "Task not found in this workspace");
}

Json Jobs::list() const {
    const std::lock_guard lock(mutex_);
    auto items = Json::array();
    for (const auto& job : jobs_) items.push_back(job.json(false));
    return {{"jobs", std::move(items)}, {"capacity", max_running}};
}

Json Jobs::details(std::string_view id) const {
    const std::lock_guard lock(mutex_);
    for (const auto& job : jobs_) if (job.id == id) return job.json(true);
    throw Error(404, "Job not found");
}

Json Jobs::stop(std::string_view id) {
    const std::lock_guard lock(mutex_);
    for (auto& job : jobs_) if (job.id == id) {
        job.request_stop();
        wake_.notify_one();
        return job.json(false);
    }
    throw Error(404, "Job not found");
}

void Jobs::supervise() {
    std::unique_lock lock(mutex_);
    for (;;) {
        bool active = false;
        for (auto& job : jobs_) {
            job.update();
            active |= job.pid >= 0;
        }
        if (active) wake_.wait_for(lock, 40ms);
        else if (shutting_down_) return;
        else wake_.wait(lock);
    }
}

} // namespace tsun
