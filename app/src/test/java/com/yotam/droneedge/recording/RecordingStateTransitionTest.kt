package com.yotam.droneedge.recording

import com.yotam.droneedge.ui.live.RecordingState
import com.yotam.droneedge.ui.live.SessionState
import com.yotam.droneedge.ui.live.canArm
import com.yotam.droneedge.ui.live.canDisarm
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
