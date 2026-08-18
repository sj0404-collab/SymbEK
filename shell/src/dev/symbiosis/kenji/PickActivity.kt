package dev.symbiosis.kenji

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import java.io.File

/** SAF tree picker. kind=eden|kenji */
class PickActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        startActivityForResult(i, 7)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode == RESULT_OK && uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: Exception) {
            }
            val path = treeToPath(uri)
            val kind = intent.getStringExtra("kind") ?: "games"
            if (path == null) {
                Toast.makeText(this, "не смог разобрать путь папки", Toast.LENGTH_LONG).show()
            } else when (kind) {
                "games" -> {
                    FolderHub.addGamesDir(this, path)
                    FolderHub.applyAfterFolderChange(this)
                    Toast.makeText(this, "игры: $path", Toast.LENGTH_LONG).show()
                }
                "saves" -> {
                    FolderHub.setSaves(this, path)
                    Toast.makeText(this, "сейвы: $path", Toast.LENGTH_LONG).show()
                }
                "kenji" -> {
                    DataSeed.setKenjiDir(this, path)
                    FolderHub.applyAfterFolderChange(this)
                    Toast.makeText(this, "Kenji: $path", Toast.LENGTH_LONG).show()
                }
                else -> {
                    FolderHub.setEden(this, path)
                    Toast.makeText(this, "Eden/files: $path", Toast.LENGTH_LONG).show()
                }
            }
        }
        finish()
    }

    companion object {
        fun treeToPath(uri: Uri): String? {
            return try {
                val id = DocumentsContract.getTreeDocumentId(uri)
                val decoded = Uri.decode(id)
                val parts = decoded.split(":", limit = 2)
                val vol = parts[0]
                val rel = if (parts.size > 1) parts[1] else ""
                val tries = ArrayList<File>()
                if (vol.equals("primary", true) || vol.equals("home", true)) {
                    val sd = android.os.Environment.getExternalStorageDirectory()
                    tries.add(File(sd, rel))
                    tries.add(File("/sdcard", rel))
                    tries.add(File("/storage/emulated/0", rel))
                }
                tries.add(File("/storage/$vol/$rel"))
                if (decoded.startsWith("/")) tries.add(File(decoded))
                tries.firstOrNull { it.isDirectory }?.absolutePath
            } catch (_: Exception) {
                null
            }
        }
    }
}
