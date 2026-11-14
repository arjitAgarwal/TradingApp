package com.papertrade.app.data.repo

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.papertrade.app.data.market.ProviderException
import com.papertrade.app.data.market.UpstoxCredentials
import com.papertrade.core.MarketHours
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Upstox login (in-app WebView, [loginUrl] → [completeLogin]).
 *
 * Unlike FYERS there is no refresh token: Upstox access tokens always end at 03:30 IST the next morning, so the user logs in
 * once a day. The token exchange needs the raw API secret, so it is kept in EncryptedSharedPreferences (Android Keystore
 * backed), in its own file so FYERS setup can never wipe it. Endpoints follow https://upstox.com/developer/api-documentation/.
 */
class UpstoxAuth(
    context: Context,
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build(),
    private val apiBase: String = "https://api.upstox.com/v2",
    private val clock: () -> Instant = { Instant.now() },
) : UpstoxCredentials {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context, "secure_credentials_upstox", MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val _state = MutableStateFlow(currentState())
    val state: StateFlow<LoginState> = _state

    private fun str(key: String) = prefs.getString(key, null)?.takeIf { it.isNotBlank() }
    fun redirectUri() = str("redirect_uri")
    fun isSetUp() = str("api_key") != null && str("api_secret") != null && redirectUri() != null

    /** When today's login stops working (03:30 IST after it was made), or null if not logged in. */
    fun loginValidUntil(): Instant? {
        if (str("access_token") == null) return null
        val obtained = Instant.ofEpochMilli(prefs.getLong("access_obtained", 0)).atZone(MarketHours.IST)
        val cutoff = obtained.toLocalDate().atTime(LocalTime.of(3, 30)).atZone(MarketHours.IST)
        return (if (obtained.isBefore(cutoff)) cutoff else cutoff.plusDays(1)).toInstant()
    }

    private fun currentState() = when {
        !isSetUp() -> LoginState.NOT_SET_UP
        loginValidUntil()?.isAfter(clock()) == true -> LoginState.READY
        else -> LoginState.NEEDS_LOGIN
    }
    private fun publish() { _state.value = currentState() }

    /** One-time setup by the app owner, from the Upstox developer apps page. Clears any existing login. */
    fun setup(apiKey: String, secret: String, redirectUri: String) {
        prefs.edit().clear().putString("api_key", apiKey.trim()).putString("api_secret", secret.trim()).putString("redirect_uri", redirectUri.trim()).apply()
        publish()
    }

    fun loginUrl(state: String = "papertrade"): String? {
        val key = str("api_key") ?: return null; val redirect = redirectUri() ?: return null
        return "$apiBase/login/authorization/dialog?response_type=code&client_id=${enc(key)}&redirect_uri=${enc(redirect)}&state=${enc(state)}"
    }

    /** Exchanges the one-time `code` from the login redirect for today's access token. */
    suspend fun completeLogin(code: String): Result<Unit> = runCatching {
        val key = str("api_key"); val secret = str("api_secret"); val redirect = redirectUri()
        if (key == null || secret == null || redirect == null) throw ProviderException("Upstox is not set up", ProviderException.Kind.NOT_CONFIGURED)
        val form = FormBody.Builder().add("code", code).add("client_id", key).add("client_secret", secret).add("redirect_uri", redirect).add("grant_type", "authorization_code").build()
        val body = withContext(Dispatchers.IO) {
            try {
                http.newCall(Request.Builder().url("$apiBase/login/authorization/token").header("Accept", "application/json").post(form).build()).execute().use { it.body?.string().orEmpty() }
            } catch (e: IOException) { throw ProviderException("Network error during Upstox login: ${e.message}", ProviderException.Kind.NETWORK, e) }
        }
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse { throw ProviderException("Unparseable Upstox login response") }
        val access = (obj["access_token"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
            ?: throw ProviderException("Upstox login failed: ${obj["errors"]?.toString()?.take(200) ?: "no access token"}", ProviderException.Kind.AUTH)
        prefs.edit().putString("access_token", access).putLong("access_obtained", clock().toEpochMilli()).apply()
        publish()
    }

    /** Today's token, or null once it has passed 03:30 IST (a new login is then needed). */
    override suspend fun accessToken(): String? {
        val t = str("access_token")
        if (t != null && loginValidUntil()?.isAfter(clock()) == true) return t
        publish(); return null
    }

    /** Upstox rejected the token (e.g. logged in elsewhere, which revokes it): forget it so the UI asks for a login. */
    override suspend fun onTokenRejected(): Boolean { logout(); return false }

    fun logout() { prefs.edit().remove("access_token").remove("access_obtained").apply(); publish() }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
