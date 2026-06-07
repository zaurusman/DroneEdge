package com.droneedge.app.recording

import com.droneedge.app.ui.live.RecordingState
import com.droneedge.app.ui.live.SessionState
import com.droneedge.app.ui.live.canArm
import com.droneedge.app.ui.live.canDisarm
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStateTransitionTest {

    @Test
    fun canArmTrueOnlyWhenRunningAndIdle() {
        assertTrue(canArm(SessionState.RUNNING, RecordingState.IDLE))
        assertFalse(canArm(SessionState.IDLE,     RecordingState.IDLE))
        assertFalse(canArm(SessionState.STOPPING, RecordingState.IDLE))
        assertFalse(canArm(SessionState.RUNNING,  RecordingState.ARMED))
        assertFalse(canArm(SessionState.RUNNING,  RecordingState.FINALIZING))
    }

    @Test
    fun canDisarmTrueOnlyWhenArmed() {
        assertTrue(canDisarm(RecordingState.ARMED))
        assertFalse(canDisarm(RecordingState.IDLE))
        assertFalse(canDisarm(RecordingState.FINALIZING))
    }
}
