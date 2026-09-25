package com.spacecorps.oam.mlkit

import com.spacecorps.oam.LanguageModel
import com.spacecorps.oam.ModelAvailability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Watches a model's [availability][LanguageModel.availability] and publishes
 * it as a [StateFlow], for status UIs and for deciding when to offer a download.
 *
 * ```kotlin
 * val monitor = AvailabilityMonitor(nano, viewModelScope)
 * monitor.state.collect { availability -> render(availability) }
 * ```
 *
 * It checks at once, then again every [pollInterval] while the model is
 * downloadable or downloading, and every [availablePollInterval] once it is
 * available (AICore can remove or update the model). An unsupported device
 * ([GeminiNanoErrors.Reasons.DEVICE_NOT_ELIGIBLE]) is checked every
 * [availablePollInterval] too; other unavailable states every [pollInterval].
 * With a [GeminiNanoModel], download progress from [GeminiNanoModel.download]
 * appears at once, without waiting for the next check.
 *
 * Works with any [LanguageModel], so the same UI can show a scripted or
 * development model. Monitoring stops when [scope] is cancelled or on [close].
 *
 * @param model The model to watch.
 * @param scope Where monitoring runs, typically a view model's or activity's scope.
 * @param pollInterval How often to check while the model is not available.
 * @param availablePollInterval How often to check while it is available (or cannot ever be); `null` stops checking then.
 */
public class AvailabilityMonitor(
    private val model: LanguageModel,
    scope: CoroutineScope,
    public val pollInterval: Duration = 5.seconds,
    public val availablePollInterval: Duration? = 1.minutes,
) : AutoCloseable {
    init {
        require(pollInterval.isPositive()) { "pollInterval must be positive." }
        require(availablePollInterval == null || availablePollInterval.isPositive()) { "availablePollInterval must be positive." }
    }

    private val current = MutableStateFlow<ModelAvailability?>(null)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val checkLock = Mutex()

    /** The latest availability; `null` until the first check completes. */
    public val state: StateFlow<ModelAvailability?> = current.asStateFlow()

    private val jobs: List<Job> = buildList {
        add(scope.launch { poll() })
        if (model is GeminiNanoModel) add(scope.launch { followDownloads(model) })
    }

    /**
     * Checks now and returns the result (also published to [state]). A model
     * whose check throws is reported as unavailable with reason `"unknown"`.
     */
    public suspend fun refresh(): ModelAvailability = checkLock.withLock {
        val availability = try {
            model.availability()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ModelAvailability.Unavailable(GeminiNanoErrors.Reasons.UNKNOWN, error.message ?: error::class.java.name)
        }
        current.value = availability
        availability
    }

    /** Asks the monitor to check soon, without waiting for the result. */
    public fun requestRefresh() {
        wake.trySend(Unit)
    }

    /** Stops monitoring. [state] keeps its last value. */
    override fun close() {
        jobs.forEach { it.cancel() }
        wake.close()
    }

    private suspend fun poll() {
        while (true) {
            val availability = refresh()
            val interval = when {
                availability is ModelAvailability.Available -> availablePollInterval
                availability is ModelAvailability.Unavailable && availability.reason == GeminiNanoErrors.Reasons.DEVICE_NOT_ELIGIBLE -> availablePollInterval
                else -> pollInterval
            }
            val woken = if (interval == null) wake.receiveCatching() else withTimeoutOrNull(interval) { wake.receiveCatching() }
            if (woken != null && woken.isClosed) return
        }
    }

    private suspend fun followDownloads(model: GeminiNanoModel) {
        var wasDownloading = false
        model.downloadProgress.collect { downloading ->
            if (downloading != null) {
                current.value = downloading
                wasDownloading = true
            } else if (wasDownloading) {
                wasDownloading = false
                // The download finished or failed: check what AICore says now.
                requestRefresh()
            }
        }
    }
}
