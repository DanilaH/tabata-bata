package ru.tabatabata

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal interface ReviewLedger {
    suspend fun read(): ReviewState
    suspend fun claim(now: Long): Boolean
    suspend fun reviewed()
    fun lastExternalAt(): Long
}
internal interface ReviewGateway {
    val available: Boolean
    suspend fun prepare(): Any
    suspend fun launch(token: Any)
    fun isAlreadyReviewed(error: Throwable): Boolean
}
internal enum class ReviewPhase { IDLE, PREPARING, LAUNCHED }

/** A return action owns one request; late SDK responses never acquire a new opportunity. */
internal class ReviewCoordinator(
    private val scope: CoroutineScope,
    private val ledger: ReviewLedger,
    private val gateway: ReviewGateway,
    private val experience: suspend (String) -> ReviewExperience,
    private val allowed: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val event: (String) -> Unit = {},
) {
    private val mutablePhase = MutableStateFlow(ReviewPhase.IDLE)
    val phase = mutablePhase.asStateFlow()
    private var inspection: Job? = null
    private var request: Job? = null
    private var candidate: String? = null
    private var candidateGeneration = 0L
    private var generation = 0L
    private var closed = false

    fun inspect(session: String?) {
        inspection?.cancel()
        candidate = null
        val ticket = ++candidateGeneration
        if (closed || session == null || !gateway.available) return
        inspection = scope.launch {
            try {
                val e = experience(session)
                val state = ledger.read()
                if (ticket == candidateGeneration && !closed &&
                    ReviewPolicy.eligible(e, state, now(), ledger.lastExternalAt())) candidate = session
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Optional service cannot affect results or saving. */ }
        }
    }

    /** True reserves this opportunity for review; no delayed interstitial fallback. */
    fun requestOnReturn(session: String): Boolean {
        if (closed || candidate != session || mutablePhase.value != ReviewPhase.IDLE || !gateway.available)
            return false
        candidate = null
        val ticket = ++generation
        mutablePhase.value = ReviewPhase.PREPARING
        request = scope.launch {
            try {
                val token = withTimeout(ReviewPolicy.PREPARE_TIMEOUT) {
                    if (!ledger.claim(now())) return@withTimeout null
                    event("attempt")
                    gateway.prepare()
                } ?: return@launch
                ensureActive()
                if (closed || ticket != generation || !allowed()) return@launch
                mutablePhase.value = ReviewPhase.LAUNCHED
                event("launch_attempt")
                // No timeout after native handoff. Only the SDK/user ends its own form.
                gateway.launch(token)
                event("flow_finished") // Does not mean stars/text were submitted.
            } catch (_: TimeoutCancellationException) { event("prepare_timeout") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (gateway.isAlreadyReviewed(e)) { runCatching { ledger.reviewed() }; event("already_reviewed") }
                else event("unavailable")
            } finally {
                if (ticket == generation) mutablePhase.value = ReviewPhase.IDLE
            }
        }
        return true
    }

    fun cancelPreparation() {
        if (mutablePhase.value != ReviewPhase.PREPARING) return
        generation++
        request?.cancel()
        request = null
        mutablePhase.value = ReviewPhase.IDLE
    }

    fun close() {
        closed = true
        candidateGeneration++
        inspection?.cancel()
        generation++
        request?.cancel()
        mutablePhase.value = ReviewPhase.IDLE
    }
}
