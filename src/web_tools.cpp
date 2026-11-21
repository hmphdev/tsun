#include "web_tools.hpp"

#include <httplib.h>
#include <libxml/HTMLparser.h>

#include <arpa/inet.h>
#include <netdb.h>

#include <algorithm>
#include <cctype>
#include <cstdlib>

namespace tsun {
namespace {

constexpr std::size_t body_limit = 512 * 1024;
constexpr std::size_t text_limit_bytes = 16 * 1024;

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

Body get(const WebUrl& url, httplib::Headers headers, bool search, const std::atomic<bool>& cancel) {
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
    Body body;
    int status = 0;
    bool supported = true;
    const auto reply = client.Get(url.path, headers,
        [&](const httplib::Response& response) {
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
        }, [&](const char* bytes, std::size_t size) {
            if (cancel) return false;
            const auto count = std::min(size, body_limit - body.text.size());
            body.text.append(bytes, count);
            body.truncated = count < size;
            return !body.truncated;
        });
    if (cancel) throw Error(400, "Interrupted");
    if (status >= 300 && status < 400) throw Error(400, "Redirects are disabled. Provide the final public URL.");
    if (status != 0 && status != 200) throw Error(400, "Web request returned HTTP " + std::to_string(status));
    if (!supported) throw Error(400, "The server did not return a supported text response");
    if (!reply && !body.truncated) throw Error(400, "Web request failed: " + httplib::to_string(reply.error()));
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
    auto source = get(parse_url(url), {{"Accept", "text/html, text/plain, text/markdown"}}, false, cancel);
    const bool html = source.type.starts_with("text/html") || source.type.starts_with("application/xhtml+xml");
    auto text = html ? html_text(source.text) : std::move(source.text);
    if (text.find_first_not_of(" \t\n\r") == text.npos) return {"No readable page text returned.", true};
    if (text.size() > text_limit_bytes) text = bounded(std::move(text), text_limit_bytes);
    else if (source.truncated) text += "\n[Truncated]";
    return {"Source: " + std::string(url) + "\n\n" + text, false};
}

ToolOutcome web_search(std::string_view query, const std::atomic<bool>& cancel) {
    if (query.size() > 400 || query.find_first_not_of(" \t\n\r") == query.npos || query.find('\0') != query.npos) {
        return {"Search query must be non-blank and at most 400 bytes.", true};
    }
    const auto* value = std::getenv("BRAVE_SEARCH_API_KEY");
    if (!value || !*value) return {"Set BRAVE_SEARCH_API_KEY in the daemon environment and restart it to enable web search.", true};
    const std::string key(value);
    if (key.size() > 512 || std::any_of(key.begin(), key.end(), [](unsigned char c) { return c <= 32 || c >= 127; })) {
        return {"BRAVE_SEARCH_API_KEY contains invalid characters.", true};
    }
    const WebUrl url{true, "api.search.brave.com", "/res/v1/web/search?count=6&q=" + httplib::encode_query_component(std::string(query))};
    const auto body = get(url, {{"Accept", "application/json"}, {"X-Subscription-Token", key}}, true, cancel);
    const auto parsed = Json::parse(body.text, nullptr, false);
    if (!parsed.is_object()) return {"The search provider returned invalid JSON.", true};
    const auto web = parsed.find("web");
    if (web == parsed.end() || !web->is_object() || !web->contains("results") || !(*web)["results"].is_array()) {
        return {"No web results found.", false};
    }
    std::string result;
    const auto& entries = (*web)["results"];
    for (std::size_t i = 0; i < std::min<std::size_t>(entries.size(), 6); ++i) {
        const auto& entry = entries[i];
        if (!entry.is_object()) continue;
        result += bounded(entry.value("title", ""), 320) + "\n" + bounded(entry.value("url", ""), 2048) + "\n" +
            bounded(entry.value("description", ""), 1000) + "\n\n";
    }
    return {result.empty() ? "No web results found." : bounded(std::move(result), text_limit_bytes), false};
}

} // namespace tsun
