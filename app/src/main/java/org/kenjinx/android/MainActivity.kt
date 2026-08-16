package org.kenjinx.android

/**
 * Official kenjinxjni exports Java_org_kenjinx_android_MainActivity_initVm.
 * We are not their UI — this class exists so that call can stash the JavaVM
 * before javaInitialize. Without it later KenjinxNative callbacks have no VM.
 */
class MainActivity {
    external fun initVm()

    companion object {
        fun attachVm() {
            runCatching {
                System.loadLibrary("kenjinxjni")
                MainActivity().initVm()
            }
        }
    }
}
