package com.spacecorps.oam.mlkit

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.spacecorps.oam.GenerationChunk
import com.spacecorps.oam.GenerationRequest
import com.spacecorps.oam.LanguageModel
import com.spacecorps.oam.ModelAvailability
import com.spacecorps.oam.ModelCapabilities
import com.spacecorps.oam.testing.ScriptedLanguageModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AvailabilityMonitorTest {
    /** A model that counts availability checks. */
    private class CountingModel(var availability: ModelAvailability) : LanguageModel {
        var checks = 0
        override val capabilities = ModelCapabilities()
        override suspend fun availability(): ModelAvailability {
            checks++
            return availability
        }
        override suspend fun countTokens(text: String): Int? = null
        override fun generate(request: GenerationRequest): Flow<GenerationChunk> = emptyFlow()
    }

    @Test
    fun checksAtOnceAndPollsWhileNotAvailable() = runTest {
        val model = CountingModel(ModelAvailability.Downloadable)
        val monitor = AvailabilityMonitor(model, backgroundScope, pollInterval = 5.seconds, availablePollInterval = null)
        assertNull(monitor.state.value)
        runCurrent()
        assertEquals(ModelAvailability.Downloadable, monitor.state.value)
        assertEquals(1, model.checks)

        model.availability = ModelAvailability.Downloading(10, 100)
        advanceTimeBy(5.seconds + 1.seconds)
        assertEquals(ModelAvailability.Downloading(10, 100), monitor.state.value)
        assertEquals(2, model.checks)

        model.availability = ModelAvailability.Available
        advanceTimeBy(6.seconds)
        assertEquals(ModelAvailability.Available, monitor.state.value)
        val checks = model.checks
        // Available and availablePollInterval = null: no more polling...
        advanceTimeBy(10.minutes)
        assertEquals(checks, model.checks)
        // ...until asked.
        model.availability = ModelAvailability.Downloadable
        monitor.requestRefresh()
        runCurrent()
        assertEquals(ModelAvailability.Downloadable, monitor.state.value)
        monitor.close()
    }

    @Test
    fun pollsSlowlyWhileAvailableAndStopsOnClose() = runTest {
        val model = CountingModel(ModelAvailability.Available)
        val monitor = AvailabilityMonitor(model, backgroundScope, pollInterval = 1.seconds, availablePollInterval = 1.minutes)
        runCurrent()
        advanceTimeBy(30.seconds)
        assertEquals(1, model.checks)
        advanceTimeBy(31.seconds)
        assertEquals(2, model.checks)
        monitor.close()
        advanceTimeBy(10.minutes)
        assertEquals(2, model.checks)
        assertEquals(ModelAvailability.Available, monitor.state.value)
    }

    @Test
    fun unsupportedDevicesAreCheckedRarely() = runTest {
        val model = CountingModel(ModelAvailability.Unavailable(GeminiNanoErrors.Reasons.DEVICE_NOT_ELIGIBLE))
        AvailabilityMonitor(model, backgroundScope, pollInterval = 1.seconds, availablePollInterval = 1.minutes)
        runCurrent()
        advanceTimeBy(59.seconds)
        assertEquals(1, model.checks)
    }

    @Test
    fun refreshReturnsAndPublishesAndSurvivesFailures() = runTest {
        val scripted = ScriptedLanguageModel(availability = ModelAvailability.Downloadable)
        val monitor = AvailabilityMonitor(scripted, backgroundScope)
        scripted.currentAvailability = ModelAvailability.Available
        assertEquals(ModelAvailability.Available, monitor.refresh())
        assertEquals(ModelAvailability.Available, monitor.state.value)

        val failing = object : LanguageModel by scripted {
            override suspend fun availability(): ModelAvailability = throw IllegalStateException("binder died")
        }
        val failingMonitor = AvailabilityMonitor(failing, backgroundScope)
        assertEquals(ModelAvailability.Unavailable("unknown", "binder died"), failingMonitor.refresh())
    }

    @Test
    fun followsGeminiNanoDownloadProgress() = runTest {
        val client = FakeNanoClient(status = FeatureStatus.DOWNLOADABLE)
        val nano = GeminiNanoModel(client, GeminiNanoOptions())
        val monitor = AvailabilityMonitor(nano, backgroundScope, pollInterval = 1.minutes, availablePollInterval = null)
        runCurrent()
        assertEquals(ModelAvailability.Downloadable, monitor.state.value)

        val step = CompletableDeferred<Unit>()
        val seen = ArrayList<ModelAvailability?>()
        client.downloadEvents = flow {
            emit(DownloadStatus.DownloadStarted(200))
            client.status = FeatureStatus.DOWNLOADING
            emit(DownloadStatus.DownloadProgress(50))
            step.await()
            client.status = FeatureStatus.AVAILABLE
            emit(DownloadStatus.DownloadCompleted)
        }
        val download = backgroundScope.launch { nano.download().toList() }
        runCurrent()
        seen += monitor.state.value
        step.complete(Unit)
        download.join()
        runCurrent()
        seen += monitor.state.value

        assertEquals(ModelAvailability.Downloading(50, 200), seen[0])
        assertIs<ModelAvailability.Available>(seen[1])
    }
}
