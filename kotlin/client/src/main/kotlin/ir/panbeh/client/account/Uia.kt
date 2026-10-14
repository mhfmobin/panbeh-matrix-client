package ir.panbeh.client.account

import de.connect2x.trixnity.clientserverapi.client.UIA
import de.connect2x.trixnity.clientserverapi.model.uia.AuthenticationRequest
import de.connect2x.trixnity.clientserverapi.model.authentication.IdentifierType
import de.connect2x.trixnity.core.MatrixServerException
import de.connect2x.trixnity.core.model.UserId
import io.ktor.http.HttpStatusCode

/** The server wants the account password for this action and none was given. */
class NeedsPassword : Exception("رمز عبور لازم است")

/** The password given for user-interactive auth was refused. */
class WrongPassword : Exception("رمز عبور درست نیست")

/**
 * Completes a user-interactive-auth request like the web app's withPassword: the call was already tried without auth;
 * if the server wants more, answer with m.login.password, or throw [NeedsPassword] when there is no password.
 */
suspend fun <T> UIA<T>.withPassword(userId: UserId, password: String?): T {
    var r: UIA<T> = this
    var tried = false
    while (true) when (val cur = r) {
        is UIA.Success -> return cur.value
        is UIA.Step -> {
            if (password == null) throw NeedsPassword()
            check(!tried) { "unexpected extra authentication step" }
            tried = true
            r = authenticate(cur.authenticate(AuthenticationRequest.Password(IdentifierType.User(userId.full), password)))
        }
        is UIA.Error -> throw if (tried) WrongPassword() else IllegalStateException(cur.errorResponse.error)
    }
}

private fun <T> authenticate(r: Result<UIA<T>>): UIA<T> =
    r.getOrElse { e ->
        val code = (e as? MatrixServerException)?.statusCode
        throw if (code == HttpStatusCode.Unauthorized || code == HttpStatusCode.Forbidden) WrongPassword() else e
    }
