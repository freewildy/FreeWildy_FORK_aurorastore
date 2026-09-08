/*
 * SPDX-FileCopyrightText: 2026 Aurora Shield Hub contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.shield

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.aurora.store.ComposeActivity
import com.aurora.store.R
import com.aurora.store.compose.theme.AuroraTheme
import java.io.File
import kotlinx.coroutines.launch

class ShieldHubActivity : ComponentActivity() {

    private var pendingInstall: File? = null

    private val apkPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            runCatching { ApkInstaller.stage(this@ShieldHubActivity, uri) }
                .onSuccess { installOrRequestPermission(it) }
                .onFailure {
                    Toast.makeText(
                        this@ShieldHubActivity,
                        it.message ?: "APK invalide",
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIncomingApk(intent)
        setContent {
            AuroraTheme {
                ShieldHub(
                    openAurora = {
                        startActivity(Intent(this, ComposeActivity::class.java))
                    },
                    openFdroid = {
                        startActivity(Intent(this, FdroidActivity::class.java))
                    },
                    sideload = {
                        apkPicker.launch(arrayOf("application/vnd.android.package-archive"))
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingApk(intent)
    }

    override fun onResume() {
        super.onResume()
        val apk = pendingInstall ?: return
        if (ApkInstaller.install(this, apk)) pendingInstall = null
    }

    private fun handleIncomingApk(intent: Intent?) {
        val uri = intent?.data ?: return
        lifecycleScope.launch {
            runCatching { ApkInstaller.stage(this@ShieldHubActivity, uri) }
                .onSuccess { installOrRequestPermission(it) }
                .onFailure {
                    Toast.makeText(
                        this@ShieldHubActivity,
                        it.message ?: "APK invalide",
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    private fun installOrRequestPermission(apk: File) {
        pendingInstall = apk
        if (ApkInstaller.install(this, apk)) pendingInstall = null
    }
}

@Composable
private fun ShieldHub(
    openAurora: () -> Unit,
    openFdroid: () -> Unit,
    sideload: () -> Unit
) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 48.dp, vertical = 36.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = stringResource(R.string.shield_hub_title),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.shield_hub_subtitle),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = stringResource(R.string.shield_no_account),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                HubButton(
                    title = stringResource(R.string.shield_open_aurora),
                    summary = stringResource(R.string.shield_open_aurora_summary),
                    onClick = openAurora,
                    modifier = Modifier.weight(1f)
                )
                HubButton(
                    title = stringResource(R.string.shield_open_fdroid),
                    summary = stringResource(R.string.shield_open_fdroid_summary),
                    onClick = openFdroid,
                    modifier = Modifier.weight(1f)
                )
            }
            HubButton(
                title = stringResource(R.string.shield_sideload),
                summary = stringResource(R.string.shield_sideload_summary),
                onClick = sideload,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun HubButton(
    title: String,
    summary: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(onClick = onClick, modifier = modifier.height(116.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(summary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
