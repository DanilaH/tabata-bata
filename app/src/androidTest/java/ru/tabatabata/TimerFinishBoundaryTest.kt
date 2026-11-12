package ru.tabatabata

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TimerFinishBoundaryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (!condition()) {
            assertTrue("$message; state=${TimerService.state.value}", SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(25)
        }
    }

    @Test
    fun queuedRepeatSurvivesPreviousFinishCleanup() {
        val releaseMain = CountDownLatch(1)
        var originalFailure: Throwable? = null
        try {
            TimerService.clearResult()
            TimerService.command(context, "start", Plan(0, 1, 0, 1))
            await("First workout must complete") { TimerService.state.value?.status == Status.COMPLETED }
            val first = TimerService.state.value!!.sessionId

            // Queue a new Android start request while the main looper is briefly busy.
            // The old 4.5 s cleanup becomes due before the new onStartCommand is delivered.
            SystemClock.sleep(4200)
            val mainBlocked = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                mainBlocked.countDown()
                check(releaseMain.await(2, TimeUnit.SECONDS)) { "Boundary probe did not release main looper" }
            }
            assertTrue("Main looper must enter the boundary probe", mainBlocked.await(2, TimeUnit.SECONDS))
            SystemClock.sleep(500)
            TimerService.command(context, "start", Plan(0, 20, 0, 1))
            releaseMain.countDown()

            await("Queued repeat must start") {
                TimerService.state.value?.let { it.sessionId != first && it.status == Status.RUNNING } == true
            }
            val initial = TimerService.state.value!!
            await("Repeat must keep ticking after the old cleanup") {
                TimerService.state.value?.let {
                    it.sessionId == initial.sessionId && it.remaining < initial.remaining - 150
                } == true
            }
            compose.onNodeWithText(compose.activity.getString(R.string.pause)).performClick()
            await("Repeat must accept Pause") { TimerService.state.value?.status == Status.PAUSED }
            compose.onNodeWithText(compose.activity.getString(R.string.resume)).performClick()
            await("Repeat must accept Resume") { TimerService.state.value?.status == Status.RUNNING }
        } catch (failure: Throwable) {
            originalFailure = failure
            throw failure
        } finally {
            releaseMain.countDown()
            try {
                TimerService.command(context, "stop")
                await("Boundary probe cleanup") {
                    TimerService.state.value == null || TimerService.state.value?.status == Status.STOPPED
                }
                TimerService.clearResult()
            } catch (cleanup: Throwable) {
                if (originalFailure == null) throw cleanup
                originalFailure.addSuppressed(cleanup)
            }
        }
    }
}
