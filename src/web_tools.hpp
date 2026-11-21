#pragma once

#include "agent_tools.hpp"

namespace tsun {

ToolOutcome web_fetch(std::string_view url, const std::atomic<bool>& cancel);
ToolOutcome web_search(std::string_view query, const std::atomic<bool>& cancel);

} // namespace tsun
