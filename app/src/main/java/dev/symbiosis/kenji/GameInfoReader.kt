package dev.symbiosis.kenji

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import org.json.JSONObject
import java.io.File
import org.kenjinx.android.Kenji

/**
 * Настоящее имя игры и её обложка - из самого ядра.
 *
 * До этого мост отвечал заглушками: icon()/cover() возвращали пустую
 * строку, а название бралось из имени файла. В списке было
 * «Blade Chimera [01007FC01CF4E000][v0] (0.28 GB)» и серый квадрат,
 * хотя ядро умеет отдать «Blade Chimera» и её иконку.
 *
 * Как это делает официальная оболочка (проверено в их APK):
 *
 *     deviceGetGameInfo(fileDescriptor, extension, GameInfo)
 *
 * заполняет объект с полями TitleName, TitleId, Developer, Version,
 * FileSize и Icon - строка base64. JNA умеет заполнять такую структуру
 * по ссылке, но для этого нужен класс с ТОЧНО такими же полями и в том
 * же порядке: имена и порядок сверены с GameInfo из classes3.dex.
 *
 * Кэш обязателен. Разбор заголовка ROM - это чтение и расшифровка
 * файла, десятки миллисекунд на игру; список зовёт icon() на каждую
 * плитку при каждой перерисовке. Без кэша прокрутка встанет.
 */
object GameInfoReader {

    /** Соответствует org.kenjinx.android.viewmodels.GameInfo. Порядок полей важен. */
    class GameInfo : com.sun.jna.Structure() {
        // The native ABI starts with a double, then five char* pointers.
        // Keep this order identical to the official GameInfo.java; JNA uses
        // it as the physical memory layout, not merely as a Kotlin property
        // order.
        @JvmField var FileSize: Double = 0.0
        @JvmField var TitleName: String? = null
        @JvmField var TitleId: String? = null
        @JvmField var Developer: String? = null
        @JvmField var Version: String? = null
        @JvmField var Icon: String? = null

        override fun getFieldOrder(): List<String> =
            listOf("FileSize", "TitleName", "TitleId", "Developer", "Version", "Icon")
    }

    data class Info(
        val title: String,
        val titleId: String,
        val developer: String,
        val version: String,
        val iconBase64: String
    )

    private val cache = HashMap<String, Info>()
    private val failed = HashSet<String>()

    /**
     * ЯДРО ЗДЕСЬ ТРОГАТЬ НЕЛЬЗЯ, ЕСЛИ ОНО НЕ ПОДНЯТО.
     *
     * Прошлая правка (названия и обложки из deviceGetGameInfo) сломала
     * приложение: лаунчер зовёт games()/icon() сразу при показе списка,
     * а ядро в процессе лаунчера НЕ инициализировано - javaInitialize()
     * выполняется только в :player, перед запуском игры.
     *
     * Вызов C-функции ядра до javaInitialize роняет процесс целиком:
     * это нативный сбой, а не Java-исключение, и runCatching его не
     * ловит. Отсюда «подключил папку - вылетело и больше не
     * запускается»: папка сохранилась, при следующем старте список
     * читается снова, снова дёргает ядро - и снова падает. Замкнутый
     * круг, из которого нельзя выйти, не очистив данные.
     *
     * Поэтому: в лаунчере метаданные из ядра НЕ читаются. Флаг
     * выставляет только PlayerActivity, где ядро уже поднято.
     */
    @Volatile
    private var coreReady = false

    /** Зовётся из :player после успешного javaInitialize. */
    fun coreIsUp() { coreReady = true }

    @Synchronized
    fun clear() {
        cache.clear()
        failed.clear()
    }

    /**
     * Прочитать сведения об игре. null, если ядро не смогло.
     *
     * Повторно не пытаемся: файл без ключей не станет читаемым сам по
     * себе, а список зовёт это постоянно.
     */
    @Synchronized
    fun read(context: Context, path: String): Info? {
        cache[path]?.let { return it }
        if (path in failed) return null
        // Без поднятого ядра - молча ничего. Имя файла лучше, чем
        // мёртвое приложение.
        if (!coreReady) return null

        val ext = path.substringAfterLast('.', "").lowercase().substringBefore('?')
        val pfd = openRom(context, path)
        if (pfd == null) {
            failed.add(path)
            return null
        }

        val info = try {
            val gi = GameInfo()
            Kenji.core.deviceGetGameInfo(pfd.fd, ext, gi)
            val name = gi.TitleName?.trim().orEmpty()
            if (name.isEmpty() && gi.Icon.isNullOrEmpty()) null
            else Info(
                title = name,
                titleId = gi.TitleId?.trim().orEmpty(),
                developer = gi.Developer?.trim().orEmpty(),
                version = gi.Version?.trim().orEmpty(),
                iconBase64 = gi.Icon.orEmpty()
            )
        } catch (t: Throwable) {
            android.util.Log.w("KenjiSpace", "deviceGetGameInfo не смог $path: ${t.message}")
            null
        } finally {
            runCatching { pfd.close() }
        }

        if (info == null) failed.add(path) else cache[path] = info
        return info
    }

    /** Только иконка, как её ждёт панель: голый base64 без префикса. */
    fun icon(context: Context, path: String): String {
        val raw = read(context, path)?.iconBase64.orEmpty()
        if (raw.isEmpty()) return ""
        // Ядро может вернуть уже готовый data-URL; панель подставляет
        // префикс сама, поэтому лишний надо снять.
        return raw.substringAfterLast(',').trim()
    }

    private fun openRom(context: Context, path: String): ParcelFileDescriptor? = runCatching {
        if (path.startsWith("/")) {
            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            context.contentResolver.openFileDescriptor(Uri.parse(path), "r")
        }
    }.getOrNull()

    /** Для отладки: что мост знает об этом файле. */
    fun json(context: Context, path: String): String {
        val i = read(context, path)
            ?: return JSONObject().put("ok", false)
                .put("message", "ядро не прочитало файл — нет ключей или файл повреждён")
                .toString()
        return JSONObject()
            .put("ok", true)
            .put("title", i.title)
            .put("titleId", i.titleId)
            .put("developer", i.developer)
            .put("version", i.version)
            .put("hasIcon", i.iconBase64.isNotEmpty())
            .toString()
    }
}
