package com.example.nfcproxy

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import com.acs.smartcard.Reader
import com.acs.smartcard.Reader.OnStateChangeListener
import com.example.nfcproxy.ui.theme.NFCProxyTheme

class MainActivity : ComponentActivity() {
    private val TAG: String = "MainActivity"
    private var mManager: UsbManager? = null
    private var mReader: Reader? = null
    private var mPermissionIntent: PendingIntent? = null
    private val ACTION_USB_PERMISSION: String = "com.android.example.USB_PERMISSION"
    private val stateStrings: Array<String?> = arrayOf<String?>(
        "Unknown", "Absent",
        "Present", "Swallowed", "Powered", "Negotiable", "Specific"
    )

    private val mReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            val action = intent.getAction()

            if (ACTION_USB_PERMISSION == action) {
                synchronized(this) {
                    val device = intent.getParcelableExtra<UsbDevice?>(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED, false
                        )
                    ) {
                        if (device != null) {
                            // Open reader

                            Log.d(TAG, "Opening reader: " + device.getDeviceName())
                            Thread(OpenTask(device)).start()
                        }
                    } else {
/*                        logMsg("Permission denied for device " + device)

                        // Enable open button
                        mOpenButton.setEnabled(true)*/
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED == action) {
               /* synchronized(this) {
                    // Update reader list
                    mReaderAdapter.clear()
                    for (device in mManager!!.getDeviceList().values) {
                        if (mReader!!.isSupported(device)) {
                            mReaderAdapter.add(device.getDeviceName())
                        }
                    }

                    val device = intent.getParcelableExtra<UsbDevice?>(UsbManager.EXTRA_DEVICE)
                    if (device != null && device == mReader!!.getDevice()) {
                        // Disable buttons

                        mCloseButton.setEnabled(false)
                        mSlotSpinner.setEnabled(false)
                        mGetStateButton.setEnabled(false)
                        mPowerSpinner.setEnabled(false)
                        mPowerButton.setEnabled(false)
                        mGetAtrButton.setEnabled(false)
                        mT0CheckBox.setEnabled(false)
                        mT1CheckBox.setEnabled(false)
                        mSetProtocolButton.setEnabled(false)
                        mGetProtocolButton.setEnabled(false)
                        mTransmitButton.setEnabled(false)
                        mControlButton.setEnabled(false)
                        mGetFeaturesButton.setEnabled(false)
                        mVerifyPinButton.setEnabled(false)
                        mModifyPinButton.setEnabled(false)
                        mReadKeyButton.setEnabled(false)
                        mDisplayLcdMessageButton.setEnabled(false)

                        // Clear slot items
                        mSlotAdapter.clear()

                        // Close reader
                        logMsg("Closing reader...")
                        Thread(com.acs.readertest.MainActivity.CloseTask()).start()
                    }
                }*/
            }
        }
    }


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

        // Get USB manager
        mManager = UsbReader.getInstance(this).getManager()


        // Initialize reader
        mReader = UsbReader.getInstance(this).getReader()
        mReader?.setOnStateChangeListener(OnStateChangeListener { slotNum: Int, prevState: Int, currState: Int ->
            var prevState = prevState
            var currState = currState
            if (prevState < Reader.CARD_UNKNOWN
                || prevState > Reader.CARD_SPECIFIC
            ) {
                prevState = Reader.CARD_UNKNOWN
            }

            if (currState < Reader.CARD_UNKNOWN
                || currState > Reader.CARD_SPECIFIC
            ) {
                currState = Reader.CARD_UNKNOWN
            }
            if (currState == Reader.CARD_PRESENT) {
                Log.d(TAG, "Card present")

                try {
                    val atr = mReader!!.power(slotNum, Reader.CARD_WARM_RESET)
                    Log.d(TAG, "ATR:" + atr.toString())
                } catch (e: java.lang.Exception) {
                    Log.d(TAG, e.toString())
                }

                try {
                    // Get ATR

                    Log.d(TAG, "Slot " + slotNum + ": Getting ATR...")
                    val atr = mReader!!.getAtr(slotNum)

                    // Show ATR
                    if (atr != null) {
                        Log.d(TAG, "ATR:" + atr.toString())
                    } else {
                        Log.d(TAG, "ATR: None")
                    }
                } catch (e: IllegalArgumentException) {
                    Log.d(TAG, e.toString())
                }

                val activeProtocol = mReader!!.setProtocol(
                    slotNum,
                    Reader.PROTOCOL_T1
                )
                Log.d(TAG, "Active protocol: " + activeProtocol)
                val command = byteArrayOf(0x30, 0x0a)
                var response = ByteArray(65538)

                // Transmit APDU
                val responseLength = mReader!!.transmit(
                    slotNum,
                    command, command.size, response,
                    response.size
                )
                Log.d(TAG, "Response length: " + responseLength)

            }

        })


        // Register receiver for USB permission
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            flags = flags or PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT
        }
        mPermissionIntent = PendingIntentCompat.getBroadcast(
            this, 0,
            Intent(ACTION_USB_PERMISSION), flags, true
        )
        val filter = IntentFilter()
        filter.addAction(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        ContextCompat.registerReceiver(this, mReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        for (device in mManager!!.getDeviceList().values) {
            if (mReader!!.isSupported(device)) {
                // Request permission
                mManager!!.requestPermission(device, mPermissionIntent)
            }
        }

    }

    private inner class OpenTask(private var mDevice: UsbDevice) : Runnable {
        override fun run() {
            val result = doInBackground(mDevice)
            onPostExecute(result)
        }

        fun doInBackground(vararg params: UsbDevice?): Exception? {
            var result: Exception? = null

            try {
                mReader?.open(params[0])
            } catch (e: Exception) {
                result = e
            }

            return result
        }

        fun onPostExecute(result: Exception?) {
            if (result != null) {
                Log.d(TAG, result.toString())
            } else {
                Log.d(TAG, "Reader name: " + mReader?.getReaderName())

                val numSlots: Int? = mReader?.getNumSlots()
                Log.d(TAG, "Number of slots: " + numSlots)
            }
        }
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