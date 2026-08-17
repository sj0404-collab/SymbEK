// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import android.os.Build
import java.io.File
import java.security.MessageDigest

/** Describes the official Kenji core embedded by the GitHub Actions build. */
object EngineLoader {
    enum class Engine(val id: String, val label: String) {
        EDEN("eden", "Symbiosis"),
        KENJI("kenji", "Kenji-NX")
    }

    sealed class State {
        object Builtin : State()
        data class Ready(val path: String, val bytes: Long) : State()
        data class Missing(val bytes: Long) : State()
        data class Broken(val reason: String) : State()
    }

    /** Exact arm64 size of official libkenjinx.so 2.1.0-pr.2. */
    val KNOWN_SIZE = mapOf(Engine.KENJI to 48_034_968L)

    /** SHA of the official core pinned by .github/workflows/build.yml. */
    private val EXPECTED_SHA = mapOf(
        Engine.KENJI to "d781048671e4cef1cde2ec15db8fe29b949df9949d83569ceaf776ad12901590"
    )

    /** Android extracts this read-only library from the APK. */
    fun packagedCore(context: Context, engine: Engine): File? {
        if (engine != Engine.KENJI) return null
        val dir = context.applicationInfo.nativeLibraryDir ?: return null
        return listOf("libkenjinx.so", "libkenji.so")
            .map { File(dir, it) }
            .firstOrNull { it.isFile && it.length() > 1_000_000L }
    }

    fun coreFile(context: Context, engine: Engine): File =
        packagedCore(context, engine) ?: File(
            File(context.filesDir, "engines").apply { mkdirs() },
            "lib${engine.id}.so"
        )

    fun state(context: Context, engine: Engine): State {
        if (engine == Engine.EDEN) return State.Builtin
        packagedCore(context, engine)?.let { return State.Ready(it.absolutePath, it.length()) }

        val file = coreFile(context, engine)
        if (!file.exists()) return State.Missing(KNOWN_SIZE[engine] ?: 0L)
        if (file.length() < 1_000_000L) {
            return State.Broken("файл ядра обрезан (${file.length()} Б)")
        }
        val expected = EXPECTED_SHA[engine]
        if (!expected.isNullOrBlank()) {
            val actual = runCatching { sha256(file) }.getOrElse {
                return State.Broken("не удалось прочитать контрольную сумму: ${it.message}")
            }
            if (!actual.equals(expected, ignoreCase = true)) {
                return State.Broken("контрольная сумма ядра не совпала")
            }
        }
        return State.Ready(file.absolutePath, file.length())
    }

    /** The embedded core cannot be removed; only a future downloaded file could be. */
    fun remove(context: Context, engine: Engine): Boolean {
        if (packagedCore(context, engine) != null) return false
        val file = coreFile(context, engine)
        if (!file.exists()) return true
        file.setWritable(true, true)
        return file.delete()
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun deviceSupported(): Boolean =
        Build.SUPPORTED_64_BIT_ABIS.any { it == "arm64-v8a" }
}
