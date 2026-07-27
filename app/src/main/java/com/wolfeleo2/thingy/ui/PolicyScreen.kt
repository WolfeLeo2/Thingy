package com.wolfeleo2.thingy.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

const val PRIVACY_POLICY_URL = "https://wolfeleo2.github.io/Thingy/"

/**
 * The privacy policy, in-app.
 *
 * JavaScript stays **off**: the page is written to degrade without it (the reveal animation is the
 * only scripted part), so there's no reason to hand a WebView a script engine for a document.
 * "Open in browser" stays in the app bar on purpose — a WebView shows no URL, and a policy the user
 * can't verify the origin of is worth less than one they can.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PolicyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    val openExternally = {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)))
        }
        Unit
    }

    // In-page anchors (the table of contents) push history entries — walk those back before leaving.
    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy Policy") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = openExternally) {
                        Icon(Icons.Filled.OpenInNew, contentDescription = "Open in browser")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (failed) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Couldn't load the policy — you may be offline.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = { failed = false; loading = true; webView?.loadUrl(PRIVACY_POLICY_URL) }) {
                        Text("Try again")
                    }
                    Button(onClick = openExternally) { Text("Open in browser") }
                }
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            // Transparent until the page paints, so a white flash doesn't punch
                            // through the app's dark theme on load.
                            setBackgroundColor(Color.TRANSPARENT)
                            settings.javaScriptEnabled = false
                            // Lets the page's own prefers-color-scheme rules follow the app theme;
                            // without it a dark-themed app shows a stark white policy page.
                            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): Boolean {
                                    // Only our own policy page belongs in here; mailto: and anything
                                    // else goes to the app that actually handles it.
                                    val url = request.url
                                    if (url.toString().startsWith(PRIVACY_POLICY_URL)) return false
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url)) }
                                    return true
                                }

                                override fun onPageFinished(view: WebView, url: String) {
                                    loading = false
                                }

                                override fun onReceivedError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    error: WebResourceError,
                                ) {
                                    // Subresource failures are noise; only a failed main document
                                    // means the user is looking at nothing.
                                    if (request.isForMainFrame) { loading = false; failed = true }
                                }
                            }
                            loadUrl(PRIVACY_POLICY_URL)
                            webView = this
                        }
                    },
                )
                if (loading) {
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
            }
        }
    }
}
