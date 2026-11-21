#pragma once

#include "agent_tools.hpp"
#include "provider.hpp"

#include <chrono>
#include <condition_variable>
#include <list>
#include <map>
#include <memory>
#include <mutex>
#include <thread>

namespace tsun {

// Agent threads, modeled on T3 Code's orchestration: an app thread holds runs
// (one per user message); a run holds items (text, reasoning, tool calls,
// approvals, notices). Every change bumps the thread's revision, and clients
// catch up with `since=<rev>` instead of re-reading the whole conversation.
class Agents {
public:
    Agents(std::string state_path, ProviderSet providers);
    ~Agents();
    Agents(const Agents&) = delete;
    Agents& operator=(const Agents&) = delete;

    const ProviderSet& providers() const { return providers_; }
    Json create(const Workspace& workspace, const Json& body);
    Json list() const;
    Json read(std::string_view id, std::uint64_t since, std::chrono::milliseconds wait) const;
    Json send(std::string_view id, const Json& body);
    Json update(std::string_view id, const Json& body);
    Json interrupt(std::string_view id);
    Json decide(std::string_view id, const Json& body);
    Json diff(std::string_view id) const;
    std::shared_ptr<const FileRoot> files(const Workspace& workspace, std::string_view id) const;

private:
    using Clock = std::chrono::system_clock;
    struct Thread;
    struct PeerRequest;

    std::shared_ptr<Thread> find(std::string_view id) const;
    void start_run(const std::shared_ptr<Thread>& thread, std::string prompt, std::shared_ptr<PeerRequest> peer = {});
    ToolOutcome message_session(Thread& source, const ToolCall& call);
    void work(std::shared_ptr<Thread> thread);
    void run_steps(Thread& thread);
    bool prepare(Thread& thread);
    void checkpoint(Thread& thread, int run, std::chrono::steady_clock::time_point started);
    Json summary(const Thread& thread) const;
    Json& add_item(Thread& thread, Json item);
    void touch(Thread& thread, Json& item);
    Json* item(Thread& thread, const std::string& id);

    const std::string state_path_;
    const ProviderSet providers_;
    mutable std::mutex mutex_;
    mutable std::condition_variable changed_;
    std::list<std::shared_ptr<Thread>> threads_;
    bool shutting_down_ = false;
};

} // namespace tsun
