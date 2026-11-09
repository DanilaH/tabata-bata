package ru.tabatabata

/** Three requests per burst. A later eligible action reopens it after cooldown.
 * Disabling cancels scheduled work, not an unresolved SDK request. Tokens reject duplicate callbacks.
 */
internal class AdLoadRetry(
    private val schedule: (Long, () -> Unit) -> Unit,
    private val cancel: () -> Unit,
    private val reload: () -> Unit,
    private val now: () -> Long,
) {
    private var enabled = false
    private var closed = false
    private var loading: Int? = null
    private var generation = 0
    private var scheduledGeneration = 0
    private var waiting = false
    private var failures = 0
    private var nextAt = 0L
    val attempt: Int get() = failures + 1

    fun setEnabled(value: Boolean) {
        if (closed) return
        enabled = value
        if (!value) {
            scheduledGeneration++
            waiting = false
            cancel()
        } else opportunity()
    }

    fun opportunity() {
        if (closed || !enabled || loading != null || waiting) return
        if (failures >= 3) {
            if (now() < nextAt) return
            failures = 0
        }
        val delay = nextAt - now()
        if (delay > 0) waitFor(delay) else reload()
    }

    fun begin(): Int? {
        if (closed || !enabled || loading != null || waiting || failures >= 3 || now() < nextAt) return null
        return (++generation).also { loading = it }
    }

    fun loaded(token: Int): Boolean {
        if (closed || loading != token) return false
        loading = null
        failures = 0
        nextAt = 0
        return true
    }

    fun failed(token: Int): Boolean {
        if (closed || loading != token) return false
        loading = null
        failures++
        val delay = when (failures) { 1 -> 15_000L; 2 -> 60_000L; else -> 300_000L }
        nextAt = now() + delay
        if (enabled && failures < 3) waitFor(delay)
        return true
    }

    private fun waitFor(delay: Long) {
        waiting = true
        val ticket = ++scheduledGeneration
        schedule(delay) {
            if (!closed && enabled && ticket == scheduledGeneration) {
                waiting = false
                reload()
            }
        }
    }

    fun close() {
        closed = true
        enabled = false
        loading = null
        scheduledGeneration++
        cancel()
    }
}
