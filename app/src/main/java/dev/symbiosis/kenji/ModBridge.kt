package dev.symbiosis.kenji

import android.os.Environment
import android.system.ErrnoException
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Моды: папка Eden → раскладка Kenji, без второй копии.
 *
 * ЧТО ПРОВЕРЕНО В САМОМ ЯДРЕ (строки вынуты из libkenjinx.so 2.1.0-pr.2,
 * не взяты из головы):
 *
 *     RomfsDir       = "romfs"
 *     ExefsDir       = "exefs"
 *     CheatDir       = "cheats"
 *     AmsContentsDir = "contents"
 *     AmsNsoPatchDir = "exefs_patches"
 *     RomfsContainer = "romfs.bin"
 *     ExefsContainer = "exefs.nsp"
 *
 * а рядом с ними имена локальных переменных загрузчика:
 * `modsBasePath`, `contentsDir`, `patchDir`, `cheatsDir`.
 *
 * Отсюда следует важное: ВНУТРЕННЯЯ раскладка мода у Kenji и у Eden
 * совпадает буква в букву, потому что обе взяты у Atmosphere:
 *
 *     <корень модов>/contents/<TitleId>/romfs/...
 *     <корень модов>/contents/<TitleId>/exefs/...
 *     <корень модов>/exefs_patches/<имя>/...
 *
 * Различается ТОЛЬКО имя корневой папки:
 *
 *     Eden  : <data>/load/<TitleId>/<имя мода>/romfs/...
 *     Kenji : <data>/mods/contents/<TitleId>/<имя мода>/romfs/...
 *
 * Поэтому мод не нужно ни распаковывать заново, ни копировать: хватает
 * жёсткой ссылки на те же файлы, как это уже делает FirmwareBridge для
 * прошивки. Копия - только если ссылка невозможна (разные разделы).
 */
object ModBridge {

    private const val EDEN_LOAD = "load"
    private const val KENJI_MODS = "mods/contents"

    fun edenLoad(root: File) = File(root, EDEN_LOAD)
    fun kenjiMods(root: File) = File(root, KENJI_MODS)

    /** TitleId - 16 шестнадцатеричных цифр. Всё прочее в load/ не мод. */
    private val TITLE_ID = Regex("^[0-9A-Fa-f]{16}$")

    data class Title(val id: String, val mods: Int)

    /** Что лежит в load/ у Eden. */
    fun edenTitles(root: File): List<Title> {
        val dir = edenLoad(root)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isDirectory && TITLE_ID.matches(it.name) }
            ?.map { t ->
                Title(t.name.uppercase(), t.listFiles()?.count { it.isDirectory } ?: 0)
            }
            ?.filter { it.mods > 0 }
            ?: emptyList()
    }

    /** Что уже разложено для Kenji. */
    fun kenjiTitles(root: File): List<Title> {
        val dir = kenjiMods(root)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isDirectory && TITLE_ID.matches(it.name) }
            ?.map { t ->
                Title(t.name.uppercase(), t.listFiles()?.count { it.isDirectory } ?: 0)
            }
            ?: emptyList()
    }

    /**
     * Разложить моды Eden в вид Kenji. Безопасно звать на каждом старте:
     * уже связанное пропускается.
     */
    fun auto(root: File = File(DataRoot.resolve()), allowCopy: Boolean = true): JSONObject {
        val src = edenLoad(root)
        if (!src.isDirectory) {
            return JSONObject().put("ok", true).put("skipped", true)
                .put("message", "папки load/ нет — моды Eden не найдены")
        }
        val dest = kenjiMods(root)
        dest.mkdirs()

        var linked = 0
        var copied = 0
        var skipped = 0
        var failed = 0
        var titles = 0

        for (title in src.listFiles().orEmpty()) {
            if (!title.isDirectory || !TITLE_ID.matches(title.name)) continue
            titles++
            val outTitle = File(dest, title.name.uppercase())
            for (mod in title.listFiles().orEmpty()) {
                if (!mod.isDirectory) continue
                val outMod = File(outTitle, mod.name)
                when (mirror(mod, outMod, allowCopy)) {
                    Result.LINKED -> linked++
                    Result.COPIED -> copied++
                    Result.SKIPPED -> skipped++
                    Result.FAILED -> failed++
                }
            }
        }

        val ready = kenjiTitles(root).isNotEmpty()
        return JSONObject()
            .put("ok", ready || titles == 0)
            .put("titles", titles)
            .put("linked", linked)
            .put("copied", copied)
            .put("skipped", skipped)
            .put("failed", failed)
            .put("source", src.absolutePath)
            .put("dest", dest.absolutePath)
            .put(
                "message",
                when {
                    titles == 0 -> "в load/ нет папок с TitleId — модов нет"
                    linked + copied == 0 && skipped > 0 -> "моды уже разложены ($skipped)"
                    else -> "моды: $titles игр, связано ${linked + copied}" +
                        (if (copied > 0) " (копий $copied)" else " (ссылками, места не заняли)") +
                        (if (failed > 0) ", ошибок $failed" else "")
                }
            )
    }

    private enum class Result { LINKED, COPIED, SKIPPED, FAILED }

    /**
     * Отражает дерево мода. Файлы - ссылками, папки - создаются.
     *
     * Каталог нельзя связать жёсткой ссылкой на Linux, поэтому дерево
     * воспроизводится, а листья связываются. Мод весит десятки мегабайт
     * (текстуры), так что копия - это заметное место на телефоне.
     */
    private fun mirror(from: File, to: File, allowCopy: Boolean): Result {
        var linked = 0
        var copied = 0
        var failed = 0
        var same = 0

        from.walkTopDown().forEach { f ->
            val rel = f.relativeToOrNull(from) ?: return@forEach
            val target = if (rel.path.isEmpty()) to else File(to, rel.path)
            if (f.isDirectory) {
                target.mkdirs()
                return@forEach
            }
            if (target.isFile && target.length() == f.length()) {
                same++
                return@forEach
            }
            target.parentFile?.mkdirs()
            if (target.exists()) target.delete()
            if (tryLink(f, target)) {
                linked++
            } else if (allowCopy && tryCopy(f, target)) {
                copied++
            } else {
                failed++
            }
        }

        return when {
            failed > 0 -> Result.FAILED
            copied > 0 -> Result.COPIED
            linked > 0 -> Result.LINKED
            same > 0 -> Result.SKIPPED
            else -> Result.SKIPPED
        }
    }

    private fun tryLink(src: File, dest: File): Boolean = try {
        Os.link(src.absolutePath, dest.absolutePath)
        dest.isFile && dest.length() == src.length()
    } catch (_: ErrnoException) {
        false
    } catch (_: Throwable) {
        false
    }

    private fun tryCopy(src: File, dest: File): Boolean = try {
        val part = File(dest.parentFile, "${dest.name}.part-${System.nanoTime()}")
        src.inputStream().use { input -> part.outputStream().use { output -> input.copyTo(output) } }
        if (!part.isFile || part.length() != src.length()) {
            part.delete()
            false
        } else {
            if (dest.exists()) dest.delete()
            val moved = part.renameTo(dest)
            if (!moved) part.delete()
            moved && dest.isFile && dest.length() == src.length()
        }
    } catch (_: Throwable) {
        false
    }

    /**
     * Список модов для панели: настоящие данные, а не пустой массив.
     *
     * Показываются обе стороны - что нашлось у Eden и что уже видит
     * Kenji, потому что «мод есть, но не работает» почти всегда значит
     * «лежит в load/, а Kenji туда не смотрит».
     */
    fun listJson(): String {
        val root = File(DataRoot.resolve())
        val items = JSONArray()

        val kenji = kenjiTitles(root).associateBy { it.id }
        val eden = edenTitles(root)

        for (t in eden) {
            val here = kenji[t.id]
            items.put(
                JSONObject()
                    .put("titleId", t.id)
                    .put("name", t.id)
                    .put("mods", t.mods)
                    .put("source", "Eden load/")
                    .put("active", here != null)
                    .put(
                        "detail",
                        if (here != null) "разложен для Kenji (${here.mods})"
                        else "только в load/ — нажмите «Мост модов»"
                    )
            )
        }
        // Моды, которых в load/ нет, но в mods/ есть - положены вручную.
        for ((id, t) in kenji) {
            if (eden.any { it.id == id }) continue
            items.put(
                JSONObject()
                    .put("titleId", id)
                    .put("name", id)
                    .put("mods", t.mods)
                    .put("source", "Kenji mods/")
                    .put("active", true)
                    .put("detail", "лежит прямо в mods/contents")
            )
        }

        return JSONObject()
            .put("path", kenjiMods(root).absolutePath)
            .put("edenPath", edenLoad(root).absolutePath)
            .put("items", items)
            .put(
                "note",
                "Kenji читает mods/contents/<TitleId>/<мод>/romfs. У Eden то же самое " +
                    "лежит в load/<TitleId>/<мод>/romfs — внутри одинаково, отличается " +
                    "только имя верхней папки, поэтому связывается ссылками без копий."
            )
            .toString()
    }
}
