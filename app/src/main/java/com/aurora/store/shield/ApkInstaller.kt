/*
 * SPDX-FileCopyrightText: 2026 Aurora Shield Hub contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.shield

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.core.content.FileProvider
import com.aurora.store.BuildConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ApkInstaller {

    suspend fun stage(context: Context, source: Uri): File = withContext(Dispatchers.IO) {
        val displayName = context.contentResolver.query(
            source,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        val safeName = displayName
            ?.substringAfterLast('/')
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?.takeIf { it.endsWith(".apk", ignoreCase = true) }
            ?: "sideload-${System.currentTimeMillis()}.apk"
        val destinationDir = File(context.cacheDir, "sideload").apply { mkdirs() }
        val destination = File(destinationDir, "${System.currentTimeMillis()}-$safeName")

        context.contentResolver.openInputStream(source).use { input ->
            requireNotNull(input) { "Impossible d'ouvrir l'APK sélectionné" }
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        require(destination.length() > 0L) { "Le fichier APK est vide" }
        destination
    }

    /**
     * Starts Android's trusted package installer. Returns false when the user must first grant the
     * per-app "install unknown apps" permission; callers can retry from onResume().
     */
    fun install(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                )
            )
            return false
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileProvider",
            apk
        )
        context.startActivity(
            Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            }
        )
        return true
    }
}
