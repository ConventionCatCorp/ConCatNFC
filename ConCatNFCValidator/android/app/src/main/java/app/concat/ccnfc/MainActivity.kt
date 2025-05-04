package app.concat.ccnfc

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.concat.ccnfc.views.HomeView
import app.concat.ccnfc.views.ValidatorView

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

@Preview
@Composable
fun AppRoot() {
    var selection by remember {
        mutableIntStateOf(0)
    }

    Scaffold(modifier = Modifier.fillMaxSize(), bottomBar = {
        NavigationBar {
            NavigationBarItem(selection == 0, onClick = { selection = 0 }, icon = { Icon(Icons.Filled.Home, contentDescription = "Home Menu") }, label = { Text("Home") } )
            NavigationBarItem(selection == 1, onClick = { selection = 1 }, icon = { Icon(Icons.Filled.Check, contentDescription = "Validation Menu") }, label = { Text("Validate") } )
        }
    }) { innerPadding -> CCContentHandler(Modifier.padding(innerPadding), selection) }
}

@Composable
fun CCContentHandler(modifier: Modifier = Modifier, selection: Int) {
    when (selection) {
        0 -> HomeView(modifier)
        1 -> ValidatorView(modifier)
    }
}