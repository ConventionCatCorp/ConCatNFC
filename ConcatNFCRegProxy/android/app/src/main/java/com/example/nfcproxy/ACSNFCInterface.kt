package com.example.nfcproxy

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat

import com.acs.smartcard.Reader
import com.acs.smartcard.Reader.OnStateChangeListener

fun ByteArray.toHexString(): String {
    return joinToString("") { "%02x".format(it) }
}

class ACSNFCInterface(ctx: Context): NFCInterface(ctx) {
    private var mManager: UsbManager? = null
    private var mReader: Reader? = null
    private val TAG: String = "ACSNFCInterface"
    private val ACTION_USB: String = "com.example.nfcproxy.USB"
    private var mPermissionIntent: PendingIntent? = null
    private var mCardPresent: Boolean = false
    private var mAtr: ByteArray? = null
    private val stateStrings: Array<String?> = arrayOf<String?>(
        "Unknown", "Absent",
        "Present", "Swallowed", "Powered", "Negotiable", "Specific"
    )

    private val mReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            val action = intent.getAction()

            when (action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.d(TAG, "Device detached")
                    val device = intent.getParcelableExtra<UsbDevice?>(UsbManager.EXTRA_DEVICE)
                    if (device == null) {
                        return
                    }
                    Log.d(TAG, "Device attached: " + device.getDeviceName())
                    if (mReader!!.isSupported(device)) {
                        // Request permission
                        mManager!!.requestPermission(device, mPermissionIntent)
                    }
                }
                ACTION_USB -> {
                    synchronized(this) {
                        val device = intent.getParcelableExtra<UsbDevice?>(UsbManager.EXTRA_DEVICE)
                        if (device == null) {
                            return
                        }
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            // Open reader
                            Log.d(TAG, "Opening reader: " + device.getDeviceName())
                            Thread(OpenTask(device)).start()
                        } else {
                            Log.d(TAG, "Permission denied for device: " + device.getDeviceName())
                        }
                    }

                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.d(TAG, "Device detached")
                    val device = intent.getParcelableExtra<UsbDevice?>(UsbManager.EXTRA_DEVICE)
                    if (device == null) {
                        return
                    }
                    Log.d(TAG, "Device detached: " + device.getDeviceName())
                    mReader?.close()
                }
            }
        }
    }

    val stateChangeListener = object : OnStateChangeListener {
        override fun onStateChange(slotNum: Int, prevState: Int, currState: Int) {
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
                mCardPresent = true
                Log.d(TAG, "Card present")

                try {
                    mAtr = mReader!!.power(slotNum, Reader.CARD_WARM_RESET)
                    mAtr?.let { Log.d(TAG, "ATR:" + it.toHexString()) }
                } catch (e: java.lang.Exception) {
                    Log.d(TAG, e.toString())
                }

                try {
                    // Get ATR

                    Log.d(TAG, "Slot " + slotNum + ": Getting ATR...")
                    mAtr = mReader!!.getAtr(slotNum)

                    // Show ATR
                    mAtr?.let { Log.d(TAG, "ATR:" + it.toHexString()) }
                } catch (e: IllegalArgumentException) {
                    Log.d(TAG, e.toString())
                }

                val activeProtocol = mReader!!.setProtocol(
                    slotNum,
                    Reader.PROTOCOL_T1
                )
                Log.d(TAG, "Active protocol: " + activeProtocol)
            } else {
                mCardPresent = false
                Log.d(TAG, "Card removed")
            }
        }
    }

    override fun readPages(pageAddress: Int): ByteArray {
        val command = byteArrayOf(0x30, pageAddress.toByte())
        var response = ByteArray(65538)

        // Transmit APDU
        val responseLength = mReader!!.transmit(
            0,
            command, command.size, response,
            response.size
        )
        return response.copyOfRange(0, responseLength)
    }

    init {
        try {
            // Get USB manager
            mManager = UsbReader.getInstance(mContext).getManager()
            // Initialize reader
            mReader = UsbReader.getInstance(mContext).getReader()
            mReader?.setOnStateChangeListener(stateChangeListener)

            // Register receiver for USB permission
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                flags = flags or PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT
            }
            mPermissionIntent = PendingIntentCompat.getBroadcast(
                mContext, 0,
                Intent(ACTION_USB), flags, true
            )
            val filter = IntentFilter()
            filter.addAction(ACTION_USB)
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            ContextCompat.registerReceiver(mContext, mReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

            for (device in mManager!!.getDeviceList().values) {
                if (mReader!!.isSupported(device)) {
                    // Request permission
                    mManager!!.requestPermission(device, mPermissionIntent)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, e.toString())
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
