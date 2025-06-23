package com.example.nfcproxy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

import com.example.nfcproxy.ui.theme.NFCProxyTheme
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

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
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
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
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    NFCProxyTheme {
        Greeting("Android")
    }
}