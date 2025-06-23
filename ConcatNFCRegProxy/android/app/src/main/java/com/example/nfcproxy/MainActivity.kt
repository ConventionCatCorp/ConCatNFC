package com.example.nfcproxy

import android.os.Bundle
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.nfcproxy.ui.theme.NFCProxyTheme
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import android.content.Context
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    private val TAG: String = "MainActivity"
    private var mNfcInterface: NFCInterface? = null
    private lateinit var server: EmbeddedServer<*, *>


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NFCProxyTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainWebView(modifier = Modifier.padding(innerPadding))
                }
            }
        }

        mNfcInterface = ACSNFCInterface(this)
        startServer()
    }
    private fun startServer() {
        server = embeddedServer(Netty, port = 7070, host = "0.0.0.0") {
            module(mNfcInterface!!)
        }
        server.start()
    }

}

@Composable
fun MainWebView(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences("webview_prefs", Context.MODE_PRIVATE)
    }
    val initialUrl = remember {
        prefs.getString("last_url", "https://reg.concat.app") ?: "https://reg.concat.app"
    }

    var url by remember { mutableStateOf(initialUrl) }
    var text by remember { mutableStateOf(url) }
    var isLoading by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { webView?.goBack() }, enabled = canGoBack) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back"
                )
            }
            IconButton(onClick = { webView?.goForward() }, enabled = canGoForward) {
                Icon(
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = "Forward"
                )
            }
            IconButton(onClick = {
                if (isLoading) {
                    webView?.stopLoading()
                } else {
                    webView?.reload()
                }
            }) {
                Icon(
                    imageVector = if (isLoading) Icons.Default.Close else Icons.Default.Refresh,
                    contentDescription = if (isLoading) "Stop" else "Reload"
                )
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                label = { Text("URL") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    val newUrl = if (URLUtil.isValidUrl(text)) {
                        text
                    } else {
                        "https://www.google.com/search?q=$text"
                    }
                    url = newUrl
                })
            )
        }

        AndroidView(
            modifier = Modifier.weight(1f),
            factory = { context ->
                WebView.setWebContentsDebuggingEnabled(true)
                WebView(context).apply {
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            isLoading = true
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            isLoading = false
                            canGoBack = view?.canGoBack() ?: false
                            canGoForward = view?.canGoForward() ?: false
                            if (url != null && (URLUtil.isHttpUrl(url) || URLUtil.isHttpsUrl(url))) {
                                text = url
                                prefs.edit().putString("last_url", url).apply()
                            }
                        }
                    }
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    loadUrl(url)
                    webView = this
                }
            },
            update = { wv ->
                webView = wv
                wv.loadUrl(url)
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
fun MainWebViewPreview() {
    NFCProxyTheme {
        MainWebView()
    }
}