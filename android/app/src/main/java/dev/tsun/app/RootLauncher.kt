package dev.tsun.app

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.util.concurrent.TimeUnit

internal class RootLaunchError(message: String, val retryable: Boolean = true) : IOException(message)

/** Opt-in launcher for the standard Termux chroot-distro Debian install. */
internal object RootLauncher {
    private const val prefix = "/data/data/com.termux/files/usr"
    private const val state = "/root/.local/state/tsun-demo/state"
    private const val binary = "/root/mobile-harness/build/mobile-harness"
    private const val login = "/system/bin/env -i HOME=/data/data/com.termux/files/home " +
        "PREFIX=$prefix PATH=$prefix/bin:/system/bin TERMUX_VERSION=tsun CD_FORCE_NO_COLORS=1 " +
        "$prefix/bin/chroot-distro login -u root -w /root debian --"

    suspend fun pair(): Pair<Pairing, Overview> {
        val pairing = readPairing()
        val api = DaemonApi(pairing)
        val overview = try { api.overview() }
        catch (_: ConnectException) { launchAndWait(api) }
        return pairing to overview
    }

    /** Only called after connection refusal, never after an auth or certificate error. */
    suspend fun start(pairing: Pairing, api: DaemonApi): Overview {
        val local = readPairing()
        if (local.port != pairing.port || local.fingerprint != pairing.fingerprint || local.key != pairing.key) {
            throw RootLaunchError("The Debian state does not match this pairing. Forget it and pair via root again.", retryable = false)
        }
        return launchAndWait(api)
    }

    private suspend fun readPairing(): Pairing {
        val output = asRoot("$login $binary --pairing-code $state")
        return output.lineSequence().mapNotNull { Pairing.parse(it) }.singleOrNull()
            ?: throw RootLaunchError("Could not read Tsun's pairing from the Debian chroot. Check the daemon and state paths.")
    }

    private suspend fun launchAndWait(api: DaemonApi): Overview {
        // Keep the chroot-distro login alive with its daemon. Detach it from the app's
        // su session and lock the state so two app opens cannot both launch it.
        val guest = "umask 077; exec $binary $state > $state/launcher.log 2>&1"
        val launch = "$login /usr/bin/flock -n -o $state/launcher.lock /bin/sh -c ${quote(guest)}"
        asRoot("/system/bin/setsid $launch </dev/null >/dev/null 2>&1 &")
        return withTimeoutOrNull(15_000L) { awaitOverview(api) }
            ?: throw RootLaunchError("Debian did not start Tsun. Check $state/launcher.log in Debian.")
    }

    private suspend fun awaitOverview(api: DaemonApi): Overview {
        while (true) {
            try { return api.overview() }
            catch (_: ConnectException) { delay(250) }
        }
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private suspend fun asRoot(command: String): String = runInterruptible(Dispatchers.IO) {
        // Only fixed launcher commands reach su. No intents, model output or user text are shell input.
        val checked = "[ \"\$(/system/bin/id -u)\" = 0 ] || exit 77; $command"
        // On KernelSU sucompat, su can exist only at exec time, so don't probe it with File.exists().
        val process = try {
            ProcessBuilder("/system/bin/sh", "-c", "exec /system/bin/su -c ${quote(checked)}")
                .redirectErrorStream(true).start()
        } catch (_: IOException) {
            throw RootLaunchError("Could not open the root launcher. Grant Tsun root in your root manager.")
        }
        try {
            process.outputStream.close()
            val deadline = SystemClock.elapsedRealtime() + 25_000L
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            process.inputStream.use { input ->
                do {
                    if (SystemClock.elapsedRealtime() >= deadline) {
                        throw RootLaunchError("Root startup timed out. Check Tsun's permission in your root manager.")
                    }
                    val available = input.available()
                    if (available > 0) {
                        val size = input.read(buffer, 0, minOf(buffer.size, available))
                        if (size <= 0) break
                        if (output.size() + size > 32 * 1024) throw RootLaunchError("The root launcher returned too much output.")
                        output.write(buffer, 0, size)
                    }
                } while (!process.waitFor(50, TimeUnit.MILLISECONDS) || input.available() > 0)
            }
            if (process.exitValue() != 0) {
                throw RootLaunchError("Root launcher failed. Grant Tsun root in your root manager and check that Debian has the built daemon and state directory.")
            }
            // The output may contain the pairing key. Never log it or put it in an error message.
            output.toString(Charsets.UTF_8.name())
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
