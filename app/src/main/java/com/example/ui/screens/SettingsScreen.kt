package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.local.LocalIdentityConfig
import com.example.data.local.ThemePreference
import com.example.mesh.transport.MeshLogEntry
import com.example.ui.components.isolateBidi
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    identity: LocalIdentityConfig,
    diagnosticLogs: List<MeshLogEntry>,
    onUpdateDisplayName: (String) -> Unit,
    onToggleBackgroundRelay: (Boolean) -> Unit,
    onToggleShareLocation: (Boolean) -> Unit = {},
    onSetMaxHops: (Int) -> Unit,
    onSetTheme: (ThemePreference) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var nameDraft by remember(identity.displayName) { mutableStateOf(identity.displayName) }
    var hopsDraft by remember(identity.maxHops) { mutableFloatStateOf(identity.maxHops.toFloat()) }
    var showClearDialog by remember { mutableStateOf(false) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        onToggleShareLocation(granted)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .testTag("settings_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(stringResource(R.string.settings_identity_section), style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = stringResource(R.string.mesh_sheet_node_id, isolateBidi(identity.nodeId)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        OutlinedTextField(
                            value = nameDraft,
                            onValueChange = { nameDraft = it },
                            label = { Text(stringResource(R.string.onboarding_name_label)) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_display_name_input")
                        )
                        Button(
                            onClick = { onUpdateDisplayName(nameDraft) },
                            enabled = nameDraft.isNotBlank() && nameDraft.trim() != identity.displayName,
                            modifier = Modifier.testTag("settings_save_name_button")
                        ) {
                            Text(stringResource(R.string.settings_save_name))
                        }
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.settings_relay_title), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = stringResource(R.string.settings_relay_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = identity.backgroundRelayEnabled,
                                onCheckedChange = onToggleBackgroundRelay,
                                modifier = Modifier.testTag("settings_relay_switch")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.settings_gps_title), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = stringResource(R.string.settings_gps_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = identity.shareLocationOnRadar,
                                onCheckedChange = { wantEnable ->
                                    if (!wantEnable) {
                                        onToggleShareLocation(false)
                                    } else {
                                        val granted = ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.ACCESS_FINE_LOCATION
                                        ) == PackageManager.PERMISSION_GRANTED ||
                                            ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.ACCESS_COARSE_LOCATION
                                            ) == PackageManager.PERMISSION_GRANTED
                                        if (granted) {
                                            onToggleShareLocation(true)
                                        } else {
                                            locationPermissionLauncher.launch(
                                                arrayOf(
                                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                                )
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.testTag("settings_gps_switch")
                            )
                        }

                        Column {
                            Text(
                                text = stringResource(R.string.settings_max_hops, hopsDraft.roundToInt()),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Slider(
                                value = hopsDraft,
                                onValueChange = { hopsDraft = it },
                                onValueChangeFinished = { onSetMaxHops(hopsDraft.roundToInt()) },
                                valueRange = 2f..10f,
                                steps = 7
                            )
                        }
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(stringResource(R.string.settings_appearance), style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ThemePreference.entries.forEach { pref ->
                                FilterChip(
                                    selected = identity.themePreference == pref,
                                    onClick = { onSetTheme(pref) },
                                    modifier = Modifier.testTag("settings_theme_${pref.name.lowercase()}"),
                                    label = {
                                        Text(
                                            when (pref) {
                                                ThemePreference.SYSTEM -> stringResource(R.string.settings_theme_system)
                                                ThemePreference.LIGHT -> stringResource(R.string.settings_theme_light)
                                                ThemePreference.DARK -> stringResource(R.string.settings_theme_dark)
                                            }
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            item {
                OutlinedButton(
                    onClick = { showClearDialog = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_clear_history_btn))
                }
            }

            item {
                Text(
                    text = stringResource(R.string.settings_diagnostics_title),
                    style = MaterialTheme.typography.titleSmall
                )
            }

            if (diagnosticLogs.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.settings_diagnostics_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(diagnosticLogs.take(25)) { entry ->
                    Text(
                        text = "[${entry.tag}] ${entry.message}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.settings_clear_dialog_title)) },
            text = { Text(stringResource(R.string.settings_clear_dialog_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        onClearHistory()
                    }
                ) {
                    Text(stringResource(R.string.action_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}
