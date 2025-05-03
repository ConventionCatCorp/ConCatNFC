package app.concat.ccnfc

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.concat.ccnfc.ui.theme.CCNFCTheme
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.NavigationBarItem

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CCNFCTheme { AppRoot() }
        }
    }
}

// Mimic React organization structure
// Yes this is not ideal, but I need to focus on speed here a bit
// typescript what have you done to me

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Preview
@Composable
fun AppRoot() {
    Scaffold(modifier = Modifier.fillMaxSize(), bottomBar = {
        CCBottomNavBar()
    }) { }
}

var selection = 0;

@Composable
fun CCBottomNavBar() {
    NavigationBar {
        NavigationBarItem(selection == 0, onClick = { /* do something */ selection = 0 }, icon = { Icon(Icons.Filled.Home, contentDescription = "Home Menu") } )
        NavigationBarItem(selection == 1, onClick = { /* do something */ selection = 1 }, icon = { Icon(Icons.Filled.Check, contentDescription = "Validation Menu") } )
    }
}

@Composable
fun CCBottomBar() {
    BottomAppBar (
        actions = {
            IconButton(onClick = { /* do something */ }) {
                Icon(Icons.Filled.Check, contentDescription = "Localized description")
            }
            IconButton(onClick = { /* do something */ }) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "Localized description",
                )
            }
            IconButton(onClick = { /* do something */ }) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Localized description",
                )
            }
            IconButton(onClick = { /* do something */ }) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Localized description",
                )
            }
        }
    )
}