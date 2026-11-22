#include "web_tools.hpp"

#include <httplib.h>
#include <libxml/HTMLparser.h>

#include <arpa/inet.h>
#include <netdb.h>

#include <algorithm>
#include <array>
#include <cctype>
#include <cstdlib>

namespace tsun {
namespace {

constexpr std::size_t body_limit = 512 * 1024;
constexpr std::size_t text_limit_bytes = 16 * 1024;
constexpr std::size_t search_count = 6;

struct WebUrl {
    bool https;
    std::string host, path;
};

WebUrl parse_url(std::string_view url) {
    const bool https = url.starts_with("https://");
    if ((!https && !url.starts_with("http://")) || url.size() > 2048 ||
        std::any_of(url.begin(), url.end(), [](unsigned char c) { return c <= 32 || c >= 127 || c == '\\'; })) {
        throw Error(400, "Use a public HTTP or HTTPS URL of at most 2048 bytes");
    }
    url.remove_prefix(https ? 8 : 7);
    const auto end = url.find_first_of("/?#");
    const auto authority = url.substr(0, end);
    const auto colon = authority.find(':');
    const auto host = authority.substr(0, colon);
    if (host.empty() || host.size() > 253 || host.find('.') == host.npos || host.front() == '.' || host.back() == '.' ||
        host.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-.") != host.npos ||
        (colon != authority.npos && authority.substr(colon + 1) != (https ? "443" : "80"))) {
        throw Error(400, "Use a public hostname and the default HTTP(S) port");
    }
    auto path = end == url.npos ? std::string_view{} : url.substr(end);
    path = path.substr(0, path.find('#'));
    return {https, std::string(host), (path.starts_with('/') ? "" : "/") + std::string(path)};
}

bool public_ipv4(in_addr addr) {
    const auto ip = ntohl(addr.s_addr);
    const auto first = ip >> 24;
    const auto second = (ip >> 16) & 255;
    const auto third = (ip >> 8) & 255;
    if (first == 0 || first == 10 || first == 127 || first >= 224) return false;
    if (first == 100 && second >= 64 && second <= 127) return false;
    if (first == 169 && second == 254) return false;
    if (first == 172 && second >= 16 && second <= 31) return false;
    if (first == 192 && (second == 168 || (second == 0 && (third == 0 || third == 2)) ||
        (second == 88 && third == 99))) return false;
    if (first == 198 && (second == 18 || second == 19 || (second == 51 && third == 100))) return false;
    if (first == 203 && second == 0 && third == 113) return false;
    return true;
}

std::string public_address(const std::string& host) {
    addrinfo hints{};
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    addrinfo* raw = nullptr;
    if (::getaddrinfo(host.c_str(), nullptr, &hints, &raw) != 0) throw Error(400, "Could not resolve a public IPv4 address");
    const std::unique_ptr<addrinfo, decltype(&::freeaddrinfo)> records(raw, ::freeaddrinfo);
    for (auto* entry = records.get(); entry; entry = entry->ai_next) {
        if (!public_ipv4(reinterpret_cast<const sockaddr_in*>(entry->ai_addr)->sin_addr)) {
            throw Error(403, "Private and reserved network addresses are blocked");
        }
    }
    if (!records) throw Error(400, "Could not resolve a public IPv4 address");
    char address[INET_ADDRSTRLEN];
    if (!::inet_ntop(AF_INET, &reinterpret_cast<const sockaddr_in*>(records->ai_addr)->sin_addr, address, sizeof(address))) {
        throw Error(400, "Could not read the public IPv4 address");
    }
    return address;
}

struct Body {
    std::string text, type;
    bool truncated = false;
};

// Without a payload this is a GET; with one, a JSON POST.
Body request(const WebUrl& url, httplib::Headers headers, std::string_view payload, bool search,
             const std::atomic<bool>& cancel) {
    if (cancel) throw Error(400, "Interrupted");
    const auto address = public_address(url.host);
    if (cancel) throw Error(400, "Interrupted");
    httplib::Client client((url.https ? "https://" : "http://") + url.host);
    // Connect to the checked address, retaining the original Host, SNI and certificate checks.
    client.set_direct_hostname_addr_map({{url.host, address}});
    client.set_connection_timeout(5);
    client.set_read_timeout(5);
    client.set_max_timeout(15'000);
    client.set_follow_location(false);
    client.set_path_encode(false);
    if (url.https) client.set_ca_cert_path("/etc/ssl/certs/ca-certificates.crt");
    headers.emplace("Accept-Encoding", "identity");
    headers.emplace("User-Agent", "Tsun/0.3 web tools");
    if (!payload.empty()) headers.emplace("Content-Type", "application/json");
    Body body;
    int status = 0;
    bool supported = true;
    httplib::Request request;
    request.method = payload.empty() ? "GET" : "POST";
    request.path = url.path;
    request.headers = std::move(headers);
    request.body = std::string(payload);
    request.response_handler = [&](const httplib::Response& response) {
        status = response.status;
        if (status != 200 || cancel) return false;
        body.type = response.get_header_value("Content-Type");
        std::transform(body.type.begin(), body.type.end(), body.type.begin(), [](unsigned char c) {
            return static_cast<char>(std::tolower(c));
        });
        const auto encoding = response.get_header_value("Content-Encoding");
        supported = (encoding.empty() || encoding == "identity") && (search ? body.type.starts_with("application/json")
            : body.type.starts_with("text/") || body.type.starts_with("application/xhtml+xml"));
        return supported;
    };
    request.content_receiver = [&](const char* bytes, std::size_t size, std::size_t, std::size_t) {
        if (cancel) return false;
        const auto count = std::min(size, body_limit - body.text.size());
        body.text.append(bytes, count);
        body.truncated = count < size;
        return !body.truncated;
    };
    const auto reply = client.send(request);
    if (cancel) throw Error(400, "Interrupted");
    if (status >= 300 && status < 400) throw Error(400, "Redirects are disabled. Provide the final public URL.");
    // The provider's own status is kept so a rejected key or a rate limit can fall through to the next one.
    if (status != 0 && status != 200) throw Error(status, "Web request returned HTTP " + std::to_string(status));
    if (!supported) throw Error(502, "The server did not return a supported text response");
    if (!reply && !body.truncated) throw Error(502, "Web request failed: " + httplib::to_string(reply.error()));
    return body;
}

// Keep a few extra bytes so bounded() can detect a cut and preserve whole UTF-8 characters.
constexpr auto html_text_limit = text_limit_bytes + 4;

void append_text(xmlNode* node, std::string& text) {
    for (; node && text.size() < html_text_limit; node = node->next) {
        if (node->type == XML_TEXT_NODE && node->content) {
            const std::string_view chunk(reinterpret_cast<const char*>(node->content));
            text.append(chunk.substr(0, html_text_limit - text.size()));
        } else if (node->type == XML_ELEMENT_NODE) {
            const std::string_view tag(reinterpret_cast<const char*>(node->name));
            if (tag == "script" || tag == "style" || tag == "noscript" || tag == "template" ||
                tag == "svg" || tag == "head") continue;
            const bool block = tag == "p" || tag == "div" || tag == "li" || tag == "br" || tag == "pre" ||
                tag == "tr" || (tag.size() == 2 && tag.front() == 'h');
            if (block) text += '\n';
            append_text(node->children, text);
            if (block && text.size() < html_text_limit) text += '\n';
        }
    }
}

std::string html_text(const std::string& body) {
    const std::unique_ptr<xmlDoc, decltype(&::xmlFreeDoc)> doc(
        htmlReadMemory(body.data(), static_cast<int>(body.size()), nullptr, nullptr,
            HTML_PARSE_NONET | HTML_PARSE_NOERROR | HTML_PARSE_NOWARNING), ::xmlFreeDoc);
    std::string text;
    if (doc) append_text(xmlDocGetRootElement(doc.get()), text);
    return text;
}

std::string bounded(std::string text, std::size_t limit) {
    if (text.size() <= limit) return text;
    while (limit && (static_cast<unsigned char>(text[limit]) & 0xc0) == 0x80) --limit;
    text.resize(limit);
    return text + "\n[Truncated]";
}

} // namespace

ToolOutcome web_fetch(std::string_view url, const std::atomic<bool>& cancel) {
    auto source = request(parse_url(url), {{"Accept", "text/html, text/plain, text/markdown"}}, {}, false, cancel);
    const bool html = source.type.starts_with("text/html") || source.type.starts_with("application/xhtml+xml");
    auto text = html ? html_text(source.text) : std::move(source.text);
    if (text.find_first_not_of(" \t\n\r") == text.npos) return {"No readable page text returned.", true};
    if (text.size() > text_limit_bytes) text = bounded(std::move(text), text_limit_bytes);
    else if (source.truncated) text += "\n[Truncated]";
    return {"Source: " + std::string(url) + "\n\n" + text, false};
}

struct SearchHit {
    std::string title, url, snippet;
};

using SearchFn = std::vector<SearchHit> (*)(std::string_view key, std::string_view query,
                                            const std::atomic<bool>& cancel);

struct SearchProvider {
    std::string_view label;
    std::string_view variable;
    SearchFn search;
};

Json search_json(const Body& body) {
    auto parsed = Json::parse(body.text, nullptr, false);
    if (!parsed.is_object()) throw Error(502, "The search provider returned invalid JSON.");
    return parsed;
}

// A provider field that is present but not a string is treated as absent.
std::string field(const Json& entry, const char* name) {
    const auto value = entry.find(name);
    return value != entry.end() && value->is_string() ? value->get<std::string>() : std::string{};
}

std::vector<SearchHit> brave_search(std::string_view key, std::string_view query, const std::atomic<bool>& cancel) {
    const WebUrl url{true, "api.search.brave.com", "/res/v1/web/search?count=" + std::to_string(search_count) + "&q=" +
        httplib::encode_query_component(std::string(query))};
    const auto parsed = search_json(request(url,
        {{"Accept", "application/json"}, {"X-Subscription-Token", std::string(key)}}, {}, true, cancel));
    std::vector<SearchHit> hits;
    const auto web = parsed.find("web");
    if (web == parsed.end() || !web->is_object() || !web->contains("results") || !(*web)["results"].is_array()) return hits;
    for (const auto& entry : (*web)["results"]) {
        if (entry.is_object()) hits.push_back({field(entry, "title"), field(entry, "url"), field(entry, "description")});
    }
    return hits;
}

std::vector<SearchHit> tavily_search(std::string_view key, std::string_view query, const std::atomic<bool>& cancel) {
    const WebUrl url{true, "api.tavily.com", "/search"};
    const Json payload{{"query", std::string(query)}, {"max_results", search_count}, {"search_depth", "basic"}};
    const auto parsed = search_json(request(url,
        {{"Accept", "application/json"}, {"Authorization", "Bearer " + std::string(key)}}, payload.dump(), true, cancel));
    std::vector<SearchHit> hits;
    const auto results = parsed.find("results");
    if (results == parsed.end() || !results->is_array()) return hits;
    for (const auto& entry : *results) {
        if (entry.is_object()) hits.push_back({field(entry, "title"), field(entry, "url"), field(entry, "content")});
    }
    return hits;
}

std::vector<SearchHit> firecrawl_search(std::string_view key, std::string_view query, const std::atomic<bool>& cancel) {
    const WebUrl url{true, "api.firecrawl.dev", "/v2/search"};
    const Json payload{{"query", std::string(query)}, {"limit", search_count}};
    const auto parsed = search_json(request(url,
        {{"Accept", "application/json"}, {"Authorization", "Bearer " + std::string(key)}}, payload.dump(), true, cancel));
    std::vector<SearchHit> hits;
    const auto data = parsed.find("data");
    if (data == parsed.end()) return hits;
    // v2 groups results by source ({"web": [...]}); v1 returned a bare array.
    const Json* entries = data->is_array() ? &*data : nullptr;
    if (!entries && data->is_object()) {
        const auto web = data->find("web");
        if (web != data->end() && web->is_array()) entries = &*web;
    }
    if (!entries) return hits;
    for (const auto& entry : *entries) {
        if (!entry.is_object()) continue;
        auto snippet = field(entry, "description");
        if (snippet.empty()) snippet = field(entry, "content");
        hits.push_back({field(entry, "title"), field(entry, "url"), std::move(snippet)});
    }
    return hits;
}

constexpr std::array<SearchProvider, 3> search_providers{{
    {"Brave", "BRAVE_SEARCH_API_KEY", brave_search},
    {"Tavily", "TAVILY_API_KEY", tavily_search},
    {"Firecrawl", "FIRECRAWL_API_KEY", firecrawl_search},
}};

struct SearchKeys {
    std::vector<std::string> valid;
    bool invalid = false;
};

// A provider variable may list several keys separated by commas or newlines, and
// its plural alias is read too, so a rejected key can fall through to the next.
SearchKeys search_keys(std::string_view variable) {
    SearchKeys keys;
    const std::array<std::string, 2> names{std::string(variable), std::string(variable) + "S"};
    for (const auto& name : names) {
        const char* raw = std::getenv(name.c_str());
        if (!raw) continue;
        std::string_view value(raw);
        while (!value.empty()) {
            const auto end = value.find_first_of(",\r\n");
            const auto entry = value.substr(0, end);
            value = end == std::string_view::npos ? std::string_view{} : value.substr(end + 1);
            const auto first = entry.find_first_not_of(" \t");
            if (first == std::string_view::npos) continue;
            const auto key = entry.substr(first, entry.find_last_not_of(" \t") - first + 1);
            if (key.size() > 512 ||
                std::any_of(key.begin(), key.end(), [](unsigned char c) { return c < 33 || c > 126; })) {
                keys.invalid = true;
                continue;
            }
            if (std::find(keys.valid.begin(), keys.valid.end(), key) == keys.valid.end()) keys.valid.emplace_back(key);
        }
    }
    return keys;
}

// A rejected key, an exhausted quota or rate limit, or a provider outage: try the next key,
// then the next provider. Tavily reports plan and pay-as-you-go limits as 432, 433 and 434.
bool transient(int status) {
    return status == 401 || status == 402 || status == 403 || status == 408 || status == 425 || status == 429 ||
        (status >= 432 && status <= 434) || status >= 500;
}

ToolOutcome search_results(std::vector<SearchHit> hits) {
    std::string result;
    for (std::size_t i = 0; i < std::min(hits.size(), search_count); ++i) {
        const auto& hit = hits[i];
        if (hit.url.empty()) continue;
        result += bounded(hit.title, 320) + "\n" + bounded(hit.url, 2048) + "\n" + bounded(hit.snippet, 1000) + "\n\n";
    }
    return {result.empty() ? "No web results found." : bounded(std::move(result), text_limit_bytes), false};
}

ToolOutcome web_search(std::string_view query, const std::atomic<bool>& cancel) {
    if (query.size() > 400 || query.find_first_not_of(" \t\n\r") == query.npos || query.find('\0') != query.npos) {
        return {"Search query must be non-blank and at most 400 bytes.", true};
    }
    bool configured = false;
    std::string failures;
    for (const auto& provider : search_providers) {
        const auto keys = search_keys(provider.variable);
        if (!keys.invalid && keys.valid.empty()) continue;
        configured = true;
        std::string failure = keys.invalid ? std::string(provider.variable) + " contains invalid characters." : "";
        std::size_t tried = 0;
        for (const auto& key : keys.valid) {
            try {
                return search_results(provider.search(key, query, cancel));
            } catch (const Error& error) {
                if (cancel) throw;
                failure = error.what();
                ++tried;
                if (!transient(error.status)) break;
            }
        }
        if (failure.empty()) continue;
        if (tried > 1) failure += " (tried " + std::to_string(tried) + " keys)";
        failures += (failures.empty() ? "" : "; ") + std::string(provider.label) + ": " + failure;
    }
    if (!configured) {
        return {"Set BRAVE_SEARCH_API_KEY, TAVILY_API_KEY or FIRECRAWL_API_KEY in the daemon environment and restart "
                "it to enable web search.", true};
    }
    return {bounded("Web search failed. " + failures, 2000), true};
}

} // namespace tsun
