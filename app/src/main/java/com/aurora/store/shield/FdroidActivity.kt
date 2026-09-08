/*
 * SPDX-FileCopyrightText: 2026 Aurora Shield Hub contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.shield

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.aurora.store.R
import com.aurora.store.compose.theme.AuroraTheme
import java.io.File
import kotlinx.coroutines.launch

class FdroidActivity : ComponentActivity() {

    private lateinit var repository: FdroidRepository
    private var pendingInstall: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        repository = FdroidRepository(applicationContext)
        setContent {
            AuroraTheme {
                FdroidScreen(
                    repository = repository,
                    onInstall = { app ->
                        lifecycleScope.launch {
                            runCatching { repository.download(app) }
                                .onSuccess { installOrRequestPermission(it) }
                                .onFailure {
                                    Toast.makeText(
                                        this@FdroidActivity,
                                        it.message ?: "Téléchargement impossible",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val apk = pendingInstall ?: return
        if (ApkInstaller.install(this, apk)) pendingInstall = null
    }

    private fun installOrRequestPermission(apk: File) {
        pendingInstall = apk
        if (ApkInstaller.install(this, apk)) pendingInstall = null
    }
}

@Composable
private fun FdroidScreen(
    repository: FdroidRepository,
    onInstall: (FdroidApp) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var repositories by remember { mutableStateOf(FdroidRepoStore.list(context)) }
    var results by remember { mutableStateOf(emptyList<FdroidApp>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var addRepository by remember { mutableStateOf(false) }

    fun search() {
        if (query.isBlank()) return
        scope.launch {
            loading = true
            error = null
            runCatching { repository.search(repositories, query) }
                .onSuccess { results = it }
                .onFailure { error = it.message ?: "Erreur F-Droid" }
            loading = false
        }
    }

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            runCatching {
                repository.refresh(repositories)
                if (query.isNotBlank()) repository.search(repositories, query) else emptyList()
            }.onSuccess { results = it }
                .onFailure { error = it.message ?: "Actualisation impossible" }
            loading = false
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(horizontal = 40.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = stringResource(R.string.fdroid_title),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = repositories.joinToString(" • ") { it.name },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.fdroid_search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = ::search, enabled = !loading && query.isNotBlank()) {
                    Text(stringResource(R.string.fdroid_search))
                }
                Button(onClick = ::refresh, enabled = !loading) {
                    Text(stringResource(R.string.fdroid_refresh))
                }
                Button(onClick = { addRepository = true }, enabled = !loading) {
                    Text(stringResource(R.string.fdroid_add_repo))
                }
            }
            when {
                loading -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.fdroid_loading))
                    }
                }
                error != null -> Text(
                    text = error.orEmpty(),
                    color = MaterialTheme.colorScheme.error
                )
                results.isEmpty() -> Text(stringResource(R.string.fdroid_empty))
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = results,
                    key = { "${it.repository.baseUrl}|${it.packageName}" }
                ) { app ->
                    FdroidResult(app = app, onInstall = { onInstall(app) })
                }
            }
        }
    }

    if (addRepository) {
        AddRepositoryDialog(
            onDismiss = { addRepository = false },
            onSave = { repo ->
                runCatching { FdroidRepoStore.add(context, repo) }
                    .onSuccess {
                        repositories = FdroidRepoStore.list(context)
                        addRepository = false
                    }
                    .onFailure { error = it.message }
            }
        )
    }
}

@Composable
private fun FdroidResult(app: FdroidApp, onInstall: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(app.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    "${app.packageName} • ${app.versionName} • ${app.repository.name}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (app.summary.isNotBlank()) {
                    Text(app.summary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(formatSize(app.size), style = MaterialTheme.typography.labelMedium)
            }
            Button(onClick = onInstall) {
                Text(stringResource(R.string.fdroid_install))
            }
        }
    }
}

@Composable
private fun AddRepositoryDialog(
    onDismiss: () -> Unit,
    onSave: (FdroidRepo) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fdroid_add_repo)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.fdroid_repo_name)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.fdroid_repo_url)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = fingerprint,
                    onValueChange = { fingerprint = it },
                    label = { Text(stringResource(R.string.fdroid_repo_fingerprint)) },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(FdroidRepo(name, url, fingerprint))
                },
                enabled = url.isNotBlank() && fingerprint.isNotBlank()
            ) {
                Text(stringResource(R.string.fdroid_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.fdroid_cancel))
            }
        }
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f Go".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f Mo".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f Ko".format(bytes / 1024.0)
    else -> "$bytes octets"
}
