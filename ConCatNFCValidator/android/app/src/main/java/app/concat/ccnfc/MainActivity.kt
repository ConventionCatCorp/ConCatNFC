package app.concat.ccnfc

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.concat.ccnfc.ui.theme.CCNFCTheme
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.concat.ccnfc.nfc.NDEFDummy
import app.concat.ccnfc.nfc.NFCActivity
import app.concat.ccnfc.nfc.OfflineDummyValidator
import app.concat.ccnfc.views.HomeView
import app.concat.ccnfc.views.ValidatorView
import app.concat.ccnfc.views.ValidatorViewModel

const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {
    private val nfcLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Handle the result
        resultHandler(result)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called")
//        enableEdgeToEdge()
        setContent {
            CCNFCTheme { AppRoot(this, nfcLauncher) }
        }
    }
}

// Mimic React organization structure
// Yes this is not ideal, but I need to focus on speed here a bit
// typescript what have you done to me

@Composable
fun AppRoot(context: Context, nfcLauncher: ActivityResultLauncher<Intent>) {
    var selection by remember {
        mutableIntStateOf(0)
    }

    Scaffold(modifier = Modifier.fillMaxSize(), bottomBar = {
        NavigationBar {
            NavigationBarItem(selection == 0, onClick = { selection = 0 }, icon = { Icon(Icons.Filled.Home, contentDescription = "Home Menu") }, label = { Text("Home") } )
            NavigationBarItem(selection == 1, onClick = { selection = 1; swapToValidate(context, nfcLauncher) }, icon = { Icon(Icons.Filled.Check, contentDescription = "Validation Menu") }, label = { Text("Validate") } )
            NavigationBarItem(selection == 2, onClick = { selection = 2 }, icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings Menu") }, label = { Text("Settings") } )
        }
    }) { innerPadding -> CCContentHandler(Modifier.padding(innerPadding), selection) }
}

private fun resultHandler(result: ActivityResult) {

}

private fun swapToValidate(context: Context, nfcLauncher: ActivityResultLauncher<Intent>) {
    Log.d(TAG, "swapToValidate called")
    val intent = NFCActivity.newIntent(context)
    nfcLauncher.launch(intent)
//    val activity = NFCActivity(OfflineDummyValidator(NDEFDummy()))
//    context.startActivity(activity.intent)
}

@Composable
fun CCContentHandler(modifier: Modifier = Modifier, selection: Int) {
    when (selection) {
        0 -> HomeView(modifier)
        // TODO: Make configurable, of course.
        1 -> ValidatorView(modifier, OfflineDummyValidator(NDEFDummy()), ValidatorViewModel.Companion.ValidatorUIState.PENDING)
    }
}