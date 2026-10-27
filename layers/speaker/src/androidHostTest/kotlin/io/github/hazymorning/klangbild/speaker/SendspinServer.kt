package io.github.hazymorning.klangbild.speaker

import org.junit.Assume.assumeTrue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The reference server of the end-to-end tests, run with [args]: aiosendspin 10.2.0, the server
 * Music Assistant 2.10.7 runs, as ci/sendspin_server.py starts it on this machine. Its events
 * come line by line.
 */
class SendspinServer(vararg args: String) : AutoCloseable {
    private val process = ProcessBuilder(listOf(PYTHON!!, "-I", SCRIPT!!) + args)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()
    private val lines = LinkedBlockingQueue<String>()

    /** The events so far. */
    val all = CopyOnWriteArrayList<String>()

    init {
        Thread {
            process.inputStream.bufferedReader().forEachLine { lines += it }
        }.apply { isDaemon = true }.start()
    }

    /** The next event named [name], waiting at most [seconds]; earlier ones are kept in [all]. */
    fun await(name: String, seconds: Long = 30): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (true) {
            val left = deadline - System.nanoTime()
            val line = lines.poll(left.coerceAtLeast(0), TimeUnit.NANOSECONDS)
                ?: throw AssertionError("no $name within $seconds s; events: $all")
            all += line
            if (line.startsWith("ERROR")) throw AssertionError("server: $line")
            if (line.split(' ').first() == name) return line
        }
    }

    /** The server's Sendspin URL, once it is ready. */
    fun url(): String = "ws://127.0.0.1:${field(await("READY"), "port")}/sendspin"

    override fun close() {
        process.destroy()
        process.waitFor(5, TimeUnit.SECONDS)
    }

    companion object {
        /** A Python with aiosendspin 10.2.0: `-Pklangbild.sendspin=/path/to/venv/bin/python`. */
        private val PYTHON: String? = System.getProperty("klangbild.sendspin")

        /** ci/sendspin_server.py, as the build names it. */
        private val SCRIPT: String? = System.getProperty("klangbild.sendspinServer")

        /** Skips the test without a Python that has aiosendspin. */
        fun assumeAvailable() = assumeTrue("needs -Pklangbild.sendspin=<python with aiosendspin 10.2.0>", PYTHON != null)

        /** The value of [key] in an event's [line]. */
        fun field(line: String, key: String): String = line.split(' ').first { it.startsWith("$key=") }.substringAfter('=')
    }
}
