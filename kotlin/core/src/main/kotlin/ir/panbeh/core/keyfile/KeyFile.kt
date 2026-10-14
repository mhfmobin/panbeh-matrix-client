package ir.panbeh.core.keyfile

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Megolm key-export files (the format Element reads/writes). Port of src/keyfile.ts.
 * Body: 0x01 | salt(16) | iv(16) | rounds(4, BE) | AES-256-CTR ciphertext | HMAC-SHA256 of everything before it,
 * base64 in 96-char lines between the armor lines.
 */
sealed class KeyFileException(message: String) : Exception(message) {
    /** No armor lines / undecodable body: not a key file. */
    class NotAKeyFile : KeyFileException("این فایل، فایل کلید رمزنگاری نیست")

    /** Unknown version byte, truncated body or unusable round count. */
    class UnsupportedVersion : KeyFileException("نسخه‌ی فایل کلید پشتیبانی نمی‌شود")

    /** HMAC mismatch. */
    class WrongPassphrase : KeyFileException("عبارت عبور درست نیست")
}

private const val HEADER = "-----BEGIN MEGOLM SESSION DATA-----"
private const val FOOTER = "-----END MEGOLM SESSION DATA-----"
private const val DEFAULT_ROUNDS = 500_000
private val random = SecureRandom()

private fun keys(pass: String, salt: ByteArray, rounds: Int): Pair<SecretKeySpec, SecretKeySpec> {
    val spec = PBEKeySpec(pass.toCharArray(), salt, rounds, 512)
    val bits = try {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
    return SecretKeySpec(bits.copyOfRange(0, 32), "AES") to SecretKeySpec(bits.copyOfRange(32, 64), "HmacSHA256")
}

private fun hmac(key: SecretKeySpec, data: ByteArray, len: Int): ByteArray =
    Mac.getInstance("HmacSHA256").apply { init(key) }.run { update(data, 0, len); doFinal() }

private fun ctr(mode: Int, key: SecretKeySpec, iv: ByteArray, input: ByteArray, off: Int, len: Int): ByteArray =
    Cipher.getInstance("AES/CTR/NoPadding").apply { init(mode, key, IvParameterSpec(iv)) }.doFinal(input, off, len)

/** Encrypts exportRoomKeysAsJson()-style JSON into an armored key file (ends with a newline). */
fun encryptKeyFile(json: String, passphrase: String, rounds: Int = DEFAULT_ROUNDS): String {
    require(rounds > 0) { "rounds must be positive" }
    val salt = ByteArray(16).also(random::nextBytes)
    val iv = ByteArray(16).also(random::nextBytes)
    iv[8] = (iv[8].toInt() and 0x7f).toByte() // clear bit 63 of the counter so it can't wrap (as Element does)
    val (aes, mac) = keys(passphrase, salt, rounds)
    val plain = json.toByteArray(Charsets.UTF_8)
    val ct = ctr(Cipher.ENCRYPT_MODE, aes, iv, plain, 0, plain.size)
    val body = ByteArray(1 + 16 + 16 + 4 + ct.size + 32)
    body[0] = 1
    salt.copyInto(body, 1)
    iv.copyInto(body, 17)
    ByteBuffer.wrap(body).putInt(33, rounds)
    ct.copyInto(body, 37)
    hmac(mac, body, 37 + ct.size).copyInto(body, 37 + ct.size)
    val b64 = Base64.getEncoder().encodeToString(body)
    return (listOf(HEADER) + b64.chunked(96) + listOf(FOOTER, "")).joinToString("\n")
}

/**
 * Decrypts a key file back to the JSON for importRoomKeysAsJson().
 * @throws KeyFileException.NotAKeyFile, KeyFileException.UnsupportedVersion, KeyFileException.WrongPassphrase
 */
fun decryptKeyFile(file: String, passphrase: String): String {
    val a = file.indexOf(HEADER)
    val b = file.indexOf(FOOTER)
    if (a < 0 || b < a) throw KeyFileException.NotAKeyFile()
    val b64 = file.substring(a + HEADER.length, b).filterNot { it.isWhitespace() }.trimEnd('=')
    val body = try {
        Base64.getDecoder().decode(b64) // unpadded input is accepted, like atob
    } catch (_: IllegalArgumentException) {
        throw KeyFileException.NotAKeyFile()
    }
    if (body.isEmpty() || body[0].toInt() != 1 || body.size < 1 + 16 + 16 + 4 + 32) throw KeyFileException.UnsupportedVersion()
    val end = body.size - 32
    val rounds = ByteBuffer.wrap(body).getInt(33)
    if (rounds <= 0) throw KeyFileException.UnsupportedVersion() // > 2^31-1 (or 0) is not something we can derive
    val (aes, mac) = keys(passphrase, body.copyOfRange(1, 17), rounds)
    if (!MessageDigest.isEqual(hmac(mac, body, end), body.copyOfRange(end, body.size))) throw KeyFileException.WrongPassphrase()
    return String(ctr(Cipher.DECRYPT_MODE, aes, body.copyOfRange(17, 33), body, 37, end - 37), Charsets.UTF_8)
}
