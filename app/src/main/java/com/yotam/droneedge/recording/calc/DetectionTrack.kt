package com.droneedge.app.recording.calc

import com.droneedge.app.detection.BoundingBox
import com.droneedge.app.detection.Detection

/**
 * Time-indexed view of a recorded `detections.json`. Pure Kotlin (regex parsing, JVM-testable).
 * [boxesAt] returns the most recent detections at or before a wall-clock time (last-known box,
 * matching the live overlay's behaviour between inferences).
 */
class DetectionTrack private constructor(
    val sessionStartMs: Long,
    private val times: LongArray,
    private val boxes: List<List<Detection>>,
) {
    /** Most recent detections at or before [wallClockMs]; empty if before the first event. */
    fun boxesAt(wallClockMs: Long): List<Detection> {
        if (times.isEmpty() || wallClockMs < times[0]) return emptyList()
        var lo = 0
        var hi = times.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= wallClockMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return boxes[ans]
    }

    companion object {
        private val SESSION_START = Regex(""""sessionStart"\s*:\s*(\d+)""")
        private val TIMESTAMP = Regex(""""timestampMs"\s*:\s*(\d+)""")
        private val DET = Regex(
            """\{"label":"([^"]*)","confidence":([-\d.]+),"left":([-\d.]+),"top":([-\d.]+),"right":([-\d.]+),"bottom":([-\d.]+)\}"""
        )

        fun parse(lines: List<String>): DetectionTrack {
            var sessionStart = 0L
            val ts = ArrayList<Long>()
            val bx = ArrayList<List<Detection>>()
            for (line in lines) {
                if (sessionStart == 0L) {
                    SESSION_START.find(line)?.let { sessionStart = it.groupValues[1].toLong() }
                }
                val tMatch = TIMESTAMP.find(line) ?: continue
                val t = tMatch.groupValues[1].toLong()
                val dets = DET.findAll(line).map { m ->
                    Detection(
                        label = m.groupValues[1],
                        confidence = m.groupValues[2].toFloat(),
                        boundingBox = BoundingBox(
                            left = m.groupValues[3].toFloat(),
                            top = m.groupValues[4].toFloat(),
                            right = m.groupValues[5].toFloat(),
                            bottom = m.groupValues[6].toFloat(),
                        ),
                    )
                }.toList()
                ts.add(t); bx.add(dets)
            }
            return DetectionTrack(sessionStart, ts.toLongArray(), bx)
        }
    }
}
