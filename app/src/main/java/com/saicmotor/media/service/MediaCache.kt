package com.saicmotor.media.service

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.saicmotor.media.data.Storage
import com.saicmotor.media.data.SubsonicSettings
import com.saicmotor.media.subsonic.SubsonicClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A disk cache of songs, shared by the player and the pre-cacher.
 *
 * Songs are cached under their Subsonic id, not their URL: the URL carries a
 * login token that's different every time, so caching by URL would never hit.
 *
 * Upcoming songs are downloaded ahead of time ([precache]), so the next song
 * (or a skip) starts straight away even on a slow mobile connection, and the
 * song after a tunnel is already there.  Old songs are dropped once the cache
 * is full.
 */
@OptIn(UnstableApi::class)
object MediaCache {

    private const val MAX_BYTES  = 400L * 1024 * 1024
    /**
     * Songs are written to the cache in pieces this big, each saved as it
     * fills.  Small, because the car just loses power: a piece still being
     * written is lost, and the song that was playing should be on disk, up
     * to the point that was playing, when the car starts again.
     */
    private const val PIECE_BYTES = 512L * 1024

    @Volatile private var cache: SimpleCache? = null

    /**
     * The cache key for a song (see the class comment).  "v2": 2.0.2–2.0.4
     * cached the original FLAC files by mistake (see [dataSourceFactory]);
     * a new key leaves those to be cleared out first as the cache fills.
     */
    fun keyFor(mediaId: String): String = "song-v2:$mediaId"

    /** In device-protected storage, so songs can play the moment the head unit boots (see Storage). */
    private fun cache(context: Context): SimpleCache =
        cache ?: synchronized(this) {
            cache ?: run {
                val storage = Storage.context(context.applicationContext)
                SimpleCache(
                    File(storage.cacheDir, "songs"),
                    LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                    StandaloneDatabaseProvider(storage)
                )
            }.also { cache = it }
        }

    /**
     * Where the player reads songs from: the cache, falling back to the
     * server — or, for a song the look-ahead is still downloading, to that
     * download as it comes in (see [Follow]).
     */
    fun dataSourceFactory(context: Context): DataSource.Factory {
        // Made when the first song is loaded, not when the player is: opening
        // the cache reads its index and lists its files, and the player is
        // made while the car's own services are still starting (see
        // CLAUDE.md, "Quiet while the car starts").
        val factory by lazy {
            val server = serverFactory(context)
            val cache = cache(context)
            cacheFactory(context) {
                Follow(server.createDataSource()) { key -> ContentMetadata.getContentLength(cache.getContentMetadata(key)) }
            }
        }
        return DataSource.Factory { factory.createDataSource() }
    }

    /** Opens the cache, off the caller's thread: it's about to be wanted (see [dataSourceFactory]). */
    fun openSoon(context: Context) {
        if (cache != null) return
        val app = context.applicationContext
        Thread({ cache(app) }, "CarMedia:cache").start()
    }

    private fun cacheFactory(context: Context, upstream: DataSource.Factory): CacheDataSource.Factory {
        val cache = cache(context)
        return CacheDataSource.Factory()
            .setCache(cache)
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache).setFragmentSize(PIECE_BYTES))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** The songs from the server. */
    private fun serverFactory(context: Context): DataSource.Factory {
        val app = context.applicationContext
        return ResolvingDataSource.Factory(
                // An error message in place of the song is never saved as the song (see SongReplies).
                SongReplies.Factory(
                    // No User-Agent of our own: Navidrome treats each client name +
                    // User-Agent as a separate "player", and its transcoding
                    // (e.g. Opus 128k for the car) is set per player.  Songs must
                    // be fetched with the same User-Agent as the app's other
                    // requests (Android's default), or Navidrome registers a new
                    // player with no transcoding and sends the original files.
                    DefaultHttpDataSource.Factory()
                        .setAllowCrossProtocolRedirects(true)
                        // Short: while the connection is coming up, a hung attempt
                        // shouldn't hold up the next one (see WaitForNetworkPolicy).
                        .setConnectTimeoutMs(6_000)
                        .setReadTimeoutMs(15_000)
                )
            ) { dataSpec -> dataSpec.withUri(withCurrentLogin(app, dataSpec.uri)) }
    }

    /**
     * A song's address with the server and login from Settings as they are
     * now.  The address of every song in the queue carries the login as it
     * was when the queue was made, so after the password (or the server's
     * address) was changed, the queue kept failing until something else was
     * played.
     */
    private fun withCurrentLogin(context: Context, uri: Uri): Uri {
        if (uri.path?.endsWith("/rest/stream") != true) return uri
        val id = uri.getQueryParameter("id") ?: return uri
        val cfg = SubsonicSettings.load(context).takeIf { it.isValid } ?: return uri
        return Uri.parse(SubsonicClient.streamUrl(cfg, id))
    }

    /** How much of the song is on disk (0–1), or null if it's never been fetched. */
    fun cachedFraction(context: Context, mediaId: String): Float? {
        val c = cache(context)
        val key = keyFor(mediaId)
        val length = ContentMetadata.getContentLength(c.getContentMetadata(key))
        if (length <= 0) return null
        return c.getCachedBytes(key, 0, length).toFloat() / length
    }

    /** Bytes of the song on disk from its start (a transcoded song's full length isn't known until it's all in). */
    fun cachedBytesFromStart(context: Context, mediaId: String): Long =
        cache(context).getCachedLength(keyFor(mediaId), 0, Long.MAX_VALUE).coerceAtLeast(0)

    /** True if the whole song is already on disk. */
    fun isCached(context: Context, mediaId: String): Boolean {
        val c = cache(context)
        val key = keyFor(mediaId)
        val length = ContentMetadata.getContentLength(c.getContentMetadata(key))
        return length > 0 && c.isCached(key, 0, length)
    }

    /** A song that ended short of its listed length, as kept (bytes): to ask the server about (see [cutShort]). */
    private const val KEY_RECHECK = "cmp_recheck"
    /** How long the server said a song is (bytes), the last time it was asked. */
    private const val KEY_CHECKED = "cmp_checked"

    /**
     * What's kept of a song ended well short of its listed length: either
     * the server ended it early without saying so (a transcode that died),
     * or the listed length is wrong (it's from the song's tags).  Which, only
     * the server can say: the next time the look-ahead comes to the song, it
     * asks for the rest (see [precache]) — unless the server's already said
     * that's all.  Until then it's kept and played as it is.
     *
     * (It used to be no longer taken for the whole song straight away: the
     * player then asked for the rest itself at its end, every time if the
     * length was simply wrong — a whole song's data each play — and with no
     * signal it waited there for good instead of moving on.)
     *
     * False if there's nothing to ask: not all kept, or already asked.
     */
    fun cutShort(context: Context, mediaId: String): Boolean = try {
        val c = cache(context)
        val key = keyFor(mediaId)
        val meta = c.getContentMetadata(key)
        val length = ContentMetadata.getContentLength(meta)
        if (length == C.LENGTH_UNSET.toLong() || meta.get(KEY_CHECKED, C.LENGTH_UNSET.toLong()) == length) false
        else { c.applyContentMetadataMutations(key, ContentMetadataMutations().set(KEY_RECHECK, length)); true }
    } catch (_: Exception) {
        false   // only the cache: the song then stays as it is
    }

    /**
     * Downloads a song into the cache.  Blocking: run it off the main thread.
     * Returns a [CacheWriter] handle via [onStart] so it can be cancelled.
     * While it runs, the player can take the song from it (see [Follow]).
     *
     * A song that ended short ([cutShort]) is asked about: the rest is asked
     * for, and whatever the server sends (more, or nothing) is then the whole
     * song, not asked about again.  If it can't be asked, it's kept as it was.
     */
    fun precache(context: Context, mediaId: String, uri: Uri, onStart: (CacheWriter) -> Unit) {
        val key = keyFor(mediaId)
        val cache = cache(context)
        val recheck = cache.getContentMetadata(key).get(KEY_RECHECK, C.LENGTH_UNSET.toLong())
        // Not known to be all there, so the rest is asked for.
        if (recheck != C.LENGTH_UNSET.toLong()) {
            cache.applyContentMetadataMutations(key, ContentMetadataMutations().remove(ContentMetadata.KEY_CONTENT_LENGTH))
        }
        val download = LiveDownload(PIECE_BYTES)
        val server = serverFactory(context)
        val source = cacheFactory(context) { Recording(server.createDataSource(), download) }.createDataSource()
        val writer = CacheWriter(source, DataSpec.Builder().setUri(uri).setKey(key).build(), null, null)
        live[key] = download
        var complete = false
        try {
            onStart(writer)
            writer.cache()
            complete = true
        } finally {
            live.remove(key, download)
            download.stopped(complete)
            if (recheck != C.LENGTH_UNSET.toLong()) {
                val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
                cache.applyContentMetadataMutations(key,
                    if (complete) ContentMetadataMutations().remove(KEY_RECHECK).set(KEY_CHECKED, length)
                    // Couldn't ask: as it was, to be asked another time.
                    else if (length == C.LENGTH_UNSET.toLong()) ContentMetadataMutations().set(ContentMetadata.KEY_CONTENT_LENGTH, recheck)
                    else ContentMetadataMutations())
            }
        }
    }

    // ── The player and the look-ahead on the same song ────────────────────

    /** Songs the look-ahead is downloading, by cache key. */
    private val live = ConcurrentHashMap<String, LiveDownload>()
    /** Songs the player is fetching from the server itself, by cache key (how many connections). */
    private val playerFetching = ConcurrentHashMap<String, AtomicInteger>()

    /**
     * True while the player is fetching the song from the server itself
     * (the next song, once the one playing is all in): the look-ahead leaves
     * it, as the cache keeps what the player fetches.  Fetching it as well
     * downloaded it twice, sharing a weak signal, and kept nothing of it.
     */
    fun playerIsFetching(mediaId: String): Boolean = (playerFetching[keyFor(mediaId)]?.get() ?: 0) > 0

    /**
     * The player's way to the server.  For a song the look-ahead is still
     * downloading (skipped to, or come up on a weak signal), the cache can't
     * be read past what's on disk until that download's done, and the player
     * used to fetch the song again alongside it: twice the data on the same
     * signal (the song stopped to load), and what it read while the download
     * held the song wasn't kept (a piece missing for next time).  It takes
     * the song from the download as it comes in instead.
     *
     * The player doesn't learn the song's length when the download that
     * finished it was someone else's, so at the end of what's on disk it
     * asks for more: past the end of a song whose length is known by then,
     * that's answered here (nothing more), not by the server (which
     * transcodes the whole song again to send nothing).
     */
    private class Follow(private val server: DataSource, private val knownLength: (String) -> Long) : DataSource {
        private var download: LiveDownload? = null
        private var pastEnd = false
        private var key: String? = null
        private var spec: DataSpec? = null
        private var position = 0L
        private var remaining = C.LENGTH_UNSET.toLong()

        // The server's reply, while the player is reading the song from it itself:
        /** Where in the song it's been read to. */
        private var replyAt = 0L
        /** How much of it is left, if the server said. */
        private var replyLeft = C.LENGTH_UNSET.toLong()
        /** Nothing's gone wrong with it, and there's more of it to come. */
        private var replySound = false
        /** Closed by the player but held open (see [close]): what lets it go if it isn't taken up again. */
        private var held: ScheduledFuture<*>? = null
        private val lock = Any()

        override fun addTransferListener(transferListener: TransferListener) = server.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            val length = dataSpec.key?.let(knownLength) ?: C.LENGTH_UNSET.toLong()
            if (length != C.LENGTH_UNSET.toLong() && dataSpec.position >= length) {
                letGo()
                pastEnd = true
                spec = dataSpec
                return 0
            }
            if (follows(dataSpec)) return C.LENGTH_UNSET.toLong()
            download = null
            synchronized(lock) {
                val h = held
                if (h != null) {
                    // Back where the reply was left, after a skip: it carries
                    // on, with nothing asked of the server.
                    if (key == dataSpec.key && replyAt == dataSpec.position &&
                        (dataSpec.length == C.LENGTH_UNSET.toLong() || dataSpec.length == replyLeft)) {
                        h.cancel(false)
                        held = null
                        holding -= this
                        return replyLeft
                    }
                    letGo()
                }
            }
            // A song being got ready ahead of its turn isn't asked for while
            // the one playing is being skipped through (see [holdOff]); the
            // look-ahead may have started on it meanwhile.
            if (dataSpec.key != playingKey) {
                holdOff(dataSpec.key)
                if (follows(dataSpec)) return C.LENGTH_UNSET.toLong()
            }
            key = dataSpec.key?.also { playerFetching.getOrPut(it) { AtomicInteger() }.incrementAndGet() }
            try {
                val n = server.open(dataSpec)
                replyAt = dataSpec.position
                replyLeft = n
                // (To the song's end, that is: nothing else is ever asked for.)
                replySound = dataSpec.length == C.LENGTH_UNSET.toLong() || dataSpec.length == n
                return n
            } catch (e: Exception) {
                replySound = false
                done()
                throw e
            }
        }

        /** Takes the song from the look-ahead's download of it, if that's where [dataSpec]'s place in it is. */
        private fun follows(dataSpec: DataSpec): Boolean {
            val d = dataSpec.key?.let { live[it] } ?: return false
            if (!d.covers(dataSpec.position)) return false
            letGo()
            download = d
            spec = dataSpec
            position = dataSpec.position
            remaining = dataSpec.length
            return true
        }

        /**
         * Waits before asking the server for a song that isn't the one
         * playing, while the one playing is being skipped through.  The
         * player gets the next song ready ahead of its turn, and drops it
         * and starts again at every skip within the song that's playing —
         * twice a second while a skip key is held.  Asked for at once, that
         * was a request to the server each time, hung up on half a second
         * later: on a weak signal they queued up behind each other (seconds
         * each just to connect) and the song, when its turn came, waited for
         * them all.  So it isn't asked for until a little after the latest
         * skip ([skippedInSong]).  Over at once if the song becomes the one
         * playing; given up if the player drops it meanwhile.
         */
        private fun holdOff(key: String?) {
            synchronized(playingLock) {
                while (playingKey != key) {
                    val left = (quietUntilNs - System.nanoTime()) / 1_000_000
                    if (left <= 0) break
                    try {
                        playingLock.wait(left)
                    } catch (e: InterruptedException) {
                        throw java.io.InterruptedIOException("dropped before it was asked for")
                    }
                }
            }
        }

        private fun done() {
            key?.let { k -> playerFetching[k]?.let { if (it.decrementAndGet() <= 0) playerFetching.remove(k, it) } }
            key = null
        }

        private fun readReply(buffer: ByteArray, offset: Int, length: Int): Int {
            val n = try {
                server.read(buffer, offset, length)
            } catch (e: Exception) {
                replySound = false
                throw e
            }
            if (n == C.RESULT_END_OF_INPUT) {
                replySound = false
            } else if (n > 0) {
                replyAt += n
                if (replyLeft != C.LENGTH_UNSET.toLong()) replyLeft -= n
            }
            return n
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pastEnd) return if (length == 0) 0 else C.RESULT_END_OF_INPUT
            val d = download ?: return readReply(buffer, offset, length)
            if (length == 0) return 0
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            val want = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remaining).toInt()
            val n = d.read(position, buffer, offset, want)
            if (n == C.RESULT_END_OF_INPUT) return n
            position += n
            if (remaining != C.LENGTH_UNSET.toLong()) remaining -= n
            return n
        }

        override fun getUri(): Uri? = if (download != null || pastEnd) spec?.uri else server.uri

        override fun getResponseHeaders(): Map<String, List<String>> =
            if (download != null || pastEnd) emptyMap() else server.responseHeaders

        /**
         * The player stops reading: it's done with the song, or it's been
         * told to skip to another place in it — which, back or to the start,
         * means reading the song again from the top, from disk, and then
         * asking for the rest of it from where the disk copy ends.  That is
         * exactly where this reply has been read to, so for the song that's
         * playing the reply is held open a few seconds instead of closed, and
         * carried on with when the player comes back for the rest ([open]).
         * (Closed, every skip back in a song still coming dropped its
         * download and asked the server again: on a weak signal, seconds
         * each time to get it going again.)
         */
        override fun close() {
            if (pastEnd) { pastEnd = false; return }
            if (download != null) { download = null; return }
            synchronized(lock) {
                if (held != null) return
                if (replySound && replyLeft != 0L && key != null && key == playingKey) {
                    holding += this
                    held = holder.schedule({ letGo() }, HOLD_REPLY_MS, TimeUnit.MILLISECONDS)
                    return
                }
            }
            closeReply()
        }

        private fun closeReply() {
            replySound = false
            done()
            server.close()
        }

        /** Closes the reply being held open, if one is. */
        fun letGo() {
            synchronized(lock) {
                val h = held ?: return
                h.cancel(false)
                held = null
                holding -= this
                try { closeReply() } catch (_: Exception) {}
            }
        }

        /** Lets the held reply go unless it's [playing]'s. */
        fun letGoUnless(playing: String?) {
            synchronized(lock) { if (held != null && key != playing) letGo() }
        }
    }

    /** The song that's playing, by cache key: its reply from the server is held open across a skip ([Follow.close]). */
    @Volatile private var playingKey: String? = null
    /** The player's ways to the server that are holding a reply open. */
    private val holding: MutableSet<Follow> = ConcurrentHashMap.newKeySet()
    private val holder by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "CarMedia:held-reply").apply { isDaemon = true } }
    }
    /** How long a reply the player stopped reading is held open for it to come back to. */
    private const val HOLD_REPLY_MS = 3_000L
    /** How long after a skip within the song playing the next song isn't asked for ([Follow.holdOff]): longer than between the steps of a held skip key (half a second). */
    private const val HOLD_OFF_MS = 700L
    private val playingLock = Object()
    /** Until when (System.nanoTime) songs being got ready ahead aren't asked for. */
    @Volatile private var quietUntilNs = System.nanoTime()

    /** The song that's playing has just been skipped through: see [Follow.holdOff]. */
    fun skippedInSong() {
        quietUntilNs = System.nanoTime() + HOLD_OFF_MS * 1_000_000
    }

    /**
     * Which song is playing (null: none).  Only its reply is held open when
     * the player stops reading ([Follow.close]); any other song's is let go.
     */
    fun nowPlaying(mediaId: String?) {
        val key = mediaId?.let(::keyFor)
        synchronized(playingLock) {
            playingKey = key
            playingLock.notifyAll()
        }
        // Not on the caller's thread (the main one): hanging up is network work.
        if (holding.isNotEmpty()) holder.execute { for (follow in holding) follow.letGoUnless(key) }
    }

    /** The look-ahead's way to the server: what comes in is also handed to [download], for [Follow]. */
    private class Recording(private val server: DataSource, private val download: LiveDownload) : DataSource {
        private var position = 0L

        override fun addTransferListener(transferListener: TransferListener) = server.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            download.starting(dataSpec.position)
            val length = server.open(dataSpec)
            position = dataSpec.position
            return length
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val n = server.read(buffer, offset, length)
            if (n > 0) {
                download.received(position, buffer, offset, n)
                position += n
            }
            return n
        }

        override fun getUri(): Uri? = server.uri
        override fun getResponseHeaders(): Map<String, List<String>> = server.responseHeaders
        override fun close() = server.close()
    }
}
