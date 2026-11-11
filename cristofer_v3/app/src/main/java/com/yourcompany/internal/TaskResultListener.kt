package com.yourcompany.internal

interface TaskResultListener {
    fun onResult(taskId: Int, isSuccess: Boolean, data: String)
}
