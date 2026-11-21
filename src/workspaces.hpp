#pragma once

#include <nlohmann/json.hpp>

#include <fcntl.h>
#include <unistd.h>

#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace tsun {

using Json = nlohmann::json;
inline constexpr std::size_t text_limit = 1024 * 1024;
inline constexpr std::size_t artifact_limit = 64 * 1024 * 1024;

class Error : public std::runtime_error {
public:
    Error(int status, std::string message) : std::runtime_error(std::move(message)), status(status) {}
    const int status;
};

class Fd {
public:
    explicit Fd(int fd = -1) noexcept : fd_(fd) {}
    Fd(Fd&& other) noexcept : fd_(other.release()) {}
    Fd& operator=(Fd&& other) noexcept {
        if (this != &other) {
            reset();
            fd_ = other.release();
        }
        return *this;
    }
    ~Fd() { reset(); }
    int get() const noexcept { return fd_; }
    explicit operator bool() const noexcept { return fd_ >= 0; }
    int release() noexcept { return std::exchange(fd_, -1); }
    void reset() noexcept { if (fd_ >= 0) ::close(release()); }
private:
    int fd_;
};

struct Command {
    std::string id;
    std::string label;
    std::vector<std::string> argv;
};

struct FileRoot {
    Fd directory;
    std::uintmax_t device;
};

struct Workspace {
    std::string id;
    std::string label;
    FileRoot root;
    bool worktrees;
    std::vector<Command> commands;

    const Command& command(std::string_view id) const;
};

struct Configuration {
    int port;
    std::vector<Workspace> workspaces;
    std::string token;
    std::string certificate;
    std::string private_key;
    std::string state_path;
    Json raw; // the parsed config.json, for sections other modules own

    const Workspace& workspace(std::string_view id) const;
    Json catalog() const;
};

struct TextFile {
    std::string text;
    std::string revision;
};

struct Download {
    Fd fd;
    std::size_t size;
};

Configuration load_config(const char* state_path);
FileRoot open_file_root(const char* path);
Json list_directory(const FileRoot& root, std::string_view path);
TextFile read_text(const FileRoot& root, std::string_view path);
std::string write_text(const FileRoot& root, std::string_view path,
                       std::string_view text, std::string_view revision);
Download open_download(const FileRoot& root, std::string_view path);

} // namespace tsun
