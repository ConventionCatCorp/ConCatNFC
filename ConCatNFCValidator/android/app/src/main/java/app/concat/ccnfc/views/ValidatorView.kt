package app.concat.ccnfc.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.concat.ccnfc.nfc.ValidatorInterface

@Composable
fun ValidatorView(modifier: Modifier = Modifier, validator: ValidatorInterface, state: ValidatorViewModel.Companion.ValidatorUIState) {
    Column (modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        when (state) {
            ValidatorViewModel.Companion.ValidatorUIState.PENDING -> PendingView(modifier)
            ValidatorViewModel.Companion.ValidatorUIState.VALID -> ValidView(modifier)
            ValidatorViewModel.Companion.ValidatorUIState.INVALID -> InvalidView(modifier)
        }
    }
}

@Composable
fun PendingView(modifier: Modifier = Modifier) {
    Text("Pending...")
}

@Composable
fun InvalidView(modifier: Modifier = Modifier) {

}

@Composable
fun ValidView(modifier: Modifier = Modifier) {
    Column {  }
}