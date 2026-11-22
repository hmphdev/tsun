#pragma once

#include "workspaces.hpp"

#include <map>
#include <memory>
#include <optional>

namespace tsun {

struct SkillInvocation {
    std::string name, arguments;
};

bool valid_skill_name(std::string_view name);
// Returns null for ordinary text; throws Error for a malformed slash command.
std::optional<SkillInvocation> skill_invocation(std::string_view text);

struct Skill {
    std::string name, description, source, path, directory, revision;
    bool manual = false;
    std::shared_ptr<const FileRoot> root;

    TextFile read_skill_file() const;
    std::string instructions() const;
    std::string read_resource(std::string_view path) const;
};

struct SkillCatalog {
    std::map<std::string, Skill, std::less<>> entries;
    std::vector<std::string> warnings;

    Json json() const;
    std::string prompt() const;
};

SkillCatalog discover_skills(const FileRoot& workspace, const std::string& workspace_path, const std::string& state_path);

} // namespace tsun
