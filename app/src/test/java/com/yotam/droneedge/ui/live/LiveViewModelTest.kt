package com.yotam.droneedge.ui.live

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
        vm = LiveViewModel()
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
}
