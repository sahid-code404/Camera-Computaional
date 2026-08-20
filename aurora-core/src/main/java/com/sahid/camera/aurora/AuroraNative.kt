package com.sahid.camera.aurora

object AuroraNative {
    init {
        System.loadLibrary("aurora_core")
    }

    fun version(): String = nativeVersion()

    fun selfTest(): Boolean = nativeSelfTest() == 0x4155524F

    private external fun nativeVersion(): String
    private external fun nativeSelfTest(): Int
}
