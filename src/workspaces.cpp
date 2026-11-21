#include "workspaces.hpp"

#include <dirent.h>
#include <sys/stat.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <cstdlib>
#include <filesystem>
#include <memory>
#include <mutex>
#include <set>

namespace tsun {
namespace {

namespace fs = std::filesystem;

bool utf8_text(std::string_view text) {
    if (text.find('\0') != text.npos) return false;
    try {
        Json(text).dump(); // The built-in encoder rejects malformed UTF-8.
        return true;
    } catch (const Json::type_error&) {
        return false;
    }
}

bool visible_name(std::string_view name) {
    return !name.empty() && name.front() != '.' && name.size() <= 255 && utf8_text(name)
        && std::none_of(name.begin(), name.end(), [](unsigned char c) {
            return c < 32 || c == 127 || c == '\\';
        });
}

std::vector<std::string> components(std::string_view path) {
    if (path.empty()) return {};
    if (path.size() > 4096) throw Error(400, "Path is too long");
    std::vector<std::string> parts;
    for (;;) {
        const auto split = path.find('/');
        const auto part = path.substr(0, split);
        if (!visible_name(part)) throw Error(400, "Use a visible workspace-relative path");
        parts.emplace_back(part);
        if (split == path.npos) return parts;
        path.remove_prefix(split + 1);
    }
}

[[noreturn]] void path_error() {
    switch (errno) {
        case ENOENT: case ENOTDIR: case ELOOP:
            throw Error(404, "Workspace entry not found or not supported");
        case EACCES: case EPERM: case EROFS:
            throw Error(403, "Workspace entry is not accessible");
        default:
            throw Error(500, "Workspace I/O failed");
    }
}

struct stat stat_fd(const Fd& fd) {
    struct stat info{};
    if (::fstat(fd.get(), &info) < 0) path_error();
    return info;
}

bool safe_file(const struct stat& info, const FileRoot& workspace) {
    return info.st_dev == workspace.device && S_ISREG(info.st_mode) && info.st_nlink == 1
        && (info.st_mode & (S_ISUID | S_ISGID)) == 0;
}

Fd open_directory(const FileRoot& workspace, const std::vector<std::string>& parts) {
    // Open a fresh handle at each level, including for readdir on the root. dup() would
    // share the directory stream position between simultaneous listings.
    Fd dir(::openat(workspace.directory.get(), ".", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
    if (!dir) path_error();
    for (const auto& part : parts) {
        Fd next(::openat(dir.get(), part.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
        if (!next) path_error();
        if (stat_fd(next).st_dev != workspace.device) throw Error(403, "Mounted paths are not supported");
        dir = std::move(next);
    }
    return dir;
}

struct FileLocation { Fd parent; std::string name; };

FileLocation locate(const FileRoot& workspace, std::string_view path) {
    auto parts = components(path);
    if (parts.empty()) throw Error(400, "A file path is required");
    auto name = std::move(parts.back());
    parts.pop_back();
    return {open_directory(workspace, parts), std::move(name)};
}

struct OpenFile { Fd fd; struct stat info; };

OpenFile open_regular(const FileRoot& workspace, const FileLocation& file) {
    Fd handle(::openat(file.parent.get(), file.name.c_str(), O_PATH | O_NOFOLLOW | O_CLOEXEC));
    if (!handle) path_error();
    const auto info = stat_fd(handle);
    if (!safe_file(info, workspace)) throw Error(404, "Workspace entry is not a supported file");
    // Pin and check the inode before opening it for data, so a FIFO or device cannot be opened.
    const auto proc_fd = "/proc/self/fd/" + std::to_string(handle.get());
    Fd readable(::open(proc_fd.c_str(), O_RDONLY | O_CLOEXEC | O_NONBLOCK));
    if (!readable) path_error();
    return {std::move(readable), info};
}

std::string read_bounded(const Fd& fd, std::size_t limit) {
    std::string result;
    std::array<char, 8192> buffer{};
    for (;;) {
        const auto n = ::read(fd.get(), buffer.data(), buffer.size());
        if (n == 0) return result;
        if (n < 0) {
            if (errno == EINTR) continue;
            path_error();
        }
        if (static_cast<std::size_t>(n) > limit - result.size()) throw Error(413, "File is too large");
        result.append(buffer.data(), static_cast<std::size_t>(n));
    }
}

std::string revision_of(const struct stat& s) {
    return '"' + std::to_string(s.st_dev) + '-' + std::to_string(s.st_ino) + '-'
        + std::to_string(s.st_size) + '-' + std::to_string(s.st_mtim.tv_sec) + '-'
        + std::to_string(s.st_mtim.tv_nsec) + '-' + std::to_string(s.st_ctim.tv_sec) + '-'
        + std::to_string(s.st_ctim.tv_nsec) + '"';
}

std::string id_field(const Json& item) {
    const auto id = item.at("id").get<std::string>();
    if (id.empty() || id.size() > 48 || !std::all_of(id.begin(), id.end(), [](unsigned char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
    })) throw std::runtime_error("IDs must be 1–48 lowercase letters, digits, underscores, or hyphens");
    return id;
}

std::string label_field(const Json& item, const std::string& id) {
    const auto label = item.value("label", id);
    if (label.empty() || label.size() > 128 || !utf8_text(label)) throw std::runtime_error("Invalid label");
    return label;
}

bool within(const fs::path& parent, const fs::path& candidate) {
    return std::mismatch(parent.begin(), parent.end(), candidate.begin(), candidate.end()).first == parent.end();
}

std::string read_state(const Fd& directory, const char* name, std::size_t limit, bool secret = true) {
    const Fd file(::openat(directory.get(), name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK));
    if (!file) throw std::runtime_error(std::string("Could not open state file: ") + name);
    const auto info = stat_fd(file);
    if (!S_ISREG(info.st_mode) || info.st_uid != ::geteuid() || info.st_nlink != 1
        || (info.st_mode & (secret ? 0077 : 0022)) != 0) {
        throw std::runtime_error(std::string("Invalid ownership or mode on state file: ") + name);
    }
    return read_bounded(file, limit);
}

} // namespace

const Command& Workspace::command(std::string_view id) const {
    for (const auto& command : commands) if (command.id == id) return command;
    throw Error(404, "Unknown command for this workspace");
}

const Workspace& Configuration::workspace(std::string_view id) const {
    for (const auto& workspace : workspaces) if (workspace.id == id) return workspace;
    throw Error(404, "Unknown workspace");
}

Json Configuration::catalog() const {
    auto result = Json::array();
    for (const auto& workspace : workspaces) {
        auto commands = Json::array();
        for (const auto& command : workspace.commands) {
            commands.push_back({{"id", command.id}, {"label", command.label}});
        }
        result.push_back({{"id", workspace.id}, {"label", workspace.label}, {"worktrees", workspace.worktrees},
            {"commands", std::move(commands)}});
    }
    return {{"workspaces", std::move(result)}, {"protocol", 3}};
}

Configuration load_config(const char* state_path) {
    const auto state_root = fs::canonical(state_path);
    const Fd state(::open(state_root.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
    if (!state) throw std::runtime_error("Could not open the state directory");
    const auto state_info = stat_fd(state);
    if (state_info.st_uid != ::geteuid() || (state_info.st_mode & 0077) != 0) {
        throw std::runtime_error("The state directory must be owned by the daemon user with mode 0700 or stricter");
    }
    auto token = read_state(state, "token", 65);
    if (!token.empty() && token.back() == '\n') token.pop_back();
    if (token.size() != 64 || !std::all_of(token.begin(), token.end(), [](unsigned char c) {
        return (c >= 'a' && c <= 'f') || (c >= '0' && c <= '9');
    })) throw std::runtime_error("The token must be 64 lowercase hex characters (openssl rand -hex 32)");

    const auto config = Json::parse(read_state(state, "config.json", 128 * 1024, false), nullptr, false);
    if (!config.is_object() || !config.contains("workspaces")) throw std::runtime_error("Invalid config: expected a workspaces array");
    const auto port = config.value("port", Json(8787));
    if (!port.is_number_integer() || port < 0 || port > 65535 || (port > 0 && port < 1024)) {
        throw std::runtime_error("Port must be 1024–65535 (or 0 to choose a free port for tests)");
    }
    const auto& entries = config.at("workspaces");
    if (!entries.is_array() || entries.empty() || entries.size() > 32) throw std::runtime_error("Configure 1–32 workspaces");
    Configuration result{port.get<int>(), {}, std::move(token), read_state(state, "cert.pem", 16 * 1024, false),
        read_state(state, "key.pem", 16 * 1024), state_root.string(), config};
    std::set<std::string> workspace_ids;
    for (const auto& entry : entries) {
        auto id = id_field(entry);
        if (!workspace_ids.insert(id).second) throw std::runtime_error("Duplicate workspace ID");
        auto label = label_field(entry, id);
        const auto configured_root = entry.at("root").get<std::string>();
        if (configured_root.empty() || configured_root.front() != '/' || !utf8_text(configured_root)) {
            throw std::runtime_error("Workspace roots must be absolute Linux directories");
        }
        const auto root = fs::canonical(configured_root);
        const char* home = std::getenv("HOME");
        std::error_code home_error;
        const auto canonical_home = home ? fs::canonical(home, home_error) : fs::path{};
        if (root == "/" || root == "/root" || root == "/home" || root == canonical_home
            || within(root, state_root) || within(state_root, root)) {
            throw std::runtime_error("Pick a project directory separate from the daemon state and your home directory");
        }
        const auto commands = entry.value("commands", Json::array());
        if (!commands.is_array() || commands.size() > 32) throw std::runtime_error("commands must be an array of up to 32 presets");
        Workspace workspace{std::move(id), std::move(label), open_file_root(root.c_str()),
            entry.value("worktrees", true), {}};
        std::set<std::string> command_ids;
        for (const auto& item : commands) {
            auto command_id = id_field(item);
            if (!command_ids.insert(command_id).second) throw std::runtime_error("Duplicate command ID");
            auto command_label = label_field(item, command_id);
            auto argv = item.at("argv").get<std::vector<std::string>>();
            if (argv.empty() || argv.size() > 64 || argv.front().empty() || argv.front().front() != '/'
                || !std::all_of(argv.begin(), argv.end(), [](const auto& arg) {
                    return arg.size() <= 4096 && arg.find('\0') == std::string::npos;
                })) throw std::runtime_error("Commands need a fixed argv and an absolute executable path");
            workspace.commands.push_back({std::move(command_id), std::move(command_label), std::move(argv)});
        }
        result.workspaces.push_back(std::move(workspace));
    }
    return result;
}

FileRoot open_file_root(const char* path) {
    Fd directory(::open(path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
    if (!directory) path_error();
    const auto device = stat_fd(directory).st_dev;
    return {std::move(directory), device};
}

Json list_directory(const FileRoot& workspace, std::string_view path) {
    auto fd = open_directory(workspace, components(path));
    const auto close_dir = [](DIR* handle) { ::closedir(handle); };
    std::unique_ptr<DIR, decltype(close_dir)> dir(::fdopendir(fd.get()), close_dir);
    if (!dir) path_error();
    fd.release();
    struct Entry { std::string name; bool directory; std::uintmax_t size; };
    std::vector<Entry> entries;
    bool truncated = false;
    for (;;) {
        errno = 0;
        const auto* entry = ::readdir(dir.get());
        if (!entry) {
            if (errno != 0) path_error();
            break;
        }
        if (!visible_name(entry->d_name)) continue;
        struct stat info{};
        if (::fstatat(::dirfd(dir.get()), entry->d_name, &info, AT_SYMLINK_NOFOLLOW) < 0) continue;
        const bool is_dir = S_ISDIR(info.st_mode) && info.st_dev == workspace.device;
        if (!is_dir && !safe_file(info, workspace)) continue;
        if (entries.size() == 2000) { truncated = true; break; }
        entries.push_back({entry->d_name, is_dir, is_dir ? 0 : static_cast<std::uintmax_t>(info.st_size)});
    }
    std::sort(entries.begin(), entries.end(), [](const Entry& a, const Entry& b) {
        return a.directory == b.directory ? a.name < b.name : a.directory;
    });
    auto files = Json::array();
    for (const auto& entry : entries) {
        files.push_back({{"name", entry.name}, {"directory", entry.directory}, {"size", entry.size}});
    }
    return {{"entries", std::move(files)}, {"truncated", truncated}};
}

TextFile read_text(const FileRoot& workspace, std::string_view path) {
    const auto file = open_regular(workspace, locate(workspace, path));
    if (file.info.st_size > static_cast<off_t>(text_limit)) throw Error(413, "Text editor limit is 1 MiB");
    auto text = read_bounded(file.fd, text_limit);
    if (!utf8_text(text)) throw Error(415, "Not a UTF-8 text file; download it instead");
    const auto revision = revision_of(file.info);
    if (revision != revision_of(stat_fd(file.fd))) throw Error(409, "File changed while reading; reopen it");
    return {std::move(text), revision};
}

std::string write_text(const FileRoot& workspace, std::string_view path,
                       std::string_view text, std::string_view revision) {
    if (text.size() > text_limit) throw Error(413, "Text editor limit is 1 MiB");
    if (!utf8_text(text)) throw Error(415, "Save must contain UTF-8 text without NULs");
    static std::mutex writes;
    static std::atomic<unsigned long> temporary_id{0};
    const std::lock_guard lock(writes);
    const auto location = locate(workspace, path);
    const auto original = open_regular(workspace, location);
    if (revision != revision_of(original.info)) throw Error(409, "File changed; reopen it before saving");
    if (original.info.st_size > static_cast<off_t>(text_limit)) throw Error(413, "Text editor limit is 1 MiB");
    if (!utf8_text(read_bounded(original.fd, text_limit))) throw Error(415, "Only UTF-8 text files can be edited");

    auto temp = ".tsun-save-" + std::to_string(::getpid()) + '-' + std::to_string(++temporary_id);
    Fd output(::openat(location.parent.get(), temp.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600));
    if (!output) path_error();
    try {
        while (!text.empty()) {
            const auto n = ::write(output.get(), text.data(), text.size());
            if (n < 0 && errno == EINTR) continue;
            if (n <= 0) path_error();
            text.remove_prefix(static_cast<std::size_t>(n));
        }
        auto info = stat_fd(output);
        if ((info.st_uid != original.info.st_uid || info.st_gid != original.info.st_gid)
            && ::fchown(output.get(), original.info.st_uid, original.info.st_gid) < 0) path_error();
        if (::fchmod(output.get(), original.info.st_mode & 0777) < 0 || ::fsync(output.get()) < 0) path_error();
        struct stat latest{};
        if (::fstatat(location.parent.get(), location.name.c_str(), &latest, AT_SYMLINK_NOFOLLOW) < 0
            || !safe_file(latest, workspace) || revision != revision_of(latest)) {
            throw Error(409, "File changed; reopen it before saving");
        }
        if (::renameat(location.parent.get(), temp.c_str(), location.parent.get(), location.name.c_str()) < 0) path_error();
    } catch (...) {
        ::unlinkat(location.parent.get(), temp.c_str(), 0);
        throw;
    }
    return revision_of(stat_fd(output));
}

Download open_download(const FileRoot& workspace, std::string_view path) {
    auto file = open_regular(workspace, locate(workspace, path));
    if (file.info.st_size < 0 || static_cast<std::uintmax_t>(file.info.st_size) > artifact_limit) {
        throw Error(413, "Download limit is 64 MiB");
    }
    return {std::move(file.fd), static_cast<std::size_t>(file.info.st_size)};
}

} // namespace tsun
