#pragma once

#include "workspaces.hpp"

#include <sys/types.h>

#include <chrono>
#include <condition_variable>
#include <deque>
#include <memory>
#include <mutex>
#include <optional>
#include <thread>

namespace tsun {

class Jobs {
public:
    explicit Jobs(const std::string& state_path);
    ~Jobs();
    Jobs(const Jobs&) = delete;
    Jobs& operator=(const Jobs&) = delete;

    Json launch(const Workspace& workspace, const Command& command, std::string_view title);
    std::shared_ptr<const FileRoot> files(const Workspace& workspace, std::string_view id) const;
    Json list() const;
    Json details(std::string_view id) const;
    Json stop(std::string_view id);

private:
    using Clock = std::chrono::steady_clock;
    struct Job {
        std::string id;
        std::string workspace;
        std::string label;
        std::string branch;
        std::string worktree_path;
        std::vector<std::string> preset;
        std::shared_ptr<const FileRoot> worktree;
        bool preparing = false;
        bool prepare_timed_out = false;
        pid_t pid = -1;
        Fd output;
        std::string logs;
        bool truncated = false;
        bool stopping = false;
        std::optional<int> exit_code;
        Clock::time_point kill_at;
        Clock::time_point prepare_deadline;

        Json json(bool include_logs) const;
        void spawn(const Fd& cwd, const std::vector<std::string>& args, char* const env[]);
        void append_log(std::string_view text);
        void read_output();
        void request_stop();
        void update();
    };

    void supervise();
    mutable std::mutex mutex_;
    std::condition_variable wake_;
    std::deque<Job> jobs_;
    const std::string worktree_storage_;
    bool shutting_down_ = false;
    std::thread worker_;
};

} // namespace tsun
