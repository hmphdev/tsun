#include "jobs.hpp"

#include <signal.h>
#include <spawn.h>
#include <sys/wait.h>

#include <algorithm>
#include <array>
#include <cerrno>
#include <cstring>

namespace tsun {
namespace {

using namespace std::chrono_literals;
constexpr std::size_t max_running = 2;
constexpr std::size_t max_history = 32;
constexpr std::size_t max_log = 64 * 1024;

void spawn_check(int error) {
    if (error != 0) throw Error(500, std::string("Could not start preset: ") + std::strerror(error));
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

} // namespace

Jobs::Jobs() : worker_([this] { supervise(); }) {}

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
    const char* state = pid >= 0 ? (stopping ? "stopping" : "running")
        : stopping ? "stopped" : exit_code == 0 ? "succeeded" : "failed";
    Json result = {{"id", id}, {"workspace", workspace}, {"label", label}, {"state", state},
        {"exitCode", exit_code ? Json(*exit_code) : Json(nullptr)}};
    if (include_logs) {
        result["log"] = logs;
        result["truncated"] = truncated;
    }
    return result;
}

void Jobs::Job::read_output() {
    if (!output) return;
    std::array<char, 8192> buffer{};
    // Bound work under the mutex even if a child writes as fast as we read.
    for (int i = 0; i < 16; ++i) {
        const auto n = ::read(output.get(), buffer.data(), buffer.size());
        if (n > 0) {
            logs.append(buffer.data(), static_cast<std::size_t>(n));
        } else if (n == 0) {
            output.reset();
            break;
        } else if (errno != EINTR) {
            if (errno != EAGAIN && errno != EWOULDBLOCK) output.reset();
            break;
        }
    }
    if (logs.size() > max_log) {
        logs.erase(0, logs.size() - max_log);
        truncated = true;
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
            output.reset();
        }
        return;
    }
    if (info.si_pid == pid) {
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
    } else if (stopping && Clock::now() >= kill_at) {
        ::kill(-pid, SIGKILL);
    }
}

Json Jobs::launch(const Workspace& workspace, const Command& command) {
    const std::lock_guard lock(mutex_);
    if (shutting_down_) throw Error(503, "Daemon is shutting down");
    const auto active = std::count_if(jobs_.begin(), jobs_.end(), [](const Job& job) { return job.pid >= 0; });
    if (static_cast<std::size_t>(active) >= max_running) {
        throw Error(409, "Two jobs are already running");
    }
    if (jobs_.size() >= max_history) {
        const auto oldest_done = std::find_if(jobs_.rbegin(), jobs_.rend(), [](const Job& job) { return job.pid < 0; });
        jobs_.erase(std::prev(oldest_done.base()));
    }

    int descriptors[2];
    if (::pipe2(descriptors, O_CLOEXEC) < 0) spawn_check(errno);
    Fd reader(descriptors[0]);
    Fd writer(descriptors[1]);
    if (::fcntl(reader.get(), F_SETFL, O_NONBLOCK) < 0) spawn_check(errno);

    SpawnSetup setup;
    spawn_check(::posix_spawn_file_actions_addfchdir_np(&setup.actions, workspace.directory.get()));
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
    for (const auto& arg : command.argv) argv.push_back(const_cast<char*>(arg.c_str()));
    argv.push_back(nullptr);
    jobs_.emplace_front();
    auto& job = jobs_.front();
    try {
        job.id = std::to_string(next_id_++);
        job.label = command.label;
        job.workspace = workspace.id;
        job.output = std::move(reader);
        spawn_check(::posix_spawn(&job.pid, argv.front(), &setup.actions, &setup.attrs, argv.data(), environ));
    } catch (...) {
        jobs_.pop_front();
        throw;
    }
    wake_.notify_one();
    return job.json(false);
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
