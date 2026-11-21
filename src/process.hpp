#pragma once

#include "workspaces.hpp"

#include <atomic>
#include <chrono>
#include <optional>

namespace tsun {

struct ProcessResult {
    int exit_code = -1;     // 128 + signal when killed by a signal
    std::string output;     // stdout and stderr, interleaved
    bool truncated = false; // output kept its head and tail only
    bool timed_out = false;
    bool cancelled = false;
};

struct ProcessOptions {
    std::chrono::milliseconds timeout{std::chrono::seconds(60)};
    std::size_t head_limit = 16 * 1024;
    std::size_t tail_limit = 32 * 1024;
    const std::atomic<bool>* cancel = nullptr;
    char* const* env = nullptr; // nullptr inherits the daemon environment
};

// Runs argv[0] (an absolute path) in its own process group with stdin on
// /dev/null. Kills the whole group on timeout or cancellation.
ProcessResult run_process(const Fd& cwd, const std::vector<std::string>& argv, const ProcessOptions& options);

// Git with the user's global and system config, hooks, and prompts disabled.
// Used for worktree setup and checkpoints, never for the agent's own commands.
ProcessResult run_git(const Fd& cwd, std::vector<std::string> args, const std::atomic<bool>* cancel = nullptr,
                      const std::vector<std::string>& extra_env = {});

} // namespace tsun
