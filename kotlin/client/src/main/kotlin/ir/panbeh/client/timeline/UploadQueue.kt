package ir.panbeh.client.timeline

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.media
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.clientserverapi.model.media.FileTransferProgress
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.events.m.room.EncryptedFile
import de.connect2x.trixnity.utils.toByteArrayFlow
import io.ktor.http.ContentType
import ir.panbeh.client.errText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

enum class UploadPhase { QUEUED, UPLOADING, FAILED }

/** A file on its way out: shown as a bubble with a progress ring until the message is in the outbox. */
data class UploadState(
    val id: Int,
    val roomId: String,
    val thread: String?,
    val name: String,
    val mimetype: String,
    val size: Long,
    val loaded: Long = 0,
    val total: Long = size,
    val phase: UploadPhase = UploadPhase.QUEUED,
    /** Persian text of why it failed */
    val error: String? = null,
    /** the extra content (voice / video note / album tag ...), e.g. to draw a voice bubble */
    val extra: JsonObject? = null,
) {
    val progress get() = if (phase == UploadPhase.FAILED) 0f else if (loaded == 0L) 0.04f else minOf(1f, loaded.toFloat() / maxOf(1L, total))
}

private class Job1(val roomId: String, val thread: String?, val replyTo: ReplyTarget?, val plan: PlannedFile, val sendAt: Long?)

/**
 * Uploading and sending media: one upload at a time, in order, with progress; cancel and retry. The platform computes the metadata
 * ([MediaInfo], thumbnail, blurhash, waveform ...) and supplies the bytes; encrypted rooms get an encrypted upload (`file`, `thumbnail_file`),
 * others a plain one (`url`, `thumbnail_url`). When the upload is done the message goes to the outbox (or is scheduled for [sendAt]).
 * [onSent] gets the content of each sent message (a sent GIF can be saved to the saved GIFs).
 */
class UploadQueue(
    private val client: MatrixClient,
    private val scope: CoroutineScope,
    private val sender: Sender = Sender(client),
    private val scheduler: Scheduler? = null,
    private val onSent: (roomId: String, content: JsonObject) -> Unit = { _, _ -> },
) {
    private val list = MutableStateFlow<List<UploadState>>(emptyList())
    private val jobs = HashMap<Int, Job>()
    private val specs = HashMap<Int, Job1>()
    private var next = 1

    /** The uploads in flight (and failed ones, until retried or cancelled). */
    val uploads: StateFlow<List<UploadState>> = list.asStateFlow()

    private fun patch(id: Int, f: (UploadState) -> UploadState) = list.update { l -> l.map { if (it.id == id) f(it) else it } }
    private fun remove(id: Int) { list.update { l -> l.filter { it.id != id } }; synchronized(jobs) { jobs.remove(id); specs.remove(id) } }

    /** Queues a batch the way the web composer does (albums, reply on the first, caption on the last of the first album, GIFs). Returns the upload ids. */
    fun sendFiles(roomId: String, files: List<FileToSend>, caption: String? = null, asFile: Boolean = false, asGif: Boolean = false, thread: String? = null, replyTo: ReplyTarget? = null, sendAt: Long? = null): List<Int> =
        planFiles(files, caption, asFile, asGif).map { enqueue(roomId, it, thread, if (it.replyTo) replyTo else null, sendAt) }

    /** Queues one file. [extra] is merged over the generated content (voice, video note ...: see [MediaExtras]). */
    fun enqueue(roomId: String, file: FileToSend, caption: String? = null, extra: JsonObject? = null, asFile: Boolean = false, thread: String? = null, replyTo: ReplyTarget? = null, sendAt: Long? = null): Int =
        enqueue(roomId, PlannedFile(file, extra, caption, replyTo != null, asFile), thread, replyTo, sendAt)

    private fun enqueue(roomId: String, plan: PlannedFile, thread: String?, replyTo: ReplyTarget?, sendAt: Long?): Int {
        val id = synchronized(jobs) { next++ }
        specs[id] = Job1(roomId, thread, replyTo, plan, sendAt)
        list.update { it + UploadState(id, roomId, thread, plan.file.name, plan.file.info.mimetype, plan.file.info.size, extra = plan.extra) }
        run(id)
        return id
    }

    // one worker takes the uploads in the order they were queued (a launch per upload would race)
    private val queue = Channel<Int>(Channel.UNLIMITED)
    init {
        scope.launch {
            for (id in queue) {
                if (specs[id] == null) continue // cancelled while waiting
                val j = launch { attempt(id) }
                synchronized(jobs) { jobs[id] = j }
                j.join()
            }
        }
    }

    private fun run(id: Int) {
        if (specs[id] == null) return
        patch(id) { it.copy(phase = UploadPhase.QUEUED, error = null, loaded = 0) }
        queue.trySend(id)
    }

    private suspend fun attempt(id: Int) {
        val spec = specs[id] ?: return
        patch(id) { it.copy(phase = UploadPhase.UPLOADING, loaded = 0) }
        try {
            val content = upload(id, spec)
            if (spec.sendAt != null && scheduler != null) scheduler.schedule(spec.roomId, content, spec.sendAt)
            else {
                val txn = sender.sendEvent(spec.roomId, "m.room.message", content, spec.thread, spec.replyTo)
                // the next file waits until this one is out, so a batch (an album) arrives in order
                withTimeoutOrNull(20.seconds) { client.room.getOutbox(RoomId(spec.roomId), txn).first { it == null || it.sentAt != null || it.sendError != null } }
            }
            remove(id)
            onSent(spec.roomId, content)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            patch(id) { it.copy(phase = UploadPhase.FAILED, error = errText(e)) }
        }
    }

    /** Stops an upload and removes it. */
    fun cancel(id: Int) {
        synchronized(jobs) { jobs.remove(id) }?.cancel()
        remove(id)
    }

    /** Tries a failed upload again. */
    fun retry(id: Int) { if (list.value.any { it.id == id && it.phase == UploadPhase.FAILED }) run(id) }

    private suspend fun upload(id: Int, spec: Job1): JsonObject {
        val p = spec.plan
        val encrypted = client.room.getById(RoomId(spec.roomId)).first()?.encrypted == true
        val m = p.file.info
        // the poster first, then the file with progress
        val thumb = m.thumbnail?.takeIf { m.mimetype.startsWith("video/") && !p.asFile }?.let { put(it.bytes.toByteArrayFlow(), it.mimetype, encrypted, null) }
        val progress = MutableStateFlow<FileTransferProgress?>(null)
        val watcher = scope.launch { progress.collect { pr -> if (pr != null) patch(id) { it.copy(loaded = pr.transferred, total = pr.total ?: it.total) } } }
        val main = try { put(p.file.open(), m.mimetype, encrypted, progress) } finally { watcher.cancel() }
        return mediaContent(p.file.name, p.caption, m, p.asFile, p.extra, main, thumb)
    }

    private suspend fun put(bytes: Flow<ByteArray>, mimetype: String, encrypted: Boolean, progress: MutableStateFlow<FileTransferProgress?>?): Uploaded {
        val media = client.media
        return if (encrypted) {
            val enc = media.prepareUploadEncryptedMedia(bytes)
            val mxc = media.uploadMedia(enc.url, progress, keepMediaInCache = false).getOrThrow()
            val file = sender.codec.json.encodeToJsonElement(EncryptedFile.serializer(), enc.copy(url = mxc)) as JsonObject
            Uploaded.Encrypted(JsonObject(file + ("mimetype" to JsonPrimitive(mimetype))))
        } else {
            val cache = media.prepareUploadMedia(bytes, ContentType.parse(mimetype))
            Uploaded.Plain(media.uploadMedia(cache, progress, keepMediaInCache = false).getOrThrow())
        }
    }
}
