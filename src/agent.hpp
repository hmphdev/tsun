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
// (started by user messages); a run holds items (text, reasoning, tool calls,
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
    Json skills(const FileRoot& workspace) const;
    Json skill_file(const FileRoot& workspace, std::string_view name) const;
    Json read(std::string_view id, std::uint64_t since, std::chrono::milliseconds wait) const;
    void remove(std::string_view id);
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
    struct BackgroundCommand;

    std::shared_ptr<Thread> find(std::string_view id) const;
    void remove_idle(std::shared_ptr<Thread> thread);
    bool effort_command(Thread& thread, const std::string& text);
    bool compact_command(const std::shared_ptr<Thread>& thread, const std::string& text);
    void start_compaction(const std::shared_ptr<Thread>& thread, std::string instructions);
    void work_compaction(const std::shared_ptr<Thread>& thread);
    void begin_run(Thread& thread);
    void start_run(const std::shared_ptr<Thread>& thread, std::optional<std::string> prompt,
                   std::shared_ptr<PeerRequest> peer = {});
    ToolOutcome start_background(Thread& thread, const std::string& item_id, const ToolCall& call);
    void finish_background(BackgroundCommand& job, const ToolOutcome& outcome);
    void dispatch_background();
    void deliver_background(Thread& thread);
    ToolOutcome message_session(Thread& source, const ToolCall& call);
    void work(std::shared_ptr<Thread> thread);
    bool run_steps(Thread& thread);
    void resolve_steering(Thread& thread, bool deliver);
    bool prepare(Thread& thread);
    void checkpoint(Thread& thread, int run, std::chrono::steady_clock::time_point started);
    Json summary(const Thread& thread) const;
    Json& add_item(Thread& thread, Json item);
    void touch(Thread& thread, Json& item);
    void touch_streamed(Thread& thread, Json& item); // publish stream deltas at a fixed cadence
    Json* item(Thread& thread, const std::string& id);
    Json document(const Thread& thread) const;
    std::shared_ptr<Thread> restore(const std::string& path) const;
    void save_thread(Thread& thread, const Json& document) const;
    void save_changed(bool everything);
    void load_threads();
    void save_loop();

    const std::string state_path_;
    const std::string threads_path_;
    const ProviderSet providers_;
    mutable std::mutex mutex_;
    mutable std::condition_variable changed_;
    std::list<std::shared_ptr<Thread>> threads_;
    std::list<BackgroundCommand> background_;
    std::thread saver_;
    std::atomic<bool> stop_saver_{false};
    bool shutting_down_ = false;
};

} // namespace tsun
