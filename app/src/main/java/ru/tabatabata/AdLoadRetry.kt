package ru.tabatabata

/** One request at a time; retries prefetch only, never show or navigate. */
internal class AdLoadRetry(
    private val schedule: (Long, () -> Unit) -> Unit,
    private val cancel: () -> Unit,
    private val reload: () -> Unit,
) {
    private var loading = false
    private var waiting = false
    private var closed = false
    private var failures = 0

    fun begin(): Boolean {
        if (closed || loading || waiting) return false
        loading = true
        return true
    }

    fun loaded() {
        if (closed) return
        loading = false
        failures = 0
    }

    fun failed() {
        if (closed || !loading) return
        loading = false
        waiting = true
        val delay = (5000L shl failures.coerceAtMost(6)).coerceAtMost(300000L)
        failures = (failures + 1).coerceAtMost(6)
        schedule(delay) {
            waiting = false
            if (!closed) reload()
        }
    }

    fun close() {
        closed = true
        loading = false
        waiting = false
        cancel()
    }
}
