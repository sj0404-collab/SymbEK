package org.kenjinx.android

/**
 * The official JNI stores the JavaVM and the class lookup context when this
 * exact callback is invoked. PlayerActivity is not the official UI, but the
 * native core still needs this bootstrap before javaInitialize().
 */
class MainActivity {
    external fun initVm()

    companion object {
        fun attachVm(): Boolean = runCatching {
            System.loadLibrary("kenjinxjni")
            MainActivity().initVm()
        }.isSuccess
    }
}
