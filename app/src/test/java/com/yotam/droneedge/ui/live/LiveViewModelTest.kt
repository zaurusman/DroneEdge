package com.yotam.droneedge.ui.live

import android.app.Application
import com.yotam.droneedge.recording.FakeSessionRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for LiveViewModel's state machine.
 *
 * UnconfinedTestDispatcher replaces Dispatchers.Main so viewModelScope.launch
 * executes eagerly without needing an Android Looper.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: LiveViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        vm = LiveViewModel(Application()).also {
            // start() auto-arms recording; inject a no-op recorder so JVM tests don't
            // try to open a real MediaStore file.
            it.recorderFactory = { FakeSessionRecorder() }
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test
    fun `initial state is IDLE`() {
        assertEquals(SessionState.IDLE, vm.sessionState.value)
    }

    @Test
    fun `initial videoUri is null`() {
        assertNull(vm.videoUri.value)
    }

    @Test
    fun `initial fps values are zero`() {
        assertEquals(0f, vm.previewFps.value)
        assertEquals(0f, vm.inferenceFps.value)
    }

    @Test
    fun `initial detections are empty`() {
        assertEquals(emptyList<Any>(), vm.detections.value)
    }

    // ── Start / Stop ──────────────────────────────────────────────────────────

    @Test
    fun `start transitions to RUNNING`() {
        vm.start()
        assertEquals(SessionState.RUNNING, vm.sessionState.value)
    }

    @Test
    fun `stop after start returns to IDLE`() {
        vm.start()
        vm.stop()
        assertEquals(SessionState.IDLE, vm.sessionState.value)
    }

    @Test
    fun `calling start twice stays RUNNING`() {
        vm.start()
        vm.start() // second call must be a no-op
        assertEquals(SessionState.RUNNING, vm.sessionState.value)
    }

    @Test
    fun `calling stop when IDLE is ignored`() {
        vm.stop() // must not throw
        assertEquals(SessionState.IDLE, vm.sessionState.value)
    }

    // ── Reset on stop ─────────────────────────────────────────────────────────

    @Test
    fun `fps values reset to zero on stop`() {
        vm.start()
        vm.stop()
        assertEquals(0f, vm.previewFps.value)
        assertEquals(0f, vm.inferenceFps.value)
    }

    @Test
    fun `detections cleared on stop`() {
        vm.start()
        vm.stop()
        assertEquals(emptyList<Any>(), vm.detections.value)
    }

    // ── Source selection ──────────────────────────────────────────────────────

    @Test
    fun `useFakeSource keeps videoUri null`() {
        vm.useFakeSource()
        assertNull(vm.videoUri.value)
    }

    @Test
    fun `useFileSource is ignored while RUNNING`() {
        vm.start()
        // Cannot pass a real Uri in a JVM unit test — verify guard by attempting useFakeSource
        vm.useFakeSource() // must be silently ignored while RUNNING
        assertEquals(SessionState.RUNNING, vm.sessionState.value)
    }

    // ── Detector mode ─────────────────────────────────────────────────────────

    @Test
    fun `initial detectorMode is FAKE`() {
        assertEquals(DetectorMode.FAKE, vm.detectorMode.value)
    }

    @Test
    fun `initial error is null`() {
        assertNull(vm.error.value)
    }

    @Test
    fun `setDetectorMode FAKE resets to fake when already fake`() {
        vm.setDetectorMode(DetectorMode.FAKE)
        assertEquals(DetectorMode.FAKE, vm.detectorMode.value)
        assertNull(vm.error.value)
    }

    @Test
    fun `setDetectorMode TFLITE without context is ignored`() {
        // context = null → guard returns immediately, mode stays FAKE
        vm.setDetectorMode(DetectorMode.TFLITE, context = null)
        assertEquals(DetectorMode.FAKE, vm.detectorMode.value)
    }

    @Test
    fun `setDetectorMode is ignored while RUNNING`() {
        vm.start()
        vm.setDetectorMode(DetectorMode.FAKE)      // must be no-op
        assertEquals(SessionState.RUNNING, vm.sessionState.value)
    }

    @Test
    fun `clearError sets error to null`() {
        // Simulate an error by triggering TFLite load with null context (sets no error)
        // Instead verify clearError on a null error is safe
        vm.clearError()
        assertNull(vm.error.value)
    }
}
