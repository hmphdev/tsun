#pragma once

#include "provider.hpp"

#include <atomic>

namespace tsun {

enum class ToolRisk { Read, Edit, Command };

struct ToolOutcome {
    std::string content;          // what the model sees
    bool error = false;
    Json display = Json::object(); // extra fields for the app (exit code, diff, preview)
};

struct ToolContext {
    const FileRoot& root;
    std::string root_path;
    const std::atomic<bool>& cancel;
};

// The same tool surface for every provider: JSON-schema function tools.
const std::vector<ToolSpec>& agent_tools();
ToolRisk tool_risk(std::string_view name);
// Checks a call against its schema and keeps its paths inside the workspace, so
// the user is never asked to approve a call that cannot run. Returns an error
// message for the model.
std::optional<std::string> validate_call(const ToolCall& call, const std::string& root_path);
// Short fields the app shows before and while a call runs.
Json describe_call(const ToolCall& call, const std::string& root_path);
ToolOutcome run_tool(const ToolContext& context, const ToolCall& call);

} // namespace tsun
