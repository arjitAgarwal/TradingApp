package com.papertrade.app.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.papertrade.app.ui.AppVM

/**
 * FYERS login inside the app: PIN first (kept encrypted, needed for the silent daily renewal), then FYERS' own login page in
 * a WebView. When FYERS redirects to the registered redirect URI we catch it, read `auth_code`, and never load that URL.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FyersLoginScreen(vm: AppVM, nav: Nav) {
    var pin by remember { mutableStateOf("") }
    var step by remember { mutableStateOf(if (vm.fyersSetUp()) 0 else -1) } // -1 not set up, 0 PIN, 1 web login, 2 finishing
    var error by remember { mutableStateOf<String?>(null) }
    val redirect = remember { vm.fyersRedirectUri() }

    fun handleRedirect(url: String): Boolean {
        if (redirect == null || !url.startsWith(redirect) || step != 1) return false
        val uri = Uri.parse(url)
        val code = uri.getQueryParameter("auth_code")
        if (code.isNullOrBlank()) { error = "FYERS did not complete the login (${uri.getQueryParameter("message") ?: "no auth code"}). Please try again."; step = 0; return true }
        step = 2
        vm.completeFyersLogin(code, pin) { r -> if (r.isSuccess) nav.back() else { error = r.exceptionOrNull()?.message ?: "Login failed"; step = 0 } }
        return true
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Log in to FYERS") }, navigationIcon = { TextButton(nav.back) { Text("←") } }) }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (step) {
                -1 -> Text("FYERS isn't set up on this phone yet. Ask the person who installed the app to fill in the FYERS app details in Account → Market data.",
                    Modifier.padding(16.dp))
                0 -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Enter your FYERS PIN", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    Text("The same 4-digit PIN you use in the FYERS app. It's stored encrypted on this phone so the app can stay logged in each day without asking you again.", fontSize = 13.sp)
                    OutlinedTextField(pin, { if (it.length <= 4 && it.all(Char::isDigit)) pin = it }, label = { Text("PIN") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth().testTag("fyers_pin"))
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                    Button({ error = null; step = 1 }, enabled = pin.length == 4, modifier = Modifier.fillMaxWidth()) { Text("Continue to FYERS login") }
                }
                1 -> LoginWebView(vm.fyersLoginUrl(), ::handleRedirect)
                else -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(); Text("Finishing login…") }
            }
        }
    }
}

/**
 * Upstox login inside the app: Upstox' own login page in a WebView; the redirect carries a one-time `code` that is swapped
 * for today's token. Upstox has no refresh token, so this is needed once a day (the login ends at 3:30 AM).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpstoxLoginScreen(vm: AppVM, nav: Nav) {
    var step by remember { mutableStateOf(if (vm.upstoxSetUp()) 1 else -1) } // -1 not set up, 0 error, 1 web login, 2 finishing
    var error by remember { mutableStateOf<String?>(null) }
    val redirect = remember { vm.upstoxRedirectUri() }

    fun handleRedirect(url: String): Boolean {
        if (redirect == null || !url.startsWith(redirect) || step != 1) return false
        val uri = Uri.parse(url)
        val code = uri.getQueryParameter("code")
        if (code.isNullOrBlank()) { error = "Upstox did not complete the login (${uri.getQueryParameter("error") ?: "no code"}). Please try again."; step = 0; return true }
        step = 2
        vm.completeUpstoxLogin(code) { r -> if (r.isSuccess) nav.back() else { error = r.exceptionOrNull()?.message ?: "Login failed"; step = 0 } }
        return true
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Log in to Upstox") }, navigationIcon = { TextButton(nav.back) { Text("←") } }) }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (step) {
                -1 -> Text("Upstox isn't set up on this phone yet. Fill in the Upstox app details in Account → Market data.", Modifier.padding(16.dp))
                0 -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                    Button({ error = null; step = 1 }, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
                }
                1 -> LoginWebView(vm.upstoxLoginUrl(), ::handleRedirect)
                else -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(); Text("Finishing login…") }
            }
        }
    }
}

/** A broker's login page; [onUrl] returns true for the redirect back to us, which is then never loaded. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LoginWebView(url: String?, onUrl: (String) -> Boolean) = AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
    WebView(ctx).apply {
        settings.javaScriptEnabled = true; settings.domStorageEnabled = true
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = onUrl(request.url.toString())
            // Some redirects skip shouldOverrideUrlLoading; catch them here too and stop the load.
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { if (onUrl(url)) view.stopLoading() else super.onPageStarted(view, url, favicon) }
        }
        url?.let(::loadUrl)
    }
})
