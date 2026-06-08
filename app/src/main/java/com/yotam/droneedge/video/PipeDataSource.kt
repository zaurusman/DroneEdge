package com.droneedge.app.video

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.PipedInputStream

/**
 * ExoPlayer DataSource that reads from a PipedInputStream fed by the USB read loop.
 */
@UnstableApi
class PipeDataSource(private val pipe: PipedInputStream) : DataSource {
    override fun open(dataSpec: DataSpec): Long =
        androidx.media3.common.C.LENGTH_UNSET.toLong()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        pipe.read(buffer, offset, length)

    override fun getUri(): Uri = Uri.EMPTY
    override fun close() {}
    override fun addTransferListener(transferListener: TransferListener) {}
}
