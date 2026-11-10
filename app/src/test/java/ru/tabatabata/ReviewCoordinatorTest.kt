package ru.tabatabata

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewCoordinatorTest {
    private class Ledger : ReviewLedger {
        var state = ReviewState()
        var failSave = false
        override suspend fun read() = state
        override suspend fun claim(now: Long): Boolean {
            if (failSave || !ReviewPolicy.canClaim(state,now)) return false
            state = ReviewState(state.attempts+1,now)
            return true
        }
        override suspend fun reviewed() { state = state.copy(alreadyReviewed=true) }
        override fun lastExternalAt() = 0L
    }
    private class Gateway : ReviewGateway {
        override var available = true
        var prepares = 0
        var launches = 0
        var error: Exception? = null
        val token = CompletableDeferred<Any>()
        val finish = CompletableDeferred<Unit>()
        override suspend fun prepare(): Any { prepares++; error?.let { throw it }; return token.await() }
        override suspend fun launch(token: Any) { launches++; finish.await() }
        override fun isAlreadyReviewed(error: Throwable) = error.message=="review-exists"
    }
    private val enough = ReviewExperience(3,2,600000,true)

    @Test fun nativeFlowOwnsItsLifetimeAndCloseDoesNotInventAReview() = runTest {
        val ledger=Ledger();val gateway=Gateway();val events=mutableListOf<String>()
        val c=ReviewCoordinator(this,ledger,gateway,{enough},{true},{1000},events::add)
        c.inspect("finished");runCurrent()
        assertTrue(c.requestOnReturn("finished"));runCurrent()
        assertEquals(1,ledger.state.attempts)
        gateway.token.complete("native-token");runCurrent()
        assertEquals(ReviewPhase.LAUNCHED,c.phase.value)
        c.cancelPreparation() // pause/interaction from the native form must not release its ownership
        advanceTimeBy(300000);runCurrent()
        assertEquals(ReviewPhase.LAUNCHED,c.phase.value)
        gateway.finish.complete(Unit);runCurrent()
        assertEquals(ReviewPhase.IDLE,c.phase.value)
        assertFalse(ledger.state.alreadyReviewed)
        assertEquals(listOf("attempt","launch_attempt","flow_finished"),events)
        c.close()
    }

    @Test fun navigationAndLatePreparationCannotResurrectAForm() = runTest {
        val l=Ledger();val g=Gateway();val c=ReviewCoordinator(this,l,g,{enough},{true},{1000})
        c.inspect("finished");runCurrent();assertTrue(c.requestOnReturn("finished"));runCurrent()
        c.cancelPreparation();g.token.complete("late");runCurrent()
        assertEquals(0,g.launches);assertEquals(ReviewPhase.IDLE,c.phase.value)
        assertFalse(c.requestOnReturn("finished"));c.close()
    }

    @Test fun preparationDeadlineDropsLateCallbackWithoutBlockingMenu() = runTest {
        val l=Ledger();val g=Gateway();val events=mutableListOf<String>()
        val c=ReviewCoordinator(this,l,g,{enough},{true},{1000},events::add)
        c.inspect("finished");runCurrent();c.requestOnReturn("finished");runCurrent()
        advanceTimeBy(1501);runCurrent();g.token.complete("late");runCurrent()
        assertEquals(ReviewPhase.IDLE,c.phase.value);assertEquals(0,g.launches)
        assertTrue(events.contains("prepare_timeout"));c.close()
    }

    @Test fun foregroundAndCurrentScreenAreRecheckedBeforeNativeHandoff() = runTest {
        var allowed=true;val l=Ledger();val g=Gateway()
        val c=ReviewCoordinator(this,l,g,{enough},{allowed},{1000})
        c.inspect("finished");runCurrent();assertTrue(c.requestOnReturn("finished"));runCurrent()
        allowed=false;g.token.complete("ready");runCurrent()
        assertEquals(0,g.launches);assertEquals(ReviewPhase.IDLE,c.phase.value);c.close()
    }

    @Test fun unavailableSdkAndInsufficientExperienceNeverReserveAnAdOpportunity() = runTest {
        val l=Ledger();val g=Gateway();g.available=false
        val c=ReviewCoordinator(this,l,g,{enough},{true},{1000})
        c.inspect("finished");runCurrent();assertFalse(c.requestOnReturn("finished"));assertEquals(0,g.prepares)
        g.available=true
        val other=ReviewCoordinator(this,l,g,{enough.copy(currentEligible=false)},{true},{1000})
        other.inspect("stopped");runCurrent();assertFalse(other.requestOnReturn("stopped"))
        c.close();other.close()
    }

    @Test fun durableSaveFailurePreventsSdkAndAlreadyReviewedSuppressesFuturePrompts() = runTest {
        val l=Ledger();val g=Gateway();l.failSave=true
        val c=ReviewCoordinator(this,l,g,{enough},{true},{1000})
        c.inspect("finished");runCurrent();c.requestOnReturn("finished");runCurrent()
        assertEquals(0,g.prepares);assertEquals(ReviewPhase.IDLE,c.phase.value)
        l.failSave=false;g.error=IllegalStateException("review-exists")
        c.inspect("next");runCurrent();c.requestOnReturn("next");runCurrent()
        assertTrue(l.state.alreadyReviewed)
        c.inspect("again");runCurrent();assertFalse(c.requestOnReturn("again"));c.close()
    }

    @Test fun repeatWrongSessionRecreationAndDisposalCannotDuplicateRequest() = runTest {
        val l=Ledger();val g=Gateway();val c=ReviewCoordinator(this,l,g,{enough},{true},{1000})
        c.inspect("finished");runCurrent();assertFalse(c.requestOnReturn("other"))
        assertTrue(c.requestOnReturn("finished"));assertFalse(c.requestOnReturn("finished"));runCurrent()
        c.close();g.token.complete("late");runCurrent();assertEquals(0,g.launches)
        val next=ReviewCoordinator(this,l,g,{enough},{true},{2000})
        next.inspect("finished");runCurrent();assertFalse(next.requestOnReturn("finished"));next.close()
    }
}
