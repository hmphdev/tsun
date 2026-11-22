#include "process.hpp"

#include <poll.h>
#include <signal.h>
#include <spawn.h>
#include <sys/wait.h>

#include <algorithm>
#include <array>
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <unistd.h>

extern char** environ;

namespace tsun {
namespace {

using Clock = std::chrono::steady_clock;

void check(int error, const char* what) {
    if (error != 0) throw Error(500, std::string(what) + ": " + std::strerror(error));
}

// stdin must read as EOF. Normally that is /dev/null, but the chroot's /dev can
// vanish under memory pressure, so fall back to an empty pipe instead of
// failing every spawn with ENOENT.
Fd open_stdin() {
    if (const int fd = ::open("/dev/null", O_RDONLY | O_CLOEXEC); fd >= 0) return Fd(fd);
    int pipe_fds[2];
    if (::pipe2(pipe_fds, O_CLOEXEC) == 0) {
        ::close(pipe_fds[1]); // With no write end, reads on the read end return EOF.
        return Fd(pipe_fds[0]);
    }
    return Fd();
}

class OutputBuffer {
public:
    OutputBuffer(std::size_t head, std::size_t tail) : head_limit_(head), tail_limit_(tail) {}

    void append(std::string_view text) {
        if (head_.size() < head_limit_) {
            const auto take = std::min(text.size(), head_limit_ - head_.size());
            head_.append(text.substr(0, take));
            text.remove_prefix(take);
        }
        if (text.empty()) return;
        tail_.append(text);
        if (tail_.size() > tail_limit_ * 2) trim();
    }

    std::string finish(bool& truncated) {
        trim();
        truncated = dropped_ > 0;
        if (!truncated) return head_ + tail_;
        return head_ + "\n… [" + std::to_string(dropped_) + " bytes of output omitted] …\n" + tail_;
    }

private:
    void trim() {
        if (tail_.size() <= tail_limit_) return;
        const auto cut = tail_.size() - tail_limit_;
        tail_.erase(0, cut);
        dropped_ += cut;
    }

    std::size_t head_limit_, tail_limit_, dropped_ = 0;
    std::string head_, tail_;
};

} // namespace

ProcessResult run_process(const Fd& cwd, const std::vector<std::string>& argv, const ProcessOptions& options) {
    if (argv.empty() || argv.front().empty() || argv.front().front() != '/') {
        throw Error(500, "Process needs an absolute executable path");
    }
    int pipe_fds[2];
    if (::pipe2(pipe_fds, O_CLOEXEC) < 0) check(errno, "Could not create a pipe");
    Fd reader(pipe_fds[0]);
    Fd writer(pipe_fds[1]);

    posix_spawn_file_actions_t actions;
    posix_spawnattr_t attrs;
    check(::posix_spawn_file_actions_init(&actions), "Could not start process");
    if (const int error = ::posix_spawnattr_init(&attrs); error != 0) {
        ::posix_spawn_file_actions_destroy(&actions);
        check(error, "Could not start process");
    }
    struct Cleanup {
        posix_spawn_file_actions_t* actions;
        posix_spawnattr_t* attrs;
        ~Cleanup() {
            ::posix_spawnattr_destroy(attrs);
            ::posix_spawn_file_actions_destroy(actions);
        }
    } cleanup{&actions, &attrs};

    check(::posix_spawn_file_actions_addfchdir_np(&actions, cwd.get()), "Could not start process");
    Fd stdin_eof = open_stdin();
    if (stdin_eof) {
        check(::posix_spawn_file_actions_adddup2(&actions, stdin_eof.get(), STDIN_FILENO), "Could not start process");
    } else {
        check(::posix_spawn_file_actions_addopen(&actions, STDIN_FILENO, "/dev/null", O_RDONLY, 0),
              "Could not start process");
    }
    check(::posix_spawn_file_actions_adddup2(&actions, writer.get(), STDOUT_FILENO), "Could not start process");
    check(::posix_spawn_file_actions_adddup2(&actions, writer.get(), STDERR_FILENO), "Could not start process");
    check(::posix_spawn_file_actions_addclosefrom_np(&actions, 3), "Could not start process");
    sigset_t mask, defaults;
    ::sigemptyset(&mask);
    ::sigemptyset(&defaults);
    for (const int sig : {SIGTERM, SIGINT, SIGHUP, SIGPIPE}) ::sigaddset(&defaults, sig);
    check(::posix_spawnattr_setsigmask(&attrs, &mask), "Could not start process");
    check(::posix_spawnattr_setsigdefault(&attrs, &defaults), "Could not start process");
    check(::posix_spawnattr_setpgroup(&attrs, 0), "Could not start process");
    check(::posix_spawnattr_setflags(&attrs, POSIX_SPAWN_SETPGROUP | POSIX_SPAWN_SETSIGMASK | POSIX_SPAWN_SETSIGDEF),
        "Could not start process");

    std::vector<char*> args;
    for (const auto& arg : argv) args.push_back(const_cast<char*>(arg.c_str()));
    args.push_back(nullptr);
    pid_t pid = -1;
    check(::posix_spawn(&pid, args.front(), &actions, &attrs, args.data(), options.env ? options.env : environ),
        "Could not start process");
    writer.reset();

    ProcessResult result;
    OutputBuffer output(options.head_limit, options.tail_limit);
    const auto deadline = Clock::now() + options.timeout;
    std::optional<Clock::time_point> kill_at;
    std::array<char, 8192> buffer{};
    bool open = true;
    int status = 0;
    for (;;) {
        if (open) {
            pollfd entry{reader.get(), POLLIN, 0};
            const int ready = ::poll(&entry, 1, 50);
            if (ready > 0) {
                const auto n = ::read(reader.get(), buffer.data(), buffer.size());
                if (n > 0) output.append(std::string_view(buffer.data(), static_cast<std::size_t>(n)));
                else if (n == 0 || (errno != EINTR && errno != EAGAIN)) open = false;
            }
        } else {
            ::poll(nullptr, 0, 20);
        }
        siginfo_t info{};
        if (::waitid(P_PID, static_cast<id_t>(pid), &info, WEXITED | WNOHANG | WNOWAIT) == 0 && info.si_pid == pid) break;
        const auto now = Clock::now();
        if (!kill_at) {
            const bool cancelled = options.cancel && options.cancel->load();
            if (cancelled || now >= deadline) {
                result.cancelled = cancelled;
                result.timed_out = !cancelled;
                ::kill(-pid, SIGTERM);
                kill_at = now + std::chrono::milliseconds(800);
            }
        } else if (now >= *kill_at) {
            ::kill(-pid, SIGKILL);
        }
    }
    // The exited leader stays unreaped until its group is killed, so the group ID
    // cannot be reused in between. Then drain what is left in the pipe.
    ::kill(-pid, SIGKILL);
    while (open) {
        pollfd entry{reader.get(), POLLIN, 0};
        if (::poll(&entry, 1, 0) <= 0) break;
        const auto n = ::read(reader.get(), buffer.data(), buffer.size());
        if (n <= 0) break;
        output.append(std::string_view(buffer.data(), static_cast<std::size_t>(n)));
    }
    while (::waitpid(pid, &status, 0) < 0 && errno == EINTR) {}
    if (WIFEXITED(status)) result.exit_code = WEXITSTATUS(status);
    else if (WIFSIGNALED(status)) result.exit_code = 128 + WTERMSIG(status);
    result.output = output.finish(result.truncated);
    return result;
}

ProcessResult run_git(const Fd& cwd, std::vector<std::string> args, const std::atomic<bool>* cancel,
                      const std::vector<std::string>& extra_env) {
    std::vector<std::string> environment{"PATH=/usr/bin:/bin", "LC_ALL=C", "GIT_CONFIG_NOSYSTEM=1",
        "GIT_CONFIG_GLOBAL=/dev/null", "GIT_TERMINAL_PROMPT=0", "GIT_NO_LAZY_FETCH=1", "GIT_NO_REPLACE_OBJECTS=1",
        "GIT_AUTHOR_NAME=Tsun", "GIT_AUTHOR_EMAIL=tsun@localhost", "GIT_COMMITTER_NAME=Tsun",
        "GIT_COMMITTER_EMAIL=tsun@localhost"};
    environment.insert(environment.end(), extra_env.begin(), extra_env.end());
    std::vector<char*> env;
    for (auto& entry : environment) env.push_back(entry.data());
    env.push_back(nullptr);
    std::vector<std::string> argv{"/usr/bin/git", "--no-pager", "-c", "core.hooksPath=/dev/null", "-c",
        "core.fsmonitor=false", "-c", "submodule.recurse=false", "-c", "commit.gpgsign=false"};
    argv.insert(argv.end(), std::make_move_iterator(args.begin()), std::make_move_iterator(args.end()));
    ProcessOptions options;
    options.timeout = std::chrono::seconds(60);
    options.head_limit = 4 * 1024 * 1024;
    options.tail_limit = 64 * 1024;
    options.cancel = cancel;
    options.env = env.data();
    return run_process(cwd, argv, options);
}

} // namespace tsun
