#pragma once

#include <cstdlib>
#include <fstream>
#include <mutex>
#include <string>
#include <thread>
#include <utility>

namespace tsun {

// The app launches the daemon with a fixed `env -i ...` command, so shell
// exports never reach it and the app has nowhere to put API keys. Keys live in
// <state>/env as KEY=VALUE lines (chmod 600) instead, which works for every
// launch path.
//
// Loading is lazy and off the startup path: prepare_env() only records the
// path and parses the file on a detached thread, so pairing and the first
// overview request never wait for it. A lookup that races the loader blocks on
// the once_flag for the few microseconds it takes to read a small local file.
namespace env_detail {

inline std::once_flag load_once;
inline std::string state_path;

inline void load() {
    if (state_path.empty()) return;
    std::ifstream file(state_path + "/env");
    if (!file) return;
    std::string line;
    std::size_t loaded = 0;
    while (std::getline(file, line) && loaded < 128) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (line.empty() || line[0] == '#') continue;
        const auto equals = line.find('=');
        if (equals == std::string::npos || equals == 0) continue;
        if (line.find_first_of(" \t") != std::string::npos) continue;
        const auto key = line.substr(0, equals);
        // An explicitly exported variable wins over the file.
        if (::setenv(key.c_str(), line.c_str() + equals + 1, 0) == 0) ++loaded;
    }
}

} // namespace env_detail

inline void prepare_env(std::string path) {
    env_detail::state_path = std::move(path);
    std::thread([] {
        try {
            std::call_once(env_detail::load_once, env_detail::load);
        } catch (...) {
            // A broken env file must not take the daemon down; the affected
            // tools report their "no key configured" error instead.
        }
    }).detach();
}

inline const char* env_get(const char* name) {
    std::call_once(env_detail::load_once, env_detail::load);
    return std::getenv(name);
}

} // namespace tsun
