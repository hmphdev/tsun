#pragma once

#include "workspaces.hpp"

#include <sys/types.h>

#include <chrono>
#include <condition_variable>
#include <deque>
#include <mutex>
#include <optional>
#include <thread>

namespace tsun {

class Jobs {
public:
    Jobs();
    ~Jobs();
    Jobs(const Jobs&) = delete;
    Jobs& operator=(const Jobs&) = delete;

    Json launch(const Workspace& workspace, const Command& command);
    Json list() const;
    Json details(std::string_view id) const;
    Json stop(std::string_view id);

private:
    using Clock = std::chrono::steady_clock;
    struct Job {
        std::string id;
        std::string workspace;
        std::string label;
        pid_t pid = -1;
        Fd output;
        std::string logs;
        bool truncated = false;
        bool stopping = false;
        std::optional<int> exit_code;
        Clock::time_point kill_at;

        Json json(bool include_logs) const;
        void read_output();
        void request_stop();
        void update();
    };

    void supervise();
    mutable std::mutex mutex_;
    std::condition_variable wake_;
    std::deque<Job> jobs_;
    std::uint64_t next_id_ = 1;
    bool shutting_down_ = false;
    std::thread worker_;
};

} // namespace tsun
