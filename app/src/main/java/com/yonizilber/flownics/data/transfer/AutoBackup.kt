package com.yonizilber.flownics.data.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.yonizilber.flownics.data.di.ApplicationScope
import com.yonizilber.flownics.data.local.db.dao.TransferDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

enum class BackupProblem { NO_FOLDER_ACCESS, WRITE_FAILED }

data class AutoBackupState(
    val enabled: Boolean = false,
    val folderUri: String? = null,
    val folderName: String? = null,
    val lastSuccessMillis: Long? = null,
    val lastFileName: String? = null,
    /** Set when the latest attempt failed; cleared by the next success. */
    val problem: BackupProblem? = null,
    val problemDetail: String = "",
    val problemAtMillis: Long? = null,
    val running: Boolean = false,
)

/**
 * A backup file in a folder the user picked (through the system picker, so no storage permission),
 * written when the user leaves the app and the last one is a day old, keeping the newest
 * [KEEP] of its own files. A failure is kept and shown in the settings until a backup succeeds.
 */
@Singleton
class AutoBackup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transfer: DataTransfer,
    private val dao: TransferDao,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AutoBackupState> = _state.asStateFlow()

    private fun load() = AutoBackupState(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        folderUri = prefs.getString(KEY_FOLDER, null),
        folderName = prefs.getString(KEY_FOLDER_NAME, null),
        lastSuccessMillis = prefs.getLong(KEY_LAST_SUCCESS, 0L).takeIf { it > 0 },
        lastFileName = prefs.getString(KEY_LAST_FILE, null),
        problem = prefs.getString(KEY_PROBLEM, null)?.let { name -> BackupProblem.entries.firstOrNull { it.name == name } },
        problemDetail = prefs.getString(KEY_PROBLEM_DETAIL, "").orEmpty(),
        problemAtMillis = prefs.getLong(KEY_PROBLEM_AT, 0L).takeIf { it > 0 },
    )

    /** Keeps access to the folder across restarts, lets go of the previous one, and turns backup on. */
    fun setFolder(tree: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val resolver = context.contentResolver
        resolver.takePersistableUriPermission(tree, flags)
        _state.value.folderUri?.let(Uri::parse)?.takeIf { it != tree }?.let { old ->
            runCatching { resolver.releasePersistableUriPermission(old, flags) }
        }
        val name = runCatching { displayName(tree) }.getOrNull()
        prefs.edit().putString(KEY_FOLDER, tree.toString()).putString(KEY_FOLDER_NAME, name).putBoolean(KEY_ENABLED, true).apply()
        _state.update { it.copy(folderUri = tree.toString(), folderName = name, enabled = true) }
        runNow()
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _state.update { it.copy(enabled = enabled) }
    }

    /** Called when the app goes to the background. */
    fun onAppLeft() {
        val s = _state.value
        val due = s.lastSuccessMillis == null || System.currentTimeMillis() - s.lastSuccessMillis >= INTERVAL_MS
        if (s.enabled && s.folderUri != null && due) runNow()
    }

    fun runNow() {
        val folder = _state.value.folderUri ?: return
        if (_state.value.running) return
        _state.update { it.copy(running = true) }
        scope.launch {
            try {
                // Never in the middle of a workout: the player owns the session until it ends.
                if (dao.inProgressCount() > 0) return@launch
                val name = backUp(Uri.parse(folder))
                val now = System.currentTimeMillis()
                prefs.edit().putLong(KEY_LAST_SUCCESS, now).putString(KEY_LAST_FILE, name)
                    .remove(KEY_PROBLEM).remove(KEY_PROBLEM_DETAIL).remove(KEY_PROBLEM_AT).apply()
                _state.update { it.copy(lastSuccessMillis = now, lastFileName = name, problem = null, problemDetail = "", problemAtMillis = null) }
            } catch (e: SecurityException) {
                fail(BackupProblem.NO_FOLDER_ACCESS, e)
            } catch (e: FileNotFoundException) {
                fail(BackupProblem.NO_FOLDER_ACCESS, e)
            } catch (e: IOException) {
                fail(BackupProblem.WRITE_FAILED, e)
            } catch (e: IllegalArgumentException) {
                // A folder URI the provider no longer recognises.
                fail(BackupProblem.NO_FOLDER_ACCESS, e)
            } finally {
                _state.update { it.copy(running = false) }
            }
        }
    }

    private fun fail(problem: BackupProblem, e: Exception) {
        val now = System.currentTimeMillis()
        val detail = e.message.orEmpty().take(200)
        prefs.edit().putString(KEY_PROBLEM, problem.name).putString(KEY_PROBLEM_DETAIL, detail).putLong(KEY_PROBLEM_AT, now).apply()
        _state.update { it.copy(problem = problem, problemDetail = detail, problemAtMillis = now) }
    }

    private suspend fun backUp(tree: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = PREFIX + LocalDateTime.now().format(STAMP) + ".zip"
        val doc = DocumentsContract.createDocument(resolver, dir, MIME_ZIP, name) ?: throw IOException("Could not create $name")
        try {
            resolver.openOutputStream(doc, "w").use { out ->
                out ?: throw IOException("Could not open $name")
                transfer.exportBackup(out)
            }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, doc) }
            throw e
        }
        prune(tree)
        name
    }

    /** Deletes this feature's older files beyond the newest [KEEP]; nothing else in the folder is touched. */
    private fun prune(tree: Uri) {
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val ours = mutableListOf<Pair<String, String>>()
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1)
                if (name != null && OURS.matches(name)) ours += c.getString(0) to name
            }
        }
        // The timestamp in the name sorts newest last.
        ours.sortedByDescending { it.second }.drop(KEEP).forEach { (id, _) ->
            runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
        }
    }

    private fun displayName(tree: Uri): String? {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    companion object {
        const val MIME_ZIP = "application/zip"
        const val KEEP = 5
        private const val INTERVAL_MS = 24L * 60 * 60 * 1000
        private const val PREFIX = "flownics-auto-"
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val OURS = Regex("flownics-auto-\\d{8}-\\d{6}\\.zip")

        // Excluded from Android's own backup: the folder permission doesn't move to another device.
        private const val PREFS = "backup_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_FOLDER = "folder"
        private const val KEY_FOLDER_NAME = "folder_name"
        private const val KEY_LAST_SUCCESS = "last_success"
        private const val KEY_LAST_FILE = "last_file"
        private const val KEY_PROBLEM = "problem"
        private const val KEY_PROBLEM_DETAIL = "problem_detail"
        private const val KEY_PROBLEM_AT = "problem_at"
    }
}
