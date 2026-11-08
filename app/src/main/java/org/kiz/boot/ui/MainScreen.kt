package org.kiz.boot.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.kiz.boot.MainViewModel
import org.kiz.boot.R
import org.kiz.boot.core.Format
import org.kiz.boot.core.Phase
import org.kiz.boot.core.WriterBus
import org.kiz.boot.core.WriterState
import org.kiz.boot.ui.theme.KizColors

/** Главный экран kizBoot: выбор образа, накопитель, настройки, прогресс и кнопки действий. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel, onPickImage: () -> Unit) {
    val state by WriterBus.state.collectAsStateWithLifecycle()
    val log by WriterBus.log.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmWrite by remember { mutableStateOf(false) }
    var confirmFormat by remember { mutableStateOf(false) }

    KeepScreenOnEffect(vm.keepScreenOn)

    LaunchedEffect(state.phase) {
        when (state.phase) {
            Phase.DONE -> snackbarHostState.showSnackbar(state.message)
            Phase.ERROR -> snackbarHostState.showSnackbar("Ошибка: ${state.message}")
            else -> Unit
        }
    }

    Scaffold(
        containerColor = KizColors.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text("kizBoot", color = KizColors.accent, fontWeight = FontWeight.Bold)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = KizColors.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FileCard(vm, onPickImage)
            UsbCard(vm)
            SettingsCard(vm)
            if (state.busy) {
                ProgressCard(state, log)
            }
            ActionRow(vm, state, onWrite = { confirmWrite = true }, onFormat = { confirmFormat = true })
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmWrite) {
        WriteConfirmDialog(vm) { confirmWrite = false }
    }
    if (confirmFormat) {
        FormatConfirmDialog(vm) { confirmFormat = false }
    }
}

@Composable
private fun KeepScreenOnEffect(keepOn: Boolean) {
    val context = LocalContext.current
    LaunchedEffect(keepOn) {
        (context as? Activity)?.window?.let { window ->
            if (keepOn) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}

// ------------------------------------------------------------ карточка образа

@Composable
private fun FileCard(vm: MainViewModel, onPickImage: () -> Unit) {
    val image = vm.selectedImage
    Card(colors = cardColors(KizColors.surface)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionHeader(Icons.Filled.Description, stringResource(R.string.section_image))
            if (image == null) {
                Text(
                    stringResource(R.string.no_file_selected),
                    style = MaterialTheme.typography.bodyMedium,
                    color = KizColors.textSecondary
                )
            } else {
                Text(
                    image.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = KizColors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    Format.fileSize(image.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = KizColors.accent
                )
                val capacity = vm.selectedDriveId?.let { id -> vm.capacities[id] }
                if (capacity != null && image.size >= capacity) {
                    Text(
                        stringResource(R.string.file_too_big),
                        style = MaterialTheme.typography.bodySmall,
                        color = KizColors.danger
                    )
                }
            }
            Button(
                onClick = onPickImage,
                modifier = Modifier.fillMaxWidth(),
                colors = accentButtonColors(),
                shape = MaterialTheme.shapes.small
            ) {
                Text(stringResource(R.string.action_select_image))
            }
            Text(
                stringResource(R.string.image_hint),
                style = MaterialTheme.typography.labelSmall,
                color = KizColors.textSecondary
            )
        }
    }
}

// ------------------------------------------------------------ карточка накопителя

@Composable
private fun UsbCard(vm: MainViewModel) {
    Card(colors = cardColors(KizColors.surface)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader(Icons.Filled.Usb, stringResource(R.string.section_usb))
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { vm.refreshDrives() },
                    colors = ButtonDefaults.textButtonColors(contentColor = KizColors.accent)
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.action_refresh), style = MaterialTheme.typography.labelMedium)
                }
            }
            if (vm.drives.isEmpty()) {
                Text(
                    stringResource(R.string.no_drives),
                    style = MaterialTheme.typography.bodyMedium,
                    color = KizColors.textSecondary
                )
            } else {
                vm.drives.forEach { drive ->
                    DeviceRow(
                        vendorName = drive.vendorName,
                        productName = drive.productName,
                        vidPid = Format.vidPid(drive.vendorId, drive.productId),
                        capacity = vm.capacities[drive.deviceId]?.let { Format.fileSize(it) },
                        hasPermission = drive.hasPermission,
                        selected = drive.deviceId == vm.selectedDriveId,
                        onSelect = { vm.selectDrive(drive.deviceId) },
                        onRequestAccess = { vm.requestPermission(drive) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    vendorName: String,
    productName: String,
    vidPid: String,
    capacity: String?,
    hasPermission: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onRequestAccess: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape = MaterialTheme.shapes.small,
        color = if (selected) KizColors.surfaceVariant else KizColors.surface,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) KizColors.accent else KizColors.outlineDim
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "$vendorName · $productName",
                style = MaterialTheme.typography.bodyLarge,
                color = KizColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "$vidPid · ёмкость: ${capacity ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
                color = KizColors.textSecondary,
                fontFamily = FontFamily.Monospace
            )
            if (hasPermission) {
                Text(
                    stringResource(R.string.access_granted),
                    style = MaterialTheme.typography.labelSmall,
                    color = KizColors.accent
                )
            } else {
                TextButton(
                    onClick = onRequestAccess,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = KizColors.accent)
                ) {
                    Text(stringResource(R.string.action_grant_access), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

// ------------------------------------------------------------ настройки

@Composable
private fun SettingsCard(vm: MainViewModel) {
    Card(colors = cardColors(KizColors.surface)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingRow(
                title = stringResource(R.string.setting_keep_screen_on),
                subtitle = stringResource(R.string.setting_keep_screen_on_desc),
                checked = vm.keepScreenOn,
                onChecked = { vm.keepScreenOn = it }
            )
            HorizontalDivider(color = KizColors.outlineDim)
            SettingRow(
                title = stringResource(R.string.setting_verify),
                subtitle = stringResource(R.string.setting_verify_desc),
                checked = vm.verify,
                onChecked = { vm.verify = it }
            )
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = KizColors.textPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = KizColors.textSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedThumbColor = KizColors.accent,
                checkedTrackColor = KizColors.accent.copy(alpha = 0.4f),
                uncheckedThumbColor = KizColors.textSecondary,
                uncheckedTrackColor = KizColors.surfaceVariant
            )
        )
    }
}

// ------------------------------------------------------------ прогресс

@Composable
private fun ProgressCard(state: WriterState, log: List<String>) {
    Card(colors = cardColors(KizColors.surface)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = when (state.phase) {
                    Phase.PREPARING -> stringResource(R.string.phase_preparing)
                    Phase.WRITING -> stringResource(R.string.phase_writing)
                    Phase.VERIFYING -> stringResource(R.string.phase_verifying)
                    Phase.FORMATTING -> stringResource(R.string.phase_formatting)
                    else -> stringResource(R.string.app_name)
                },
                style = MaterialTheme.typography.titleMedium,
                color = KizColors.textPrimary
            )
            LinearProgressIndicator(
                progress = { state.percent / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = KizColors.accent,
                trackColor = KizColors.track
            )
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "${state.percent}%",
                    style = MaterialTheme.typography.headlineLarge,
                    color = KizColors.accent,
                    fontWeight = FontWeight.Bold
                )
                Column {
                    Text(
                        Format.speed(state.speedMbPerSec * 1024.0 * 1024.0),
                        style = MaterialTheme.typography.bodyLarge,
                        color = KizColors.textPrimary,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        if (state.etaSeconds >= 0) {
                            stringResource(R.string.eta_left, Format.eta(state.etaSeconds))
                        } else {
                            stringResource(R.string.eta_unknown)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = KizColors.textSecondary
                    )
                }
            }
            Text(
                "LBA 0x%08X · %s / %s".format(
                    state.currentLba.coerceAtLeast(0),
                    Format.fileSize(state.writtenBytes),
                    Format.fileSize(state.totalBytes)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = KizColors.textSecondary,
                fontFamily = FontFamily.Monospace
            )
            LogBox(log)
        }
    }
}

@Composable
private fun LogBox(log: List<String>) {
    val scrollState = rememberScrollState()
    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) scrollState.animateScrollTo(scrollState.maxValue)
    }
    Surface(
        color = KizColors.background,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, KizColors.outlineDim)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 140.dp)
                .verticalScroll(scrollState)
                .padding(10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (log.isEmpty()) {
                    Text(
                        "…",
                        style = MaterialTheme.typography.labelSmall,
                        color = KizColors.textSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                }
                log.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.labelSmall,
                        color = KizColors.textSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------ кнопки действий

@Composable
private fun ActionRow(vm: MainViewModel, state: WriterState, onWrite: () -> Unit, onFormat: () -> Unit) {
    val busy = state.busy
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onWrite,
                enabled = vm.canWrite && !busy,
                modifier = Modifier
                    .weight(2f)
                    .height(56.dp),
                colors = accentButtonColors(),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    stringResource(R.string.action_write),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            OutlinedButton(
                onClick = { vm.cancelOperation() },
                enabled = busy,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = KizColors.textPrimary,
                    disabledContentColor = KizColors.textSecondary
                ),
                border = BorderStroke(1.dp, if (busy) KizColors.accent else KizColors.outlineDim),
                shape = MaterialTheme.shapes.small
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
        OutlinedButton(
            onClick = onFormat,
            enabled = vm.canFormat && !busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = KizColors.accent,
                disabledContentColor = KizColors.textSecondary
            ),
            border = BorderStroke(1.dp, if (vm.canFormat && !busy) KizColors.accent else KizColors.outlineDim),
            shape = MaterialTheme.shapes.small
        ) {
            Text(stringResource(R.string.action_format))
        }
    }
}

// ------------------------------------------------------------ диалоги

@Composable
private fun WriteConfirmDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val drive = vm.drives.firstOrNull { it.deviceId == vm.selectedDriveId }
    val image = vm.selectedImage
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = KizColors.surface,
        titleContentColor = KizColors.textPrimary,
        textContentColor = KizColors.textSecondary,
        title = { Text(stringResource(R.string.confirm_write_title)) },
        text = {
            Text(
                stringResource(
                    R.string.confirm_write_text,
                    image?.name ?: "?",
                    drive?.let { "${it.vendorName} (${Format.vidPid(it.vendorId, it.productId)})" } ?: "?"
                )
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    vm.startWrite()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = KizColors.accent)
            ) {
                Text(stringResource(R.string.action_write), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = KizColors.textSecondary)
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun FormatConfirmDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = KizColors.surface,
        titleContentColor = KizColors.textPrimary,
        textContentColor = KizColors.textSecondary,
        title = { Text(stringResource(R.string.confirm_format_title)) },
        text = { Text(stringResource(R.string.confirm_format_text)) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    vm.startFormat()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = KizColors.accent)
            ) {
                Text(stringResource(R.string.action_format), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = KizColors.textSecondary)
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

// ------------------------------------------------------------ утилиты оформления

@Composable
private fun cardColors(container: Color) = CardDefaults.cardColors(containerColor = container)

@Composable
private fun accentButtonColors() = ButtonDefaults.buttonColors(
    containerColor = KizColors.accent,
    contentColor = Color.Black,
    disabledContainerColor = KizColors.surfaceVariant,
    disabledContentColor = KizColors.textSecondary
)

@Composable
private fun SectionHeader(icon: ImageVector, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = KizColors.accent, modifier = Modifier.size(20.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = KizColors.textPrimary
        )
    }
}
