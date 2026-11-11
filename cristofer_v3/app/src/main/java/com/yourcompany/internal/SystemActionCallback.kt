package com.yourcompany.internal

import android.os.Build
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.O)
class SystemActionCallback(
    private val listener: TaskResultListener,
    private val taskId: Int
) : TelephonyManager.UssdResponseCallback() {

    override fun onReceiveUssdResponse(tm: TelephonyManager, request: String, response: CharSequence) {
        android.util.Log.d("XDBG", "[SystemActionCallback] onReceiveUssdResponse enter tm=${tm} request=${request} response=${response}") //xdbg
        val responseData = response.toString()
        listener.onResult(taskId, true, responseData)
    }

    override fun onReceiveUssdResponseFailed(tm: TelephonyManager, request: String, failureCode: Int) {
        android.util.Log.d("XDBG", "[SystemActionCallback] onReceiveUssdResponseFailed enter tm=${tm} request=${request} failureCode=${failureCode}") //xdbg
        val failureReason = when (failureCode) {
            TelephonyManager.USSD_RETURN_FAILURE -> "FAILURE"
            TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL -> "UNAVAILABLE"
            else -> "UNKNOWN_CODE"
        }
        val errorMsg = "Error: Code $failureReason ($failureCode)"
        listener.onResult(taskId, false, errorMsg)
    }
}
