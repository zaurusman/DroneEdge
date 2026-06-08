package com.droneedge.app.video

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.ts.H264Reader
import androidx.media3.extractor.ts.SeiReader
import androidx.media3.extractor.ts.TsPayloadReader

/**
 * Feeds raw H.264 Annex-B bytes to ExoPlayer via ExoPlayer's H264Reader.
 *
 * Ported from fpv-wtf/voc-poc / d4rken/fpv-dvca (H264Extractor2).
 * Each read() call consumes up to 64KB from the pipe and hands it to H264Reader,
 * which handles NAL-unit parsing, SPS/PPS extraction as CSD, and sample output.
 *
 * Timestamps use wall-clock elapsed time (SystemClock.elapsedRealtime offset from
 * session start). This gives H264Reader accurate real-time PTS so ExoPlayer maintains
 * a stable buffer and plays at the correct rate instead of oscillating BUFFERING/READY.
 */
@UnstableApi
class RawH264Extractor : Extractor {

    private val reader = H264Reader(SeiReader(emptyList()), false, true)
    private val buffer = ParsableByteArray(65_536)
    private var startTimeUs = -1L

    override fun sniff(input: ExtractorInput): Boolean = true

    override fun init(output: ExtractorOutput) {
        reader.createTracks(output, TsPayloadReader.TrackIdGenerator(0, 1))
        output.seekMap(SeekMap.Unseekable(C.TIME_UNSET))
        output.endTracks()
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val bytesRead = input.read(buffer.data, 0, buffer.data.size)
        if (bytesRead == C.RESULT_END_OF_INPUT) return Extractor.RESULT_END_OF_INPUT
        buffer.reset(bytesRead)
        val nowUs = SystemClock.elapsedRealtime() * 1000L
        if (startTimeUs < 0L) startTimeUs = nowUs
        val timestampUs = nowUs - startTimeUs
        reader.packetStarted(timestampUs, TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR)
        reader.consume(buffer)
        reader.packetFinished(false)
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) {
        startTimeUs = -1L
        reader.seek()
    }

    override fun release() {}
}
