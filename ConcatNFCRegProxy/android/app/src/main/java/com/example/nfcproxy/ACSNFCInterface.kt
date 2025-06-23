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

fun bytes(vararg values: Int) = ByteArray(values.size) { i -> values[i].toByte() }


class ACSNFCInterface(ctx: Context): NFCInterface(ctx) {
    private var mManager: UsbManager? = null
    private var mReader: Reader? = null
    private val TAG: String = "ACSNFCInterface"
    private val ACTION_USB: String = "com.example.nfcproxy.USB"
    private var mPermissionIntent: PendingIntent? = null
    private var mAtr: ByteArray? = null
    private val OPERATION_GET_SUPPORTED_CARD_SIGNATURE = bytes(0x3B, 0x8F, 0x80, 0x1, 0x80, 0x4F, 0xC, 0xA0, 0x0, 0x0, 0x3, 0x6, 0x3, 0x0, 0x3)
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
                    sendEvent("Reader error")
                    mReader?.close()
                    mReaderOpened = false
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
                setCardPresent(true)
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
                if (mAtr == null) {
                    mCardSupported = false
                    return
                }
                // Check for supported card
                if (mAtr!!.copyOfRange(0, OPERATION_GET_SUPPORTED_CARD_SIGNATURE.size).contentEquals(
                    OPERATION_GET_SUPPORTED_CARD_SIGNATURE)) {
                    mCardSupported = true
                } else {
                    mCardSupported = false
                }
                sendEvent("Card present")
            } else {
                sendEvent("Card NOT present")
                setCardPresent(false)
                Log.d(TAG, "Card removed")
            }
        }
    }

    override fun transmitAndValidate(command: ByteArray, validate: Boolean): ByteArray {
        if (!getCardPresent() || !mCardSupported) {
            throw NFCInterfaceException("Card not present or not supported")
        }
        var response = ByteArray(256)

        // Transmit APDU
        val responseLength = mReader!!.transmit(
            0,
            command, command.size, response,
            response.size
        )
        if (validate) {
            if (responseLength < 2 || response[responseLength - 2] != 0x90.toByte()) {
                throw NFCInterfaceException("Invalid response")
            }
            return response.copyOfRange(0, responseLength - 2)
        } else {
            return response.copyOfRange(0, responseLength)
        }
    }

    override fun GetUUID(): String {
        val response = transmitAndValidate(bytes(0xFF, 0xCA, 0x00, 0x00, 0x00))
        return response.toHexString()
    }

    override fun readPages(pageAddress: Int): ByteArray {
        if (!getCardPresent() || !mCardSupported) {
            throw NFCInterfaceException("Card not present or not supported")
        }
        val command = byteArrayOf(0x30, pageAddress.toByte())
        var response = ByteArray(65538)

        // Transmit APDU
        val responseLength = mReader!!.transmit(
            0,
            command, command.size, response,
            response.size
        )
        return response.copyOfRange(0, responseLength)
/*
        // I don't know why this is not working...
        val command = bytes(0xff, 0xb0, 0x00, pageAddress, 0x10)
        val response = transmitAndValidate(command)
        return response.copyOfRange(0, response.size)
*/
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
                mReaderOpened = true

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
