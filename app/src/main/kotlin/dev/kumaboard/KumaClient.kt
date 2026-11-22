package dev.kumaboard

import dev.kumaboard.core.Board
import dev.kumaboard.core.BoardConfig
import dev.kumaboard.core.KumaParser
import dev.kumaboard.core.UsageParser
import dev.kumaboard.core.UsageReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Reads the two public status page endpoints (F1). No credentials, no cookies, no third-party
 * hosts (§9). Short timeouts keep a slow network from ever blocking the board (F2).
 */
class KumaClient {
    class FetchException(message: String, cause: Throwable? = null) : IOException(message, cause)

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(false) // never downgrade https -> http via a redirect
        .retryOnConnectionFailure(true)
        .build()

    suspend fun fetch(config: BoardConfig): Board = coroutineScope {
        val page = async { get(config.statusPageApiUrl()) }
        val beats = async { get(config.heartbeatApiUrl()) }
        try {
            KumaParser.combine(KumaParser.parseStatusPage(page.await()), KumaParser.parseHeartbeats(beats.await()))
        } catch (e: KumaParser.ParseException) {
            throw FetchException("Unexpected response — is this a Kuma 2.x status page?", e)
        }
    }

    /** The weekly Claude/Codex usage file (numbers only; see [UsageParser]). Same rules: no credentials, no cookies. */
    suspend fun fetchUsage(url: String): UsageReport {
        val body = get(url, UsageParser.MAX_BYTES)
        return try {
            UsageParser.parse(body)
        } catch (e: UsageParser.ParseException) {
            throw FetchException(e.message ?: "Unexpected usage file", e)
        }
    }

    private suspend fun get(url: String, maxBytes: Long = MAX_BYTES): String = runInterruptible(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "KumaBoard/${BuildConfig.VERSION_NAME}")
            .build()
        try {
            http.newCall(request).execute().use { r ->
                if (r.code == 404) throw FetchException("Status page not found (HTTP 404) — check the slug")
                if (!r.isSuccessful) throw FetchException("Server answered HTTP ${r.code}")
                val body = r.body ?: throw FetchException("Empty response")
                if (body.contentLength() > maxBytes) throw FetchException("Response too large")
                body.source().let { src ->
                    src.request(maxBytes + 1)
                    if (src.buffer.size > maxBytes) throw FetchException("Response too large")
                }
                body.string()
            }
        } catch (e: FetchException) {
            throw e
        } catch (e: UnknownHostException) {
            throw FetchException("Can't resolve ${request.url.host} — is Tailscale/Wi-Fi up?", e)
        } catch (e: SocketTimeoutException) {
            throw FetchException("Timed out reaching ${request.url.host}", e)
        } catch (e: SSLException) {
            throw FetchException("TLS error talking to ${request.url.host}", e)
        } catch (e: IOException) {
            throw FetchException("Can't reach ${request.url.host}", e)
        }
    }

    private companion object {
        const val MAX_BYTES = 4L * 1024 * 1024
    }
}
