package dev.tsun.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.X509TrustManager

internal class Pairing private constructor(val port: Int, val key: String, val fingerprint: String) {
    companion object {
        private val hex = Regex("[a-f0-9]{64}")

        fun fromFields(port: Int, key: String, fingerprint: String): Pairing? =
            if (port in 1024..65535 && hex.matches(key) && hex.matches(fingerprint)) Pairing(port, key, fingerprint)
            else null

        fun parse(code: String): Pairing? {
            val parts = code.trim().split(':', limit = 5)
            if (parts.size != 4 || parts[0] != "tsun1") return null
            val port = parts[1].toIntOrNull() ?: return null
            return fromFields(port, parts[2], parts[3])
        }
    }
}

private class DaemonTrust(fingerprint: String) : X509TrustManager {
    private val pin = fingerprint.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("No daemon certificate")
        val received = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
        if (!MessageDigest.isEqual(received, pin)) throw CertificateException("Daemon certificate does not match pairing")
        leaf.checkValidity()
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        throw CertificateException("This trust manager is only for the pinned daemon")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

internal data class Preset(val id: String, val label: String)
internal data class Workspace(val id: String, val label: String, val worktrees: Boolean, val presets: List<Preset>)
internal data class FileScope(val workspace: String, val run: String? = null, val thread: String? = null)
internal data class Entry(val name: String, val directory: Boolean, val size: Long)
internal data class Listing(val entries: List<Entry>, val truncated: Boolean)
internal data class Document(val text: String, val revision: String)
internal data class Run(
    val id: String, val workspace: String, val label: String, val state: String, val exitCode: Int?,
    val branch: String?, val filesReady: Boolean,
) {
    val active get() = state == "preparing" || state == "running" || state == "stopping"
}
internal data class RunList(val items: List<Run>, val capacity: Int)
internal data class Transcript(val run: Run, val log: String, val truncated: Boolean)

// ---- Agent threads (protocol 3) ----------------------------------------------

internal data class ModelProvider(
    val id: String, val label: String, val kind: String, val model: String, val enabled: Boolean, val reason: String?,
)
internal data class Catalog(val workspaces: List<Workspace>, val providers: List<ModelProvider>, val defaultProvider: String?)

internal enum class Mode(val wire: String, val label: String, val hint: String) {
    Ask("ask", "Ask first", "Approve every edit and command"),
    Edits("edits", "Auto-edit", "Edits run; commands wait for you"),
    Full("full", "Full access", "Edits and commands run without asking");

    companion object {
        fun of(wire: String) = entries.firstOrNull { it.wire == wire } ?: Edits
    }
}

internal data class AgentThread(
    val id: String, val workspace: String, val title: String, val state: String, val mode: Mode,
    val provider: String, val providerLabel: String, val model: String, val branch: String?, val filesReady: Boolean,
    val runs: Int, val pendingCall: String?, val additions: Int, val deletions: Int, val filesChanged: Int,
    val inputTokens: Long, val outputTokens: Long, val error: String?, val updatedAt: Long,
) {
    val active get() = state != "idle"
}
internal data class ThreadList(val items: List<AgentThread>, val capacity: Int)
internal data class FileStat(val path: String, val additions: Int, val deletions: Int, val binary: Boolean)

internal sealed interface ThreadItem {
    val id: String
    val run: Int

    data class User(override val id: String, override val run: Int, val text: String) : ThreadItem
    data class Text(override val id: String, override val run: Int, val text: String, val streaming: Boolean) : ThreadItem
    data class Thinking(override val id: String, override val run: Int, val text: String, val streaming: Boolean) : ThreadItem
    data class Tool(
        override val id: String, override val run: Int, val name: String, val title: String, val detail: String,
        val status: String, val output: String?, val exitCode: Int?, val before: String?, val after: String?,
        val created: Boolean,
    ) : ThreadItem {
        val risky get() = name == "bash" || name == "write_file" || name == "edit_file"
    }
    data class Note(override val id: String, override val run: Int, val text: String, val error: Boolean) : ThreadItem
    data class RunEnd(
        override val id: String, override val run: Int, val durationMs: Long, val additions: Int, val deletions: Int,
        val files: List<FileStat>, val stopped: Boolean,
    ) : ThreadItem
}
internal data class ThreadPage(val thread: AgentThread, val rev: Long, val reset: Boolean, val items: List<ThreadItem>)
internal data class Review(val diff: String, val truncated: Boolean, val files: List<FileStat>, val additions: Int, val deletions: Int)

internal class ApiError(val status: Int, message: String) : IOException(message)

internal fun Exception.userMessage(): String = when (this) {
    is CancellationException -> throw this
    is ApiError -> message ?: "The daemon rejected this request."
    is ConnectException -> "Can't reach the daemon. Is it running in Debian?"
    is SocketTimeoutException -> "The local daemon didn't respond in time."
    is SSLException -> "Couldn't verify the Debian daemon. Check that it's running with the certificate you paired with."
    is IOException -> message ?: "Couldn't complete the connection or file operation."
    else -> "Couldn't read the daemon's response."
}

internal class DaemonApi(private val pairing: Pairing) {
    // Keep the platform hostname check for 127.0.0.1. Only trust this paired certificate,
    // rather than the system CAs or another local process that happens to use the port.
    private val socketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(DaemonTrust(pairing.fingerprint)), null) }.socketFactory
    }

    suspend fun catalog(): Catalog {
        val response = json("/v1/workspaces")
        if (response.getInt("protocol") != 3) throw ApiError(426, "Rebuild and restart the Tsun 0.3 daemon in Debian.")
        val workspaces = response.getJSONArray("workspaces").mapObjects { workspace ->
            Workspace(workspace.getString("id"), workspace.getString("label"), workspace.getBoolean("worktrees"),
                workspace.getJSONArray("commands").mapObjects {
                    Preset(it.getString("id"), it.getString("label"))
                })
        }
        val agent = response.getJSONObject("agent")
        val providers = agent.getJSONArray("providers").mapObjects {
            ModelProvider(it.getString("id"), it.getString("label"), it.getString("kind"), it.getString("model"),
                it.getBoolean("enabled"), it.optStringOrNull("reason"))
        }
        return Catalog(workspaces, providers, agent.optStringOrNull("default"))
    }

    suspend fun threads(): ThreadList {
        val response = json("/v1/threads")
        return ThreadList(response.getJSONArray("threads").mapObjects { it.thread() }, response.getInt("capacity"))
    }

    /** Long-polls for up to [waitMs] when nothing changed since [since]. */
    suspend fun thread(id: String, since: Long, waitMs: Int): ThreadPage {
        val response = json("/v1/threads/${encode(id)}?since=$since&wait=$waitMs")
        return ThreadPage(response.getJSONObject("thread").thread(), response.getLong("rev"),
            response.getBoolean("reset"), response.getJSONArray("items").mapObjects { it.threadItem() }.filterNotNull())
    }

    suspend fun startThread(workspace: String, prompt: String, mode: Mode, provider: String?): AgentThread =
        post("/v1/threads", JSONObject().put("workspace", workspace).put("prompt", prompt).put("mode", mode.wire)
            .apply { if (provider != null) put("provider", provider) }).thread()

    suspend fun reply(id: String, text: String, mode: Mode, provider: String?): AgentThread =
        post("/v1/threads/${encode(id)}/messages", JSONObject().put("text", text).put("mode", mode.wire)
            .apply { if (provider != null) put("provider", provider) }).thread()

    suspend fun setMode(id: String, mode: Mode): AgentThread =
        post("/v1/threads/${encode(id)}/settings", JSONObject().put("mode", mode.wire)).thread()

    suspend fun interrupt(id: String): AgentThread = post("/v1/threads/${encode(id)}/interrupt", JSONObject()).thread()

    suspend fun decide(id: String, call: String, allow: Boolean): AgentThread =
        post("/v1/threads/${encode(id)}/approval", JSONObject().put("call", call)
            .put("decision", if (allow) "allow" else "deny")).thread()

    suspend fun review(id: String): Review {
        val response = json("/v1/threads/${encode(id)}/diff", timeout = 30_000)
        return Review(response.getString("diff"), response.optBoolean("truncated"),
            response.getJSONArray("files").mapObjects { it.fileStat() },
            response.optInt("additions"), response.optInt("deletions"))
    }

    private suspend fun post(path: String, body: JSONObject): JSONObject =
        json(path, "POST", body.toString().toByteArray(Charsets.UTF_8), "application/json")

    suspend fun entries(scope: FileScope, path: String): Listing {
        val response = json(fileRoute("entries", scope, path))
        return Listing(response.getJSONArray("entries").mapObjects {
            Entry(it.getString("name"), it.getBoolean("directory"), it.getLong("size"))
        }, response.getBoolean("truncated"))
    }

    suspend fun document(scope: FileScope, path: String): Document {
        val response = json(fileRoute("file", scope, path))
        return Document(response.getString("text"), response.getString("revision"))
    }

    suspend fun save(scope: FileScope, path: String, text: String, revision: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > 1024 * 1024) throw ApiError(413, "Text editor limit is 1 MiB.")
        return json(fileRoute("file", scope, path), "PUT", bytes, "text/plain; charset=utf-8", revision)
            .getString("revision")
    }

    suspend fun image(scope: FileScope, path: String): ByteArray =
        request(fileRoute("download", scope, path)) { input, _ ->
            input.readBounded(12 * 1024 * 1024, "This image is too large to preview. You can still export it.")
        }

    suspend fun export(scope: FileScope, path: String, openOutput: () -> OutputStream) {
        request(fileRoute("download", scope, path)) { input, length ->
            if (length !in 0L..(64L * 1024 * 1024)) throw IOException("Invalid artifact size.")
            openOutput().use { output ->
                val buffer = ByteArray(32 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (n < 0) throw IOException("Export was interrupted. The copy may be incomplete.")
                    output.write(buffer, 0, n)
                    remaining -= n
                }
            }
        }
    }

    suspend fun runs(): RunList {
        val response = json("/v1/jobs")
        return RunList(response.getJSONArray("jobs").mapObjects { it.run() }, response.getInt("capacity"))
    }

    suspend fun transcript(id: String): Transcript {
        val response = json("/v1/jobs/${encode(id)}")
        return Transcript(response.run(), response.getString("log"), response.getBoolean("truncated"))
    }

    suspend fun launch(workspace: String, preset: String, title: String): Run = json("/v1/jobs", "POST",
        JSONObject().put("workspace", workspace).put("command", preset).put("title", title).toString().toByteArray(),
        "application/json").run()

    suspend fun stop(id: String): Run = json("/v1/jobs/${encode(id)}/stop", "POST").run()

    private suspend fun json(
        path: String, method: String = "GET", body: ByteArray? = null,
        contentType: String? = null, revision: String? = null, timeout: Int = 5000,
    ): JSONObject = request(path, method, body, contentType, revision, timeout) { input, _ ->
        // A 1 MiB UTF-8 file can be up to 6 MiB after JSON escaping control characters.
        JSONObject(input.readBounded(8 * 1024 * 1024, "Response is too large.").toString(Charsets.UTF_8))
    }

    private suspend fun <T> request(
        path: String, method: String = "GET", body: ByteArray? = null,
        contentType: String? = null, revision: String? = null, timeout: Int = 5000,
        consume: (InputStream, Long) -> T,
    ): T = withContext(Dispatchers.IO) {
        val connection = URL("https://127.0.0.1:${pairing.port}$path")
            .openConnection(Proxy.NO_PROXY) as HttpsURLConnection
        try {
            connection.sslSocketFactory = socketFactory
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 1800
            connection.readTimeout = timeout
            connection.setRequestProperty("Authorization", "Bearer ${pairing.key}")
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (revision != null) connection.setRequestProperty("If-Match", revision)
            if (body != null || method == "POST") {
                val data = body ?: byteArrayOf()
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType ?: "application/json")
                connection.setFixedLengthStreamingMode(data.size)
                connection.outputStream.use { it.write(data) }
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val error = connection.errorStream?.use { stream ->
                    runCatching { JSONObject(stream.readBounded(8192, "Error response too large.")
                        .toString(Charsets.UTF_8)).getString("error") }.getOrNull()
                }
                throw ApiError(status, error ?: "The daemon returned HTTP $status.")
            }
            connection.inputStream.use { consume(it, connection.contentLengthLong) }
        } finally {
            connection.disconnect()
        }
    }
}

private fun fileRoute(endpoint: String, scope: FileScope, path: String): String =
    "/v1/$endpoint?workspace=${encode(scope.workspace)}&path=${encode(path)}" +
        (scope.run?.let { "&run=${encode(it)}" } ?: "") + (scope.thread?.let { "&thread=${encode(it)}" } ?: "")

private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

private fun JSONObject.run(): Run = Run(getString("id"), getString("workspace"), getString("label"),
    getString("state"), if (isNull("exitCode")) null else getInt("exitCode"),
    if (isNull("branch")) null else getString("branch"), getBoolean("filesReady"))

private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key) || !has(key)) null else getString(key)

private fun JSONObject.thread(): AgentThread = AgentThread(
    id = getString("id"), workspace = getString("workspace"), title = getString("title"), state = getString("state"),
    mode = Mode.of(getString("mode")), provider = getString("provider"), providerLabel = getString("providerLabel"),
    model = getString("model"), branch = optStringOrNull("branch"), filesReady = getBoolean("filesReady"),
    runs = getInt("runs"), pendingCall = optStringOrNull("pendingCall"), additions = getInt("additions"),
    deletions = getInt("deletions"), filesChanged = getInt("filesChanged"), inputTokens = getLong("inputTokens"),
    outputTokens = getLong("outputTokens"), error = optStringOrNull("error"), updatedAt = getLong("updatedAt"),
)

private fun JSONObject.fileStat() = FileStat(getString("path"), getInt("additions"), getInt("deletions"), optBoolean("binary"))

private fun JSONObject.threadItem(): ThreadItem? {
    val id = getString("id")
    val run = optInt("run")
    return when (getString("kind")) {
        "user" -> ThreadItem.User(id, run, getString("text"))
        "text" -> ThreadItem.Text(id, run, getString("text"), optBoolean("streaming"))
        "thinking" -> ThreadItem.Thinking(id, run, getString("text"), optBoolean("streaming"))
        "tool" -> ThreadItem.Tool(id, run, optString("name"), optString("title"), optString("detail"),
            optString("status"), optStringOrNull("output"), if (has("exitCode") && !isNull("exitCode")) getInt("exitCode") else null,
            optStringOrNull("before"), optStringOrNull("after"), optBoolean("created"))
        "notice" -> ThreadItem.Note(id, run, getString("text"), optString("tone") == "error")
        "run" -> ThreadItem.RunEnd(id, run, optLong("duration"), optInt("additions"), optInt("deletions"),
            optJSONArray("files")?.mapObjects { it.fileStat() }.orEmpty(), optBoolean("stopped"))
        else -> null // Newer daemons may add kinds; skip what this app doesn't know.
    }
}

private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    List(length()) { transform(getJSONObject(it)) }

private fun InputStream.readBounded(limit: Int, error: String): ByteArray {
    val result = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer)
        if (n < 0) return result.toByteArray()
        if (n > limit - result.size()) throw IOException(error)
        result.write(buffer, 0, n)
    }
}
