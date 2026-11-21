#include "provider.hpp"

#include <httplib.h>
#include <sys/stat.h>

#include <algorithm>
#include <array>
#include <cstdlib>
#include <optional>
#include <set>
#include <thread>

namespace tsun {
namespace {

// ---- Transport ---------------------------------------------------------------

struct Endpoint {
    std::string origin; // scheme://host[:port]
    std::string host;
    std::string path;   // base path without a trailing slash
    bool https;
};

Endpoint parse_endpoint(const std::string& url) {
    const bool https = url.rfind("https://", 0) == 0;
    if (!https && url.rfind("http://", 0) != 0) throw std::runtime_error("Provider baseUrl must start with https:// or http://");
    const auto scheme_end = url.find("://") + 3;
    const auto slash = url.find('/', scheme_end);
    Endpoint endpoint{url.substr(0, slash), {}, slash == url.npos ? "" : url.substr(slash), https};
    while (!endpoint.path.empty() && endpoint.path.back() == '/') endpoint.path.pop_back();
    const auto authority = url.substr(scheme_end, slash == url.npos ? url.npos : slash - scheme_end);
    if (authority.find('@') != authority.npos) throw std::runtime_error("Put provider credentials in a key file, not the URL");
    const auto colon = authority.rfind(':');
    endpoint.host = authority.substr(0, colon == authority.npos || authority.back() == ']' ? authority.npos : colon);
    if (endpoint.host.empty()) throw std::runtime_error("Provider baseUrl needs a host");
    return endpoint;
}

bool bypass_proxy(const std::string& host) {
    if (host == "127.0.0.1" || host == "localhost" || host == "[::1]") return true;
    const char* raw = std::getenv("NO_PROXY");
    if (!raw) raw = std::getenv("no_proxy");
    if (!raw) return false;
    std::string_view list(raw);
    while (!list.empty()) {
        const auto comma = list.find(',');
        auto entry = list.substr(0, comma);
        while (!entry.empty() && entry.front() == ' ') entry.remove_prefix(1);
        while (!entry.empty() && entry.back() == ' ') entry.remove_suffix(1);
        if (!entry.empty() && entry.front() == '.') entry.remove_prefix(1);
        if (entry == "*" || host == entry
            || (host.size() > entry.size() && host.ends_with(entry) && host[host.size() - entry.size() - 1] == '.')) {
            return true;
        }
        if (comma == list.npos) break;
        list.remove_prefix(comma + 1);
    }
    return false;
}

void configure_proxy(httplib::Client& client, const Endpoint& endpoint) {
    if (bypass_proxy(endpoint.host)) return;
    const char* raw = std::getenv(endpoint.https ? "HTTPS_PROXY" : "HTTP_PROXY");
    if (!raw) raw = std::getenv(endpoint.https ? "https_proxy" : "http_proxy");
    if (!raw || !*raw) return;
    std::string proxy(raw);
    if (proxy.rfind("http://", 0) == 0) proxy.erase(0, 7);
    else if (proxy.find("://") != proxy.npos) return; // Only HTTP CONNECT proxies are supported.
    proxy = proxy.substr(0, proxy.find('/'));
    if (const auto at = proxy.rfind('@'); at != proxy.npos) proxy.erase(0, at + 1);
    const auto colon = proxy.rfind(':');
    const int port = colon == proxy.npos ? 80 : std::atoi(proxy.c_str() + colon + 1);
    if (port > 0) client.set_proxy(proxy.substr(0, colon), port);
}

// Splits a Server-Sent Events byte stream into `data:` payloads.
class SseReader {
public:
    template <typename F> void feed(std::string_view chunk, F&& on_data) {
        if (raw_.size() < 64 * 1024) raw_.append(chunk.substr(0, 64 * 1024 - raw_.size()));
        buffer_.append(chunk);
        std::size_t start = 0;
        for (;;) {
            const auto end = buffer_.find('\n', start);
            if (end == buffer_.npos) break;
            auto line = std::string_view(buffer_).substr(start, end - start);
            if (!line.empty() && line.back() == '\r') line.remove_suffix(1);
            start = end + 1;
            if (line.empty()) {
                if (!data_.empty()) {
                    const auto payload = std::move(data_);
                    data_.clear();
                    on_data(payload);
                }
            } else if (line.starts_with("data:")) {
                auto data = line.substr(5);
                if (!data.empty() && data.front() == ' ') data.remove_prefix(1);
                if (!data_.empty()) data_ += '\n';
                data_.append(data);
                if (data_.size() > 8 * 1024 * 1024) throw ProviderError("Stream event is too large", false, true);
            }
        }
        buffer_.erase(0, start);
    }
    const std::string& raw() const { return raw_; }

private:
    std::string buffer_, data_, raw_;
};

std::string http_error(const ProviderSettings& settings, int status, const std::string& body) {
    const auto parsed = Json::parse(body, nullptr, false);
    std::string message;
    if (parsed.is_object() && parsed.contains("error")) {
        const auto& error = parsed["error"];
        if (error.is_object()) message = error.value("message", "");
        else if (error.is_string()) message = error.get<std::string>();
    }
    if (message.empty()) message = body.substr(0, 300);
    if (message.empty()) message = "HTTP " + std::to_string(status);
    if (status == 401 || status == 403) return settings.label + " rejected the API key: " + message;
    return settings.label + " request failed (" + std::to_string(status) + "): " + message;
}

// Posts a streaming request. `parser` consumes each SSE payload and returns
// true once the provider signalled a complete response.
template <typename Parser>
void post_stream(const ProviderSettings& settings, const std::string& path, httplib::Headers headers,
                 const Json& body, const std::atomic<bool>& cancel, Parser& parser) {
    const auto endpoint = parse_endpoint(settings.base_url);
    for (const auto& [key, value] : settings.extra_headers) headers.emplace(key, value);
    const auto payload = body.dump(-1, ' ', false, Json::error_handler_t::replace);
    for (int attempt = 0;; ++attempt) {
        httplib::Client client(endpoint.origin);
        client.set_connection_timeout(15);
        client.set_read_timeout(300); // Reasoning models can stay quiet for a while.
        client.set_write_timeout(60);
        client.set_keep_alive(false);
        client.set_follow_location(false);
        if (const char* bundle = std::getenv("SSL_CERT_FILE"); bundle && endpoint.https) client.set_ca_cert_path(bundle);
        configure_proxy(client, endpoint);

        parser.reset();
        SseReader reader;
        std::optional<ProviderError> failure;
        // A model can stay silent for minutes while it reasons. Shut the socket
        // down as soon as the run is interrupted instead of waiting for a chunk.
        std::atomic<bool> finished{false};
        std::thread watcher([&] {
            while (!finished.load()) {
                if (cancel.load()) {
                    client.stop();
                    return;
                }
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
            }
        });
        auto result = client.Post(endpoint.path + path, headers, payload, "application/json",
            [&](const char* data, std::size_t length) {
                if (cancel.load()) return false;
                try {
                    reader.feed(std::string_view(data, length), [&](const std::string& event) { parser.event(event); });
                } catch (const ProviderError& error) {
                    failure.emplace(error);
                    return false;
                }
                return true;
            });
        finished = true;
        watcher.join();
        if (cancel.load()) throw ProviderError("Interrupted", false, parser.streamed());
        if (!failure) {
            if (!result) {
                failure.emplace("Could not reach " + settings.label + ": " + httplib::to_string(result.error()), true,
                    parser.streamed());
            } else if (result->status < 200 || result->status > 299) {
                const int status = result->status;
                failure.emplace(http_error(settings, status, reader.raw()),
                    status == 408 || status == 409 || status == 429 || status >= 500, false);
            } else if (!parser.complete()) {
                failure.emplace(settings.label + " ended the stream early", true, parser.streamed());
            }
        }
        if (!failure) return;
        if (!failure->retryable || failure->streamed || attempt >= 3) throw *failure;
        const auto wait = std::chrono::seconds(attempt == 0 ? 1 : attempt == 1 ? 3 : 9);
        for (auto waited = std::chrono::milliseconds(0); waited < wait; waited += std::chrono::milliseconds(100)) {
            if (cancel.load()) throw ProviderError("Interrupted", false, false);
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
        }
    }
}

Json parse_tool_input(const std::string& raw, std::string& invalid) {
    if (raw.empty()) return Json::object();
    auto input = Json::parse(raw, nullptr, false);
    if (input.is_object()) return input;
    invalid = raw.substr(0, 16 * 1024);
    return Json::object();
}

// Tool call IDs cross providers when a thread switches models.
std::string portable_id(const std::string& id) {
    std::string result = id.substr(0, 64);
    for (auto& c : result) {
        if (!std::isalnum(static_cast<unsigned char>(c)) && c != '_' && c != '-') c = '_';
    }
    return result.empty() ? "call" : result;
}

std::string result_text(const ToolResult& result) {
    return result.content.empty() ? "(no output)" : result.content;
}

// Anthropic accepts image blocks inside a tool_result; OpenAI's tool role is
// text only, so that adapter appends the image as a follow-up user message.
Json tool_result_content(const ToolResult& result) {
    if (!result.image) return result_text(result);
    return Json::array({
        {{"type", "text"}, {"text", result_text(result)}},
        {{"type", "image"},
            {"source", {{"type", "base64"}, {"media_type", result.image->media_type}, {"data", result.image->data}}}},
    });
}

// ---- Anthropic Messages API --------------------------------------------------

class AnthropicStream {
public:
    AnthropicStream(const StreamEvents& events) : events_(events) {}
    void reset() { *this = AnthropicStream(events_); }
    bool streamed() const { return streamed_; }
    bool complete() const { return complete_; }

    void event(const std::string& data) {
        auto event = Json::parse(data, nullptr, false);
        if (!event.is_object()) return;
        const auto type = event.value("type", "");
        if (type == "message_start") {
            model_ = event["message"].value("model", "");
            usage(event["message"].value("usage", Json::object()));
        } else if (type == "content_block_start") {
            const auto index = event.value("index", std::size_t{0});
            if (index > 512) throw ProviderError("Too many content blocks in one message", false, true);
            if (blocks_.size() <= index) {
                blocks_.resize(index + 1);
                partial_.resize(index + 1);
            }
            auto block = event["content_block"];
            const auto kind = block.value("type", "");
            if (kind == "tool_use") block["input"] = Json::object();
            blocks_[index] = block;
            streamed_ = true;
            if (!events_.started) return;
            if (kind == "text") events_.started(index, "text", Json::object());
            else if (kind == "thinking" || kind == "redacted_thinking") events_.started(index, "thinking", Json::object());
            else if (kind == "tool_use") events_.started(index, "tool", {{"id", block.value("id", "")}, {"name", block.value("name", "")}});
        } else if (type == "content_block_delta") {
            const auto index = event.value("index", std::size_t{0});
            if (index >= blocks_.size() || blocks_[index].is_null()) return;
            auto& block = blocks_[index];
            const auto& delta = event["delta"];
            const auto kind = delta.value("type", "");
            if (kind == "text_delta") {
                const auto text = delta.value("text", "");
                block["text"] = block.value("text", "") + text;
                if (events_.delta) events_.delta(index, "text", text);
            } else if (kind == "thinking_delta") {
                const auto text = delta.value("thinking", "");
                block["thinking"] = block.value("thinking", "") + text;
                if (events_.delta) events_.delta(index, "thinking", text);
            } else if (kind == "signature_delta") {
                block["signature"] = block.value("signature", "") + delta.value("signature", "");
            } else if (kind == "input_json_delta") {
                const auto text = delta.value("partial_json", "");
                partial_[index] += text;
                if (events_.delta) events_.delta(index, "tool", text);
            } else if (kind == "citations_delta") {
                if (!block.contains("citations")) block["citations"] = Json::array();
                block["citations"].push_back(delta["citation"]);
            }
        } else if (type == "content_block_stop") {
            const auto index = event.value("index", std::size_t{0});
            if (index >= blocks_.size() || blocks_[index].is_null()) return;
            auto& block = blocks_[index];
            const auto kind = block.value("type", "");
            if (kind == "tool_use") {
                std::string invalid;
                block["input"] = parse_tool_input(partial_[index], invalid);
                ToolCall call{block.value("id", ""), block.value("name", ""), block["input"], invalid};
                if (events_.finished) {
                    events_.finished(index, "tool", {{"id", call.id}, {"name", call.name}, {"input", call.input},
                        {"invalid", !invalid.empty()}});
                }
                calls_.emplace(index, std::move(call));
            } else if (events_.finished && kind == "text") {
                events_.finished(index, "text", {{"text", block.value("text", "")}});
            } else if (events_.finished && (kind == "thinking" || kind == "redacted_thinking")) {
                events_.finished(index, "thinking", {{"text", block.value("thinking", "")}});
            }
        } else if (type == "message_delta") {
            if (event["delta"].contains("stop_reason") && event["delta"]["stop_reason"].is_string()) {
                stop_ = event["delta"]["stop_reason"];
            }
            usage(event.value("usage", Json::object()));
        } else if (type == "message_stop") {
            complete_ = true;
        } else if (type == "error") {
            const auto& error = event["error"];
            const auto kind = error.value("type", "error");
            throw ProviderError(error.value("message", "The model stream failed") + " (" + kind + ")",
                kind == "overloaded_error" || kind == "api_error" || kind == "rate_limit_error", streamed_);
        }
    }

    Reply finish(const std::string& owner) {
        Reply reply;
        reply.turn.role = Turn::Role::Assistant;
        reply.turn.native = Json::array();
        reply.turn.native_owner = owner;
        for (std::size_t i = 0; i < blocks_.size(); ++i) {
            if (blocks_[i].is_null()) continue;
            const auto kind = blocks_[i].value("type", "");
            if (kind == "text") {
                if (!reply.turn.text.empty()) reply.turn.text += "\n\n";
                reply.turn.text += blocks_[i].value("text", "");
            }
            if (const auto call = calls_.find(i); call != calls_.end()) reply.turn.calls.push_back(call->second);
            reply.turn.native.push_back(blocks_[i]);
        }
        reply.raw_stop = stop_;
        reply.stop = stop_ == "end_turn" || stop_ == "stop_sequence" ? StopReason::End
            : stop_ == "tool_use" ? StopReason::ToolUse
            : stop_ == "max_tokens" ? StopReason::MaxTokens
            : stop_ == "refusal" ? StopReason::Refusal : StopReason::Other;
        reply.model = model_;
        reply.input_tokens = usage_.value("input_tokens", 0) + usage_.value("cache_read_input_tokens", 0)
            + usage_.value("cache_creation_input_tokens", 0);
        reply.output_tokens = usage_.value("output_tokens", 0);
        reply.cached_tokens = usage_.value("cache_read_input_tokens", 0);
        return reply;
    }

private:
    void usage(const Json& value) {
        if (!value.is_object()) return;
        for (const auto& [key, number] : value.items()) if (number.is_number_integer()) usage_[key] = number;
    }

    StreamEvents events_;
    std::vector<Json> blocks_;
    std::vector<std::string> partial_;
    std::map<std::size_t, ToolCall> calls_;
    std::string stop_, model_;
    Json usage_ = Json::object();
    bool streamed_ = false, complete_ = false;
};

class AnthropicProvider final : public Provider {
public:
    using Provider::Provider;

    Reply stream(const std::string& system, const std::vector<ToolSpec>& tools, const std::vector<Turn>& history,
                 const StreamEvents& events, const std::atomic<bool>& cancel, const std::string& effort = {}) const override {
        // Eager input streaming is only for the first-party API; gateways may reject the field.
        const bool first_party = parse_endpoint(settings_.base_url).host == "api.anthropic.com";
        Json tool_list = Json::array();
        for (const auto& tool : tools) {
            Json entry = {{"name", tool.name}, {"description", tool.description}, {"input_schema", tool.schema}};
            if (first_party) entry["eager_input_streaming"] = true;
            tool_list.push_back(std::move(entry));
        }
        Json request = {
            {"model", settings_.model},
            {"max_tokens", settings_.max_tokens > 0 ? settings_.max_tokens : 64000},
            {"stream", true},
            {"system", Json::array({{{"type", "text"}, {"text", system}}})},
            {"tools", std::move(tool_list)},
            {"messages", messages(history)},
            {"cache_control", {{"type", "ephemeral"}}},
            {"thinking", {{"type", "adaptive"}, {"display", "summarized"}}},
        };
        const std::string level = effort.empty() ? settings_.effort : effort; // per-session /effort override
        if (!level.empty()) request["output_config"] = {{"effort", level}};
        if (settings_.fallbacks) request["fallbacks"] = "default";
        request.update(settings_.extra_body);

        httplib::Headers headers{{"x-api-key", settings_.api_key}, {"anthropic-version", "2026-10-21"},
            {"accept", "text/event-stream"}};
        if (settings_.fallbacks) headers.emplace("anthropic-beta", "server-side-fallback-2026-07-01");
        AnthropicStream parser(events);
        post_stream(settings_, "/v1/messages", std::move(headers), request, cancel, parser);
        return parser.finish(owner());
    }

private:
    Json messages(const std::vector<Turn>& history) const {
        Json result = Json::array();
        const auto user = [&](Json blocks) {
            if (!result.empty() && result.back()["role"] == "user") {
                for (auto& block : blocks) result.back()["content"].push_back(std::move(block));
            } else {
                result.push_back({{"role", "user"}, {"content", std::move(blocks)}});
            }
        };
        for (const auto& turn : history) {
            switch (turn.role) {
            case Turn::Role::User:
                user(Json::array({{{"type", "text"}, {"text", turn.text.empty() ? "(empty message)" : turn.text}}}));
                break;
            case Turn::Role::Tools: {
                Json blocks = Json::array();
                for (const auto& r : turn.results) {
                    Json block = {{"type", "tool_result"}, {"tool_use_id", portable_id(r.call_id)},
                        {"content", tool_result_content(r)}};
                    if (r.error) block["is_error"] = true;
                    blocks.push_back(std::move(block));
                }
                user(std::move(blocks));
                break;
            }
            case Turn::Role::Assistant: {
                if (turn.native_owner == owner() && turn.native.is_array() && !turn.native.empty()) {
                    result.push_back({{"role", "assistant"}, {"content", turn.native}});
                    break;
                }
                Json blocks = Json::array();
                if (!turn.text.empty()) blocks.push_back({{"type", "text"}, {"text", turn.text}});
                for (const auto& call : turn.calls) {
                    blocks.push_back({{"type", "tool_use"}, {"id", portable_id(call.id)}, {"name", call.name},
                        {"input", call.input}});
                }
                if (blocks.empty()) blocks.push_back({{"type", "text"}, {"text", "(no response)"}});
                result.push_back({{"role", "assistant"}, {"content", std::move(blocks)}});
                break;
            }
            }
        }
        return result;
    }
};

// ---- OpenAI-compatible Chat Completions --------------------------------------

class OpenAIStream {
public:
    OpenAIStream(const StreamEvents& events) : events_(events) {}
    void reset() { *this = OpenAIStream(events_); }
    bool streamed() const { return streamed_; }
    bool complete() const { return done_ || !finish_.empty(); }

    void event(const std::string& data) {
        if (data == "[DONE]") {
            done_ = true;
            return;
        }
        auto chunk = Json::parse(data, nullptr, false);
        if (!chunk.is_object()) return;
        if (chunk.contains("error")) {
            const auto& error = chunk["error"];
            const auto message = error.is_object() ? error.value("message", "The model stream failed")
                : error.is_string() ? error.get<std::string>() : std::string("The model stream failed");
            throw ProviderError(message, false, streamed_);
        }
        if (chunk.contains("model") && chunk["model"].is_string()) model_ = chunk["model"];
        if (chunk.contains("usage") && chunk["usage"].is_object()) usage_ = chunk["usage"];
        if (!chunk.contains("choices") || !chunk["choices"].is_array() || chunk["choices"].empty()) return;
        const auto& choice = chunk["choices"][0];
        if (choice.contains("finish_reason") && choice["finish_reason"].is_string()) finish_ = choice["finish_reason"];
        if (!choice.contains("delta") || !choice["delta"].is_object()) return;
        const auto& delta = choice["delta"];
        for (const char* key : {"reasoning_content", "reasoning"}) {
            if (delta.contains(key) && delta[key].is_string() && !delta[key].get_ref<const std::string&>().empty()) {
                append_reasoning(delta[key].get<std::string>());
                break;
            }
        }
        if (delta.contains("content") && delta["content"].is_string()) {
            const auto& text = delta["content"].get_ref<const std::string&>();
            if (!text.empty()) append_text(text);
        }
        if (delta.contains("tool_calls") && delta["tool_calls"].is_array()) {
            for (const auto& part : delta["tool_calls"]) tool_part(part);
        }
    }

    Reply finish() {
        close_text();
        close_reasoning();
        Reply reply;
        reply.turn.role = Turn::Role::Assistant;
        reply.turn.text = text_;
        for (auto& [index, call] : calls_) {
            std::string invalid;
            ToolCall finished{call.id.empty() ? "call_" + std::to_string(index) : call.id, call.name,
                parse_tool_input(call.arguments, invalid), invalid};
            if (events_.finished) {
                events_.finished(call.slot, "tool", {{"id", finished.id}, {"name", finished.name},
                    {"input", finished.input}, {"invalid", !invalid.empty()}});
            }
            reply.turn.calls.push_back(std::move(finished));
        }
        reply.raw_stop = finish_;
        reply.stop = finish_ == "length" ? StopReason::MaxTokens
            : finish_ == "content_filter" ? StopReason::Refusal
            : !reply.turn.calls.empty() ? StopReason::ToolUse
            : finish_ == "stop" || finish_.empty() ? StopReason::End : StopReason::Other;
        reply.model = model_;
        reply.input_tokens = usage_.value("prompt_tokens", 0);
        reply.output_tokens = usage_.value("completion_tokens", 0);
        if (usage_.contains("prompt_tokens_details") && usage_["prompt_tokens_details"].is_object()) {
            reply.cached_tokens = usage_["prompt_tokens_details"].value("cached_tokens", 0);
        }
        return reply;
    }

private:
    struct PendingCall { std::string id, name, arguments; std::size_t slot = 0; bool announced = false; };

    void append_reasoning(const std::string& text) {
        streamed_ = true;
        if (!reasoning_slot_) {
            reasoning_slot_ = next_slot_++;
            if (events_.started) events_.started(*reasoning_slot_, "thinking", Json::object());
        }
        reasoning_ += text;
        if (events_.delta) events_.delta(*reasoning_slot_, "thinking", text);
    }

    void append_text(const std::string& text) {
        streamed_ = true;
        close_reasoning();
        if (!text_slot_) {
            text_slot_ = next_slot_++;
            if (events_.started) events_.started(*text_slot_, "text", Json::object());
            if (!text_.empty()) text_ += "\n\n";
        }
        text_ += text;
        segment_ += text;
        if (events_.delta) events_.delta(*text_slot_, "text", text);
    }

    void tool_part(const Json& part) {
        if (!part.is_object()) return;
        streamed_ = true;
        close_reasoning();
        close_text();
        const auto index = part.value("index", static_cast<int>(calls_.size()));
        if (index < 0 || index > 128) throw ProviderError("Too many tool calls in one message", false, true);
        auto& call = calls_[index];
        if (part.contains("id") && part["id"].is_string()) call.id = part["id"];
        if (part.contains("function") && part["function"].is_object()) {
            const auto& function = part["function"];
            if (function.contains("name") && function["name"].is_string()) call.name += function["name"].get<std::string>();
            if (function.contains("arguments") && function["arguments"].is_string()) {
                const auto& chunk = function["arguments"].get_ref<const std::string&>();
                call.arguments += chunk;
                if (call.announced && events_.delta) events_.delta(call.slot, "tool", chunk);
            }
        }
        if (!call.announced && !call.name.empty()) {
            call.announced = true;
            call.slot = next_slot_++;
            if (events_.started) events_.started(call.slot, "tool", {{"id", call.id}, {"name", call.name}});
        }
    }

    void close_text() {
        if (!text_slot_) return;
        if (events_.finished) events_.finished(*text_slot_, "text", {{"text", segment_}});
        text_slot_.reset();
        segment_.clear();
    }

    void close_reasoning() {
        if (!reasoning_slot_) return;
        if (events_.finished) events_.finished(*reasoning_slot_, "thinking", {{"text", reasoning_}});
        reasoning_slot_.reset();
        reasoning_.clear();
    }

    StreamEvents events_;
    std::string text_, segment_, reasoning_, finish_, model_;
    std::optional<std::size_t> text_slot_, reasoning_slot_;
    std::size_t next_slot_ = 0;
    std::map<int, PendingCall> calls_;
    Json usage_ = Json::object();
    bool streamed_ = false, done_ = false;
};

class OpenAIProvider final : public Provider {
public:
    using Provider::Provider;

    Reply stream(const std::string& system, const std::vector<ToolSpec>& tools, const std::vector<Turn>& history,
                 const StreamEvents& events, const std::atomic<bool>& cancel, const std::string& effort = {}) const override {
        Json tool_list = Json::array();
        for (const auto& tool : tools) {
            tool_list.push_back({{"type", "function"}, {"function", {{"name", tool.name},
                {"description", tool.description}, {"parameters", tool.schema}}}});
        }
        Json request = {{"model", settings_.model}, {"stream", true}, {"messages", messages(system, history)},
            {"tools", std::move(tool_list)}};
        if (settings_.stream_usage) request["stream_options"] = {{"include_usage", true}};
        if (settings_.max_tokens > 0) request["max_tokens"] = settings_.max_tokens;
        const std::string level = effort.empty() ? settings_.effort : effort; // per-session /effort override
        if (!level.empty()) request["reasoning_effort"] = level;
        request.update(settings_.extra_body);

        httplib::Headers headers{{"accept", "text/event-stream"}};
        if (!settings_.api_key.empty()) headers.emplace("Authorization", "Bearer " + settings_.api_key);
        OpenAIStream parser(events);
        post_stream(settings_, "/chat/completions", std::move(headers), request, cancel, parser);
        return parser.finish();
    }

private:
    static Json messages(const std::string& system, const std::vector<Turn>& history) {
        Json result = Json::array({{{"role", "system"}, {"content", system}}});
        for (const auto& turn : history) {
            switch (turn.role) {
            case Turn::Role::User:
                result.push_back({{"role", "user"}, {"content", turn.text}});
                break;
            case Turn::Role::Tools:
                for (const auto& r : turn.results) {
                    result.push_back({{"role", "tool"}, {"tool_call_id", portable_id(r.call_id)},
                        {"content", r.error ? "Error: " + result_text(r) : result_text(r)}});
                }
                // Tool messages cannot carry images here, so each one follows up as a user turn.
                for (const auto& r : turn.results) {
                    if (!r.image) continue;
                    result.push_back({{"role", "user"}, {"content", Json::array({
                        {{"type", "text"}, {"text", "Image returned by " + r.name + ": " + result_text(r)}},
                        {{"type", "image_url"}, {"image_url",
                            {{"url", "data:" + r.image->media_type + ";base64," + r.image->data}}}},
                    })}});
                }
                break;
            case Turn::Role::Assistant: {
                Json message = {{"role", "assistant"}};
                if (turn.calls.empty()) {
                    message["content"] = turn.text.empty() ? "(no response)" : turn.text;
                } else {
                    message["content"] = turn.text.empty() ? Json(nullptr) : Json(turn.text);
                    Json calls = Json::array();
                    for (const auto& call : turn.calls) {
                        calls.push_back({{"id", portable_id(call.id)}, {"type", "function"},
                            {"function", {{"name", call.name}, {"arguments", call.input.dump()}}}});
                    }
                    message["tool_calls"] = std::move(calls);
                }
                result.push_back(std::move(message));
                break;
            }
            }
        }
        return result;
    }
};

// ---- Configuration -----------------------------------------------------------

std::string read_key_file(const std::string& state_path, const std::string& name) {
    if (name.empty() || name.size() > 64 || name.front() == '.' || name.find('/') != name.npos) {
        throw std::runtime_error("apiKeyFile must be a plain file name inside the state directory");
    }
    const auto path = state_path + "/" + name;
    const Fd file(::open(path.c_str(), O_RDONLY | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK));
    if (!file) return {};
    struct stat info{};
    if (::fstat(file.get(), &info) < 0 || !S_ISREG(info.st_mode) || info.st_uid != ::geteuid()
        || (info.st_mode & 0077) != 0 || info.st_size > 4096) {
        throw std::runtime_error("STATE_DIR/" + name + " must be a regular file owned by the daemon user with mode 0600");
    }
    std::string key(static_cast<std::size_t>(info.st_size), '\0');
    if (::read(file.get(), key.data(), key.size()) != static_cast<ssize_t>(key.size())) {
        throw std::runtime_error("Could not read STATE_DIR/" + name);
    }
    while (!key.empty() && std::isspace(static_cast<unsigned char>(key.back()))) key.pop_back();
    return key;
}

std::string string_option(const Json& entry, const char* key, std::string fallback) {
    if (!entry.contains(key)) return fallback;
    if (!entry[key].is_string()) throw std::runtime_error(std::string("Provider field must be a string: ") + key);
    return entry[key].get<std::string>();
}

ProviderSettings parse_provider(const Json& entry, const std::string& state_path) {
    if (!entry.is_object()) throw std::runtime_error("Each agent provider must be an object");
    ProviderSettings settings;
    settings.kind = string_option(entry, "kind", "anthropic");
    const bool anthropic = settings.kind == "anthropic";
    if (!anthropic && settings.kind != "openai") throw std::runtime_error("Provider kind must be \"anthropic\" or \"openai\"");
    settings.id = string_option(entry, "id", settings.kind);
    if (settings.id.empty() || settings.id.size() > 48 || !std::all_of(settings.id.begin(), settings.id.end(), [](unsigned char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
    })) throw std::runtime_error("Provider IDs must be 1–48 lowercase letters, digits, underscores, or hyphens");
    settings.base_url = string_option(entry, "baseUrl", anthropic ? "https://api.anthropic.com" : "https://api.openai.com/v1");
    parse_endpoint(settings.base_url);
    settings.model = string_option(entry, "model", anthropic ? "claude-opus-5-5" : "");
    if (settings.model.empty() || settings.model.size() > 200) throw std::runtime_error("Provider " + settings.id + " needs a model");
    settings.label = string_option(entry, "label", settings.model);
    if (settings.label.empty() || settings.label.size() > 80) throw std::runtime_error("Provider labels must be 1–80 bytes");
    settings.effort = string_option(entry, "effort", anthropic ? "high" : "");
    static const std::set<std::string> efforts{"", "minimal", "low", "medium", "high", "xhigh", "max"};
    if (!efforts.contains(settings.effort)) throw std::runtime_error("Provider " + settings.id + " has an unknown effort level");
    settings.max_tokens = entry.value("maxTokens", 0);
    if (settings.max_tokens != 0 && (settings.max_tokens < 1024 || settings.max_tokens > 200000)) {
        throw std::runtime_error("maxTokens must be 1024–200000");
    }
    settings.fallbacks = anthropic && entry.value("fallbacks", true);
    settings.stream_usage = entry.value("streamUsage", true);
    settings.extra_body = entry.value("extraBody", Json::object());
    if (!settings.extra_body.is_object()) throw std::runtime_error("extraBody must be an object");
    const auto headers = entry.value("extraHeaders", Json::object());
    if (!headers.is_object()) throw std::runtime_error("extraHeaders must be an object");
    for (const auto& [key, value] : headers.items()) {
        if (!value.is_string()) throw std::runtime_error("extraHeaders values must be strings");
        settings.extra_headers.emplace_back(key, value.get<std::string>());
    }

    if (entry.value("enabled", true) == false) {
        settings.disabled_reason = "Turned off in config.json.";
        return settings;
    }
    const auto key_file = string_option(entry, "apiKeyFile", anthropic ? "anthropic-key" : "");
    if (!key_file.empty()) settings.api_key = read_key_file(state_path, key_file);
    const auto key_env = string_option(entry, "apiKeyEnv", anthropic ? "ANTHROPIC_API_KEY" : "OPENAI_API_KEY");
    if (settings.api_key.empty() && !key_env.empty()) {
        if (const char* value = std::getenv(key_env.c_str())) settings.api_key = value;
    }
    if (std::any_of(settings.api_key.begin(), settings.api_key.end(), [](unsigned char c) { return c < 33 || c > 126; })) {
        throw std::runtime_error("The API key for " + settings.id + " contains unexpected characters");
    }
    // OpenAI-compatible local servers often need no key; hosted ones answer 401.
    if (anthropic && settings.api_key.empty()) {
        settings.disabled_reason = "Add an API key to STATE_DIR/" + (key_file.empty() ? std::string("anthropic-key") : key_file)
            + " (mode 0600) or set " + key_env + ", then restart the daemon.";
    }
    return settings;
}

} // namespace

Json ProviderSettings::describe() const {
    Json result = {{"id", id}, {"label", label}, {"kind", kind}, {"model", model}, {"enabled", enabled()}};
    if (!enabled()) result["reason"] = disabled_reason;
    return result;
}

const Provider* ProviderSet::find(std::string_view id) const {
    for (const auto& provider : providers) if (provider->settings().id == id) return provider.get();
    return nullptr;
}

Json ProviderSet::catalog() const {
    Json list = Json::array();
    for (const auto& provider : providers) list.push_back(provider->settings().describe());
    return {{"providers", std::move(list)}, {"default", default_id.empty() ? Json(nullptr) : Json(default_id)}};
}

ProviderSet load_providers(const Json& config, const std::string& state_path) {
    ProviderSet set;
    const auto agent = config.value("agent", Json::object());
    if (!agent.is_object()) throw std::runtime_error("agent must be an object");
    auto entries = agent.value("providers", Json::array());
    if (!entries.is_array() || entries.size() > 32) throw std::runtime_error("agent.providers must be an array of up to 32 providers");
    // With no list, offer Claude through the Anthropic API.
    if (entries.empty()) entries.push_back({{"id", "claude"}, {"kind", "anthropic"}, {"label", "Claude Opus 5.5"}});
    std::set<std::string> ids;
    for (const auto& entry : entries) {
        auto settings = parse_provider(entry, state_path);
        if (!ids.insert(settings.id).second) throw std::runtime_error("Duplicate provider ID: " + settings.id);
        if (settings.kind == "anthropic") set.providers.push_back(std::make_shared<AnthropicProvider>(std::move(settings)));
        else set.providers.push_back(std::make_shared<OpenAIProvider>(std::move(settings)));
    }
    set.default_id = agent.value("default", std::string{});
    if (!set.default_id.empty() && !set.find(set.default_id)) throw std::runtime_error("agent.default names an unknown provider");
    if (set.default_id.empty()) {
        for (const auto& provider : set.providers) {
            if (provider->settings().enabled()) {
                set.default_id = provider->settings().id;
                break;
            }
        }
    }
    return set;
}

} // namespace tsun
