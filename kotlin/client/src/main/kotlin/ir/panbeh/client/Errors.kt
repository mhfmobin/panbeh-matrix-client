package ir.panbeh.client

import de.connect2x.trixnity.core.ErrorResponse
import de.connect2x.trixnity.core.MatrixServerException
import ir.panbeh.core.admin.errorText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

/** The Matrix errcode of a failed request, e.g. "M_FORBIDDEN". */
val Throwable.errcode: String?
    get() = (this as? MatrixServerException)?.errorResponse?.let { r ->
        (r as? ErrorResponse.CustomErrorResponse)?.errorCode
            ?: runCatching { Json.encodeToJsonElement(ErrorResponse.serializer(), r).jsonObject["errcode"]?.jsonPrimitive?.content }.getOrNull()
    }

/** A Persian message for an error, as the web app's errText. */
fun errText(e: Throwable): String {
    val ex = generateSequence(e) { it.cause }.firstOrNull { it is MatrixServerException } as? MatrixServerException
    if (ex != null) errorText(ex.errcode, ex.retryAfter)?.let { return it }
    val net = generateSequence(e) { it.cause }.any { it is IOException || it::class.simpleName?.contains("Timeout") == true }
    if (net) return "به سرور دسترسی نیست. نشانی و پورت را بررسی کنید."
    return ex?.errorResponse?.error ?: e.message ?: e.toString()
}

/** Login errors: only here does M_FORBIDDEN mean bad credentials. */
fun loginErrText(e: Throwable): String =
    if (generateSequence(e) { it.cause }.any { it.errcode == "M_FORBIDDEN" }) "نام کاربری یا رمز عبور اشتباه است." else errText(e)
