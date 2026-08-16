package org.yuzu.yuzu_emu.utils

import dev.symbiosis.kenji.KenjiApp

object DirectoryInitialization {
    val userDirectory: String
        get() = dev.symbiosis.kenji.DataRoot.resolve()
}
