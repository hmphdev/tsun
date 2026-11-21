#pragma once

#include "workspaces.hpp"

#include <atomic>
#include <functional>
#include <memory>

namespace tsun {

// ---- Provider-neutral conversation model -------------------------------------
// The agent loop only speaks these types. Each adapter translates them to its
// wire format at the edge, so a thread can change providers between runs.

struct ToolSpec {
    std::string name;
    std::string description;
    Json schema; // JSON Schema for the input object
};

struct ToolCall {
    std::string id;
    std::string name;
    Json input = Json::object();
    std::string invalid_input; // set when the model sent unparseable JSON
};

struct ToolResult {
    std::string call_id;
    std::string name;
    std::string content;
    bool error = false;
};

struct Turn {
    enum class Role { User, Assistant, Tools } role = Role::User;
    std::string text;                 // user prompt, or the assistant's visible text
    std::vector<ToolCall> calls;      // assistant tool calls, in order
    std::vector<ToolResult> results;  // tool results answering the previous assistant turn
    // Native assistant payload, replayed unchanged when the same provider and
    // model continue the thread (keeps reasoning blocks and signatures intact).
    std::string native_owner;
    Json native;
};

enum class StopReason { End, ToolUse, MaxTokens, Refusal, Other };

struct Reply {
    Turn turn;
    StopReason stop = StopReason::Other;
    std::string raw_stop;
    std::string model;
    std::int64_t input_tokens = 0, output_tokens = 0, cached_tokens = 0;
};

// Live stream callbacks. `slot` identifies one text, reasoning, or tool block.
struct StreamEvents {
    std::function<void(std::size_t slot, std::string_view kind, const Json& info)> started;
    std::function<void(std::size_t slot, std::string_view kind, std::string_view text)> delta;
    std::function<void(std::size_t slot, std::string_view kind, const Json& info)> finished;
};

class ProviderError : public std::runtime_error {
public:
    ProviderError(std::string message, bool retryable, bool streamed)
        : std::runtime_error(std::move(message)), retryable(retryable), streamed(streamed) {}
    bool retryable;
    bool streamed; // some content already reached the caller
};

struct ProviderSettings {
    std::string id;
    std::string label;
    std::string kind;     // "anthropic" or "openai"
    std::string base_url;
    std::string model;
    std::string api_key;
    std::string effort;   // anthropic: output_config.effort; openai: reasoning_effort
    int max_tokens = 0;   // 0 leaves the provider default (openai only)
    bool fallbacks = true;
    bool stream_usage = true;
    Json extra_body = Json::object();
    std::vector<std::pair<std::string, std::string>> extra_headers;
    std::string disabled_reason;
    bool enabled() const { return disabled_reason.empty(); }
    Json describe() const;
};

class Provider {
public:
    explicit Provider(ProviderSettings settings) : settings_(std::move(settings)) {}
    virtual ~Provider() = default;
    const ProviderSettings& settings() const { return settings_; }
    std::string owner() const { return settings_.id + "/" + settings_.model; }

    // Blocking. Retries transient failures that happen before any output.
    virtual Reply stream(const std::string& system, const std::vector<ToolSpec>& tools,
                         const std::vector<Turn>& history, const StreamEvents& events,
                         const std::atomic<bool>& cancel) const = 0;

protected:
    ProviderSettings settings_;
};

struct ProviderSet {
    std::vector<std::shared_ptr<const Provider>> providers;
    std::string default_id;
    const Provider* find(std::string_view id) const;
    Json catalog() const;
};

// Reads config.json's "agent" object. Keys come from files in STATE_DIR (mode
// 0600) or from environment variables named in the config.
ProviderSet load_providers(const Json& config, const std::string& state_path);

} // namespace tsun
