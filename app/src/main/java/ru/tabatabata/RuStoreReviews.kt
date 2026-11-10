package ru.tabatabata

import android.app.Activity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import ru.rustore.sdk.review.RuStoreReviewManagerFactory
import ru.rustore.sdk.review.errors.RuStoreReviewExists
import ru.rustore.sdk.review.model.ReviewInfo

/** Real native flow is release-only. Ordinary emulator tests never solicit a real review. */
internal class RuStoreReviews(private val activity: Activity) : ReviewGateway {
    private val manager by lazy { RuStoreReviewManagerFactory.create(activity) }
    override val available: Boolean get() = !BuildConfig.DEBUG && runCatching {
        @Suppress("DEPRECATION")
        activity.packageManager.getPackageInfo("ru.vk.store", 0)
    }.isSuccess

    override suspend fun prepare(): Any = suspendCancellableCoroutine<ReviewInfo> { continuation ->
        manager.requestReviewFlow()
            .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
            .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    }

    override suspend fun launch(token: Any) { suspendCancellableCoroutine<Unit> { continuation ->
        manager.launchReviewFlow(token as ReviewInfo)
            .addOnSuccessListener { if (continuation.isActive) continuation.resume(Unit) }
            .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    } }

    override fun isAlreadyReviewed(error: Throwable) = error is RuStoreReviewExists
}
