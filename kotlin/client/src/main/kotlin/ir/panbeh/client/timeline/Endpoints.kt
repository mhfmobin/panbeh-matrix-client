package ir.panbeh.client.timeline

import de.connect2x.trixnity.client.MatrixClient
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.takeFrom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Requests Trixnity 5.9 doesn't have, or has differently from what Synapse (and the web app) use: the report score, and MSC4140
 * as Synapse implements it (a ?delay query on the normal /send, a list, actions as paths). They go through Trixnity's own
 * authenticated HTTP client, so tokens, refresh and Matrix error mapping (MatrixServerException) apply.
 */

private const val CLIENT = "/_matrix/client/v3"
private const val MSC4140 = "/_matrix/client/unstable/org.matrix.msc4140"

internal fun enc(s: String) = s.encodeURLPathPart()

/** Calls [path] (below the base URL) with an optional raw JSON [body]; returns the response JSON (empty for no body). */
internal suspend fun MatrixClient.call(method: HttpMethod, path: String, body: String? = null, query: Map<String, String> = emptyMap()): JsonObject {
    val res = api.baseClient.baseClient.request {
        this.method = method
        url {
            takeFrom(path)
            query.forEach { (k, v) -> parameters.append(k, v) }
        }
        if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
    }
    val text = res.bodyAsText()
    return if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text) as? JsonObject ?: JsonObject(emptyMap())
}

/** POST /rooms/{roomId}/report/{eventId} with the spec's score (Trixnity's request has only the reason). */
internal suspend fun MatrixClient.reportEvent(roomId: String, eventId: String, score: Int, reason: String?) {
    val b = JsonObject(buildMap { put("score", JsonPrimitive(score)); if (reason != null) put("reason", JsonPrimitive(reason)) }).toString()
    call(HttpMethod.Post, "$CLIENT/rooms/${enc(roomId)}/report/${enc(eventId)}", b)
}

/** PUT /rooms/{roomId}/send/{type}/{txnId}?org.matrix.msc4140.delay=ms → delay_id. [content] is the event content JSON as it goes on the wire. */
internal suspend fun MatrixClient.sendDelayed(roomId: String, type: String, txnId: String, delayMs: Long, content: String): String =
    (call(HttpMethod.Put, "$CLIENT/rooms/${enc(roomId)}/send/${enc(type)}/${enc(txnId)}", content, mapOf("org.matrix.msc4140.delay" to delayMs.toString()))["delay_id"] as kotlinx.serialization.json.JsonPrimitive).content

/** GET /org.matrix.msc4140/delayed_events */
internal suspend fun MatrixClient.listDelayed(): JsonObject = call(HttpMethod.Get, "$MSC4140/delayed_events")

/** POST /org.matrix.msc4140/delayed_events/{id}/{action} with action send | cancel | restart */
internal suspend fun MatrixClient.delayedAction(delayId: String, action: String) {
    call(HttpMethod.Post, "$MSC4140/delayed_events/${enc(delayId)}/$action", "{}")
}
