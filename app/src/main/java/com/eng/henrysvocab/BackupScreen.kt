package com.eng.henrysvocab

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun BackupScreen(
    store: VocabStore,
    wallpaper: WallpaperStore,
    models: ModelSettings,
    onRestored: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val backup = remember(context, store, wallpaper, models) { AppBackup(context, store, wallpaper, models) }
    val scope = rememberCoroutineScope()
    val restored by rememberUpdatedState(onRestored)
    // Neither the password nor selected URI is persisted. Retry the picker after recreation.
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var pendingExport by remember { mutableStateOf(false) }
    var pendingPicker by remember { mutableStateOf(false) }
    var restoreDocument by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val enabled = !busy && !pendingPicker

    fun clearPassword() {
        password = ""
        confirmation = ""
    }

    fun runBackup(uri: Uri, restoring: Boolean) {
        if (busy) return
        if (password.isBlank()) {
            clearPassword()
            error = "请重新输入备份密码并选择文件。"
            return
        }
        val secret = password.toCharArray()
        clearPassword()
        busy = true
        error = null
        status = if (restoring) "正在验证并恢复备份…" else "正在加密并保存备份…"
        scope.launch {
            val success = try {
                if (restoring) backup.restoreFrom(uri, secret) else backup.exportTo(uri, secret)
                status = if (restoring) "备份已恢复。" else "加密备份已保存。"
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: BackupAuthenticationException) {
                error = "密码不正确，或备份文件已损坏。"
                false
            } catch (_: BackupFormatException) {
                error = if (restoring) "无法恢复：备份格式不受支持、已损坏或超出大小限制。"
                else "无法创建备份：数据或保存位置不受支持，或数据超出大小限制。"
                false
            } catch (_: BackupRollbackException) {
                error = "恢复失败，部分设置可能未能还原。请重新打开应用检查数据，或恢复另一份有效备份。"
                false
            } catch (_: Exception) {
                error = if (restoring) "无法读取或恢复备份。请检查文件和可用空间后重试。"
                else "未能保存备份。请选择其他位置重试；此位置可能留有不完整文件。"
                false
            } finally {
                secret.fill('\u0000')
                busy = false
                if (error != null) status = null
            }
            if (success && restoring) restored()
        }
    }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        pendingPicker = false
        if (uri != null) runBackup(uri, restoring = false) else clearPassword()
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingPicker = false
        clearPassword()
        restoreDocument = uri
    }

    fun launchPicker(launch: () -> Unit) {
        error = null
        status = null
        pendingPicker = true
        try {
            launch()
        } catch (_: Exception) {
            pendingPicker = false
            clearPassword()
            error = "无法打开系统文件选择器。"
        }
    }

    BackHandler(enabled = !enabled) { /* Do not leave the screen during a picker or write. */ }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("备份与恢复", onBack = { if (enabled) onBack() })
        Text("这是手动加密备份，不会自动运行。备份包含词书和学习进度、自定义壁纸，以及模型配置（含已存储的 API 密钥）。")
        Text("请保存到卸载应用后仍保留的文件夹，例如下载目录、SD 卡或云盘。恢复时必须输入原密码，忘记密码无法找回。")
        Text("恢复会替换当前的词书、学习进度、壁纸及模型配置。请仅恢复自己信任的备份：恢复的模型网址将用于后续请求。")
        GlassButton("创建加密备份", {
            clearPassword()
            pendingExport = true
        }, Modifier.fillMaxWidth(), enabled = enabled)
        GlassButton("选择备份并恢复", {
            launchPicker { open.launch(arrayOf("*/*")) }
        }, Modifier.fillMaxWidth(), enabled = enabled)
        (error ?: status)?.let { message ->
            Text(
                message,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
    }

    val selected = restoreDocument
    if (pendingExport || selected != null) {
        val exporting = pendingExport
        fun dismiss() {
            pendingExport = false
            restoreDocument = null
            clearPassword()
        }
        val validPassword = password.isNotBlank() && (!exporting ||
            (password.length >= 12 && password == confirmation))
        AlertDialog(
            onDismissRequest = ::dismiss,
            title = { Text(if (exporting) "设置备份密码" else "确认恢复备份") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (exporting) "请使用至少 12 个字符且不易猜到的独立密码。"
                    else "恢复会替换现有数据。请输入创建此备份时使用的密码。")
                    BackupPasswordField(password, { password = it }, "备份密码")
                    if (exporting) BackupPasswordField(confirmation, { confirmation = it }, "再次输入密码")
                }
            },
            confirmButton = {
                TextButton(enabled = validPassword, onClick = {
                    pendingExport = false
                    restoreDocument = null
                    confirmation = ""
                    if (exporting) launchPicker { create.launch("henrysvocab.hvb") }
                    else if (selected != null) runBackup(selected, restoring = true)
                }) { Text(if (exporting) "选择保存位置" else "替换并恢复") }
            },
            dismissButton = { TextButton(onClick = ::dismiss) { Text("取消") } },
        )
    }
}

@Composable
private fun BackupPasswordField(value: String, change: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= BackupPasswordLimit) change(it) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation(),
    )
}
