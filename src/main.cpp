#include "jobs.hpp"
#include "workspaces.hpp"

#include <httplib.h>
#include <openssl/crypto.h>
#include <openssl/ssl.h>
#include <pthread.h>
#include <signal.h>

#include <algorithm>
#include <array>
#include <cerrno>
#include <iostream>
#include <memory>
#include <optional>
#include <string_view>
#include <thread>

namespace {

using httplib::Request;
using httplib::Response;
using tsun::Error;
using tsun::Json;

void respond(Response& response, const Json& body, int status = 200) {
    response.status = status;
    response.set_content(body.dump(-1, ' ', false, Json::error_handler_t::replace), "application/json");
}

void problem(Response& response, int status, const char* message) {
    respond(response, {{"error", message}}, status);
}

bool matches(std::string_view provided, std::string_view expected) {
    return provided.size() == expected.size() && CRYPTO_memcmp(provided.data(), expected.data(), expected.size()) == 0;
}

std::string fingerprint(SSL_CTX* context) {
    const auto* cert = SSL_CTX_get0_certificate(context);
    std::array<unsigned char, EVP_MAX_MD_SIZE> hash{};
    unsigned int length = 0;
    if (cert == nullptr || X509_digest(cert, EVP_sha256(), hash.data(), &length) != 1) {
        throw std::runtime_error("Could not fingerprint the TLS certificate");
    }
    constexpr std::string_view hex = "0123456789abcdef";
    std::string digest;
    for (unsigned int i = 0; i < length; ++i) {
        digest += hex[hash[i] >> 4];
        digest += hex[hash[i] & 15];
    }
    return digest;
}

std::optional<std::string> find_query(const Request& request, const char* key) {
    // httplib collapses identical pairs, so count keys in the raw query as well.
    const std::string_view target = request.target;
    const auto marker = target.find('?');
    unsigned count = 0;
    if (marker != target.npos) {
        auto remaining = target.substr(marker + 1);
        for (;;) {
            const auto separator = remaining.find('&');
            const auto pair = remaining.substr(0, separator);
            if (httplib::decode_query_component(std::string(pair.substr(0, pair.find('=')))) == key) ++count;
            if (separator == remaining.npos) break;
            remaining.remove_prefix(separator + 1);
        }
    }
    if (count == 0) return std::nullopt;
    if (count != 1 || request.get_param_value_count(key) != 1) {
        throw Error(400, std::string("Expected one query parameter: ") + key);
    }
    return request.get_param_value(key);
}

std::string query(const Request& request, const char* key) {
    auto value = find_query(request, key);
    if (!value) throw Error(400, std::string("Missing query parameter: ") + key);
    return std::move(*value);
}

// Keep a task's directory handle alive for the whole request, even if its run
// falls out of history. An unknown task must never open the project checkout.
struct FileScope {
    const tsun::Workspace& workspace;
    std::shared_ptr<const tsun::FileRoot> task;
    const tsun::FileRoot& root() const { return task ? *task : workspace.root; }
};

Json object_body(const Request& request) {
    auto value = Json::parse(request.body, nullptr, false);
    if (!value.is_object()) throw Error(400, "Expected a JSON object");
    return value;
}

std::string string_field(const Json& body, const char* key) {
    const auto it = body.find(key);
    if (it == body.end() || !it->is_string()) throw Error(400, std::string("Expected a string field: ") + key);
    return it->get<std::string>();
}

void download(Response& response, tsun::Download file) {
    response.set_header("Content-Disposition", "attachment");
    if (file.size == 0) {
        response.set_content("", "application/octet-stream");
        return;
    }
    const auto fd = std::make_shared<tsun::Fd>(std::move(file.fd));
    response.set_content_provider(file.size, "application/octet-stream",
        [fd](std::size_t offset, std::size_t length, httplib::DataSink& sink) {
            std::array<char, 64 * 1024> buffer;
            ssize_t n;
            do {
                n = ::pread(fd->get(), buffer.data(), std::min(buffer.size(), length), static_cast<off_t>(offset));
            } while (n < 0 && errno == EINTR);
            return n > 0 && sink.write(buffer.data(), static_cast<std::size_t>(n));
        });
}

int serve(const tsun::Configuration& config, httplib::SSLServer& server) {
    struct sigaction ignore{};
    ignore.sa_handler = SIG_IGN;
    ::sigemptyset(&ignore.sa_mask);
    if (::sigaction(SIGPIPE, &ignore, nullptr) != 0) throw std::runtime_error("Could not configure SIGPIPE");

    sigset_t termination;
    ::sigemptyset(&termination);
    for (const int sig : {SIGINT, SIGTERM, SIGHUP}) ::sigaddset(&termination, sig);
    if (::pthread_sigmask(SIG_BLOCK, &termination, nullptr) != 0) {
        throw std::runtime_error("Could not set up shutdown signals");
    }

    tsun::Jobs jobs(config.state_path);
    const auto scope = [&](const Request& request) -> FileScope {
        const auto& workspace = config.workspace(query(request, "workspace"));
        const auto run = find_query(request, "run");
        return {workspace, run ? jobs.files(workspace, *run) : nullptr};
    };
    server.new_task_queue = [] { return new httplib::ThreadPool(4, 4, 16); };
    server.set_payload_max_length(tsun::text_limit);
    server.set_keep_alive_max_count(1);
    server.set_read_timeout(5);
    server.set_write_timeout(5);
    server.set_default_headers({{"Cache-Control", "no-store"}, {"X-Content-Type-Options", "nosniff"},
        {"Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"}});
    const int port = config.port == 0 ? server.bind_to_any_port("127.0.0.1")
        : server.bind_to_port("127.0.0.1", config.port) ? config.port : -1;
    if (port <= 0) throw std::runtime_error("Could not bind 127.0.0.1; check whether the port is in use");

    const auto expected_host = "127.0.0.1:" + std::to_string(port);
    const auto expected_auth = "Bearer " + config.token;
    server.set_pre_routing_handler([&](const Request& request, Response& response) {
        using Result = httplib::Server::HandlerResponse;
        if (request.get_header_value_count("Host") != 1 || request.get_header_value("Host") != expected_host
            || request.has_header("Origin")) {
            problem(response, 403, "Only local app requests are allowed");
            return Result::Handled;
        }
        if (request.get_header_value_count("Authorization") != 1
            || !matches(request.get_header_value("Authorization"), expected_auth)) {
            problem(response, 401, "Pairing key is missing or incorrect");
            return Result::Handled;
        }
        return Result::Unhandled;
    });
    server.set_exception_handler([](const Request&, Response& response, const std::exception_ptr& error) {
        try {
            std::rethrow_exception(error);
        } catch (const Error& e) {
            problem(response, e.status, e.what());
        } catch (...) {
            problem(response, 500, "Unexpected daemon error");
        }
    });
    server.set_error_handler([](const Request&, Response& response) {
        if (response.body.empty()) problem(response, response.status, httplib::status_message(response.status));
    });

    server.Get("/v1/workspaces", [&](const Request&, Response& res) { respond(res, config.catalog()); });
    server.Get("/v1/entries", [&](const Request& req, Response& res) {
        respond(res, tsun::list_directory(scope(req).root(), find_query(req, "path").value_or("")));
    });
    server.Get("/v1/file", [&](const Request& req, Response& res) {
        const auto file = tsun::read_text(scope(req).root(), query(req, "path"));
        respond(res, {{"text", file.text}, {"revision", file.revision}});
    });
    server.Put("/v1/file", [&](const Request& req, Response& res) {
        const auto files = scope(req);
        const auto path = query(req, "path");
        if (req.get_header_value_count("If-Match") != 1) throw Error(428, "Open the file first to get its revision");
        const auto type = req.get_header_value("Content-Type");
        if (type != "text/plain" && type != "text/plain; charset=utf-8") throw Error(415, "Use text/plain; charset=utf-8");
        const auto revision = tsun::write_text(files.root(), path, req.body, req.get_header_value("If-Match"));
        respond(res, {{"revision", revision}});
    });
    server.Get("/v1/download", [&](const Request& req, Response& res) {
        download(res, tsun::open_download(scope(req).root(), query(req, "path")));
    });
    server.Get("/v1/jobs", [&](const Request&, Response& res) { respond(res, jobs.list()); });
    server.Post("/v1/jobs", [&](const Request& req, Response& res) {
        const auto body = object_body(req);
        const auto& workspace = config.workspace(string_field(body, "workspace"));
        const auto title = body.contains("title") ? string_field(body, "title") : std::string{};
        respond(res, jobs.launch(workspace, workspace.command(string_field(body, "command")), title), 201);
    });
    server.Get(R"(/v1/jobs/([a-f0-9]{24}))", [&](const Request& req, Response& res) {
        respond(res, jobs.details(req.matches[1].str()));
    });
    server.Post(R"(/v1/jobs/([a-f0-9]{24})/stop)", [&](const Request& req, Response& res) {
        respond(res, jobs.stop(req.matches[1].str()));
    });

    server.set_start_handler([&] { std::cout << "Tsun listening on https://" << expected_host << std::endl; });
    std::jthread shutdown([&](std::stop_token stop) {
        while (!stop.stop_requested()) {
            const timespec timeout{0, 100'000'000};
            if (::sigtimedwait(&termination, nullptr, &timeout) > 0) {
                server.stop();
                return;
            }
        }
    });
    if (::geteuid() == 0) std::cerr << "Warning: presets will run as root. Only pair your own app and trust each workspace.\n";
    return server.listen_after_bind() ? 0 : 1;
}

} // namespace

int main(int argc, char** argv) {
    if (argc == 2 && std::string_view(argv[1]) == "--version") {
        std::cout << "Tsun daemon 0.2.0 (protocol 2)\n";
        return 0;
    }
    const bool print_code = argc == 3 && std::string_view(argv[1]) == "--pairing-code";
    if (argc != 2 && !print_code) {
        std::cerr << "Usage: mobile-harness [--pairing-code] STATE_DIR\n";
        return 2;
    }
    try {
        const auto config = tsun::load_config(argv[print_code ? 2 : 1]);
        httplib::SSLServer server(httplib::SSLServer::PemMemory{
            config.certificate.data(), config.certificate.size(), config.private_key.data(), config.private_key.size(),
            nullptr, 0, nullptr,
        });
        auto* context = static_cast<SSL_CTX*>(server.tls_context());
        if (!server.is_valid() || SSL_CTX_set_min_proto_version(context, TLS1_2_VERSION) != 1) {
            throw std::runtime_error("Could not load the TLS certificate and private key from the state directory");
        }
        if (print_code) {
            if (config.port == 0) throw std::runtime_error("Use a fixed port to generate a pairing code");
            std::cout << "tsun1:" << config.port << ':' << config.token << ':' << fingerprint(context) << '\n';
            return 0;
        }
        return serve(config, server);
    } catch (const std::exception& e) {
        std::cerr << "Tsun: " << e.what() << '\n';
        return 1;
    }
}
