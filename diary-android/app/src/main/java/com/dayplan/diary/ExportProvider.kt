package com.dayplan.diary

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import java.io.File

/** Hands the diary file to apps signed with the same key (the calendar app), and to nobody else. */
class ExportProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val ctx = context ?: return null
        val pm = ctx.packageManager
        val callers = pm.getPackagesForUid(Binder.getCallingUid()) ?: return null
        if (callers.none { pm.checkSignatures(it, ctx.packageName) == PackageManager.SIGNATURE_MATCH }) return null
        val file = File(ctx.filesDir, "diary.json")
        return MatrixCursor(arrayOf("json")).apply { addRow(arrayOf(if (file.exists()) file.readText() else "")) }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
