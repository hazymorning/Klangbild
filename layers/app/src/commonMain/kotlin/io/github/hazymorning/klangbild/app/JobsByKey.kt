package io.github.hazymorning.klangbild.app

import kotlinx.coroutines.Job

/**
 * What is being made, by key, one job for each key at most ([Scenes], [Thumbs]). Called under the
 * lock of the one that keeps it.
 */
internal class JobsByKey {
    private val jobs = HashMap<String, Job>()

    /** Whether a job runs for [key]. */
    operator fun contains(key: String): Boolean = key in jobs

    /** [job] runs for [key] from now on. */
    operator fun set(key: String, job: Job) {
        jobs[key] = job
    }

    /** [job] has ended: only its own place goes, since another job may run for [key] by now. */
    fun ended(key: String, job: Job?) {
        if (jobs[key] === job) jobs.remove(key)
    }

    /** Cancels the jobs of every key but [keys]. */
    fun retain(keys: Set<String>) {
        val gone = jobs.keys.filter { it !in keys }
        for (key in gone) jobs.remove(key)?.cancel()
    }

    /** Cancels every job. */
    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }
}
