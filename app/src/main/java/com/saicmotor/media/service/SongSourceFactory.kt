package com.saicmotor.media.service

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ogg.OggExtractor

/**
 * Builds the player's source for each song, giving it the song's length (and
 * keeping what it streams, see [KeepWhatPlays]).
 *
 * A song streaming from Navidrome has no length, so ExoPlayer would take it
 * for live radio and start it over when the connection drops; with the length
 * (from the song's details) it carries on from where it dropped.  The length
 * also shows a song the server ended early: [onCutShort] is told where it
 * stopped (ms), off the main thread.
 */
@OptIn(UnstableApi::class)
internal class SongSourceFactory(
    private val dataSourceFactory: DataSource.Factory,
    /** The song's length in milliseconds, or null if unknown. */
    private val durationMsOf: (MediaItem) -> Long?,
    private val onCutShort: (MediaItem, Long) -> Unit = { _, _ -> }
) : MediaSource.Factory {

    private var drm: DrmSessionManagerProvider = DefaultDrmSessionManagerProvider()
    private var loadErrors: LoadErrorHandlingPolicy = WaitForNetworkPolicy()

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory =
        apply { drm = provider }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory =
        apply { loadErrors = policy }

    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val durationUs = durationMsOf(mediaItem)?.takeIf { it > 0 }?.times(1_000) ?: C.TIME_UNSET
        val extractors = KnownDurationExtractors(
            durationUs,
            onCutShort = { endedUs -> onCutShort(mediaItem, endedUs / 1_000) },
            onStream   = { stream -> StreamedSongs.mark(mediaItem.mediaId, stream) }
        )
        return ProgressiveMediaSource.Factory(KeepWhatPlays(dataSourceFactory), extractors)
            .setDrmSessionManagerProvider(drm)
            .setLoadErrorHandlingPolicy(loadErrors)
            .createMediaSource(mediaItem)
    }
}

/**
 * Lets the song cache keep what the player streams.
 *
 * ExoPlayer asks the cache not to keep a stream of unknown length (it takes
 * it for live radio), and a song still streaming from Navidrome is just that:
 * transcoded, with no length.  So a song the look-ahead hadn't downloaded
 * (the first one after a skip or a new album) was never kept, and when the
 * car started again it had to come from the server (no signal yet: silence).
 * Navidrome makes the same bytes every time it transcodes a song (only the
 * Ogg serial number differs, which nothing checks), so a song picked up again
 * after a dropped connection is kept whole too.
 */
@OptIn(UnstableApi::class)
internal class KeepWhatPlays(private val upstream: DataSource.Factory) : DataSource.Factory {

    override fun createDataSource(): DataSource = object : DataSource {
        private val source = upstream.createDataSource()

        override fun addTransferListener(transferListener: TransferListener) = source.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long = source.open(
            dataSpec.buildUpon().setFlags(dataSpec.flags and DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN.inv()).build()
        )

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = source.read(buffer, offset, length)

        override fun getUri(): Uri? = source.uri

        override fun getResponseHeaders(): Map<String, List<String>> = source.responseHeaders

        override fun close() = source.close()
    }
}

/** The usual extractors, but a stream whose duration they can't tell gets [durationUs]. */
@OptIn(UnstableApi::class)
internal class KnownDurationExtractors(
    private val durationUs: Long,
    /** The stream ended well short of [durationUs]: where (µs). */
    private val onCutShort: (Long) -> Unit = {},
    /** The song's being taken as a stream (true), or has turned out to be all there (false). */
    private val onStream: (Boolean) -> Unit = {}
) : ExtractorsFactory {
    private val all = DefaultExtractorsFactory()

    override fun createExtractors(): Array<Extractor> = wrap(all.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        wrap(all.createExtractors(uri, responseHeaders))

    private fun wrap(all: Array<Extractor>): Array<Extractor> {
        val extractors = oggFirst(all)
        return if (durationUs == C.TIME_UNSET) extractors
        else Array(extractors.size) { KnownDurationExtractor(extractors[it], durationUs, onCutShort, onStream) }
    }

    /**
     * Ogg is tried first: it's what Navidrome sends the car (Opus), and a
     * song read back from the cache comes with nothing to say what it is,
     * so seven other readers each had a look at it first.  Anything else
     * (a song sent as it is) is still found by the reader after.
     */
    private fun oggFirst(extractors: Array<Extractor>): Array<Extractor> {
        val ogg = extractors.indexOfFirst { it is OggExtractor }
        if (ogg <= 0) return extractors
        return Array(extractors.size) { i -> if (i == 0) extractors[ogg] else if (i <= ogg) extractors[i - 1] else extractors[i] }
    }
}

/**
 * The songs the player is taking as a stream: come up before they were
 * downloaded, so with no length, and no way for the player to find a place
 * in them but to read from the top (see [KnownDurationExtractor]).  They can
 * be skipped through only as far as they've come in.
 */
internal object StreamedSongs {
    private val ids = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun mark(mediaId: String, stream: Boolean) {
        if (stream) ids += mediaId else ids -= mediaId
    }

    operator fun contains(mediaId: String?): Boolean = mediaId != null && mediaId in ids
}

/**
 * Gives a stream whose duration its reader can't tell the song's known
 * duration, and lets a song that's streaming be skipped through.
 *
 * The Ogg reader finds a place in a song by looking at its end and working
 * inwards, which takes the song's length: a song still streaming has none,
 * so the reader calls it unseekable, and the player then starts it over
 * from the beginning whenever it's asked for another place in it.  So such
 * a song is said to be seekable, with one way to any place in it: read it
 * again from the top (from disk, as far as it's been kept) and let through
 * only what comes from that place on.  Skipping ahead within what the
 * player's already read doesn't even need that.  What isn't downloaded yet
 * can't be skipped to: MediaService sees to that ([StreamedSongs]).
 */
@OptIn(UnstableApi::class)
internal class KnownDurationExtractor(
    private val extractor: Extractor,
    private val knownDurationUs: Long,
    private val onCutShort: (Long) -> Unit = {},
    private val onStream: (Boolean) -> Unit = {}
) : Extractor {

    /** The latest point in the song read so far. */
    private var lastSampleUs = C.TIME_UNSET
    private var toldCutShort = false

    /** Being read as a stream (no length): see the class comment. */
    private var stream = false
    /** Reading again from the top, for the place at [fromUs]: packets are held back until it's known whether they're let through. */
    private var sifting = false
    private var fromUs = 0L
    /** The Ogg reader, started over, hands on the stream's two header packets as if they were sound, and counts them as time. */
    private var sawHeader = false
    private var headerUs = 0L
    private var headerUsKnown = true
    private var held = ByteArray(0)
    private var heldSize = 0
    private var heldIsHeader = false

    override fun sniff(input: ExtractorInput): Boolean = extractor.sniff(input)

    override fun init(output: ExtractorOutput) {
        extractor.init(object : ExtractorOutput by output {
            override fun track(id: Int, type: Int): TrackOutput {
                val track = output.track(id, type)
                return object : TrackOutput by track {
                    override fun sampleData(input: androidx.media3.common.DataReader, length: Int, allowEndOfInput: Boolean): Int =
                        sampleData(input, length, allowEndOfInput, TrackOutput.SAMPLE_DATA_PART_MAIN)

                    override fun sampleData(input: androidx.media3.common.DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                        if (!sifting) return track.sampleData(input, length, allowEndOfInput, sampleDataPart)
                        hold(length)
                        val n = input.read(held, heldSize, length)
                        if (n == C.RESULT_END_OF_INPUT) {
                            if (allowEndOfInput) return n
                            throw java.io.EOFException()
                        }
                        heldSize += n
                        return n
                    }

                    override fun sampleData(data: androidx.media3.common.util.ParsableByteArray, length: Int) =
                        sampleData(data, length, TrackOutput.SAMPLE_DATA_PART_MAIN)

                    override fun sampleData(data: androidx.media3.common.util.ParsableByteArray, length: Int, sampleDataPart: Int) {
                        if (!sifting) return track.sampleData(data, length, sampleDataPart)
                        hold(length)
                        data.readBytes(held, heldSize, length)
                        heldSize += length
                    }

                    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                        var at = timeUs
                        if (sifting) {
                            val header = heldSize >= 8 && (startsWith(OPUS_HEAD) || startsWith(OPUS_TAGS))
                            if (header) {
                                sawHeader = true
                                headerUsKnown = false
                                heldSize = 0
                                return                                  // not sound
                            }
                            if (!headerUsKnown) { headerUs = timeUs; headerUsKnown = true }   // what the headers were counted as
                            at = timeUs - headerUs
                            if (at > lastSampleUs) lastSampleUs = at
                            if (at < fromUs) { heldSize = 0; return }   // before the place asked for
                            track.sampleData(androidx.media3.common.util.ParsableByteArray(held, heldSize), heldSize)
                            heldSize = 0
                            sifting = false                             // from here on it all goes through
                            track.sampleMetadata(at, flags, size, offset, cryptoData)
                            return
                        }
                        at = timeUs - headerUs
                        if (at > lastSampleUs) lastSampleUs = at
                        track.sampleMetadata(at, flags, size, offset, cryptoData)
                    }
                }
            }

            override fun seekMap(seekMap: SeekMap) {
                output.seekMap(when {
                    seekMap.durationUs != C.TIME_UNSET -> {
                        stream = false
                        onStream(false)
                        seekMap
                    }
                    extractor.underlyingImplementation is OggExtractor -> {
                        stream = true
                        onStream(true)
                        FromTheTop(knownDurationUs)
                    }
                    else -> object : SeekMap by seekMap {
                        override fun getDurationUs(): Long = knownDurationUs
                    }
                })
            }
        })
    }

    private fun hold(more: Int) {
        if (heldSize + more > held.size) held = held.copyOf(maxOf(heldSize + more, held.size * 2, 4096))
    }

    private fun startsWith(what: ByteArray): Boolean = what.indices.all { held[it] == what[it] }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val result = extractor.read(input, seekPosition)
        if (result == Extractor.RESULT_END_OF_INPUT && !toldCutShort && lastSampleUs != C.TIME_UNSET &&
            knownDurationUs - lastSampleUs > CUT_SHORT_US) {
            toldCutShort = true
            onCutShort(lastSampleUs)
        }
        return result
    }

    override fun seek(position: Long, timeUs: Long) {
        heldSize = 0
        sawHeader = false
        headerUs = 0L
        headerUsKnown = true
        // A stream read again from the top: only what's wanted goes through.
        sifting = stream && position == 0L
        fromUs = if (sifting) timeUs else 0L
        extractor.seek(position, timeUs)
    }

    override fun release() = extractor.release()

    override fun getUnderlyingImplementation(): Extractor = extractor.underlyingImplementation

    /** Any place in the song is reached by reading from the top (see [KnownDurationExtractor]). */
    private class FromTheTop(private val durationUs: Long) : SeekMap {
        override fun isSeekable() = true
        override fun getDurationUs() = durationUs
        override fun getSeekPoints(timeUs: Long) = SeekMap.SeekPoints(androidx.media3.extractor.SeekPoint.START)
    }

    private companion object {
        /** Ending this far before the song's length isn't the song's own end (lengths are given to the second). */
        const val CUT_SHORT_US = 10_000_000L
        val OPUS_HEAD = "OpusHead".toByteArray(Charsets.US_ASCII)
        val OPUS_TAGS = "OpusTags".toByteArray(Charsets.US_ASCII)
    }
}
