package com.rfandango.haku_x.ui.settings

import android.Manifest
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rfandango.haku_x.R
import com.rfandango.haku_x.data.GameStorage
import com.rfandango.haku_x.ui.common.ChoiceDialog
import com.rfandango.haku_x.ui.common.ConfirmDialog
import com.rfandango.haku_x.ui.common.Hint
import com.rfandango.haku_x.ui.common.Section
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StorageSettingsViewModel(app: Application) : AndroidViewModel(app) {
  private val _path = MutableStateFlow(GameStorage.root(app).path)
  val path = _path.asStateFlow()
  private val _busy = MutableStateFlow(false)
  val busy = _busy.asStateFlow()
  private val _notice = MutableStateFlow<String?>(null)
  val notice = _notice.asStateFlow()

  fun dismissNotice() { _notice.value = null }

  fun move(destination: File) {
    if (_busy.value) return
    _busy.value = true
    viewModelScope.launch {
      val app = getApplication<Application>()
      val result = withContext(Dispatchers.IO) { runCatching { GameStorage.relocate(app, destination) } }
      _path.value = runCatching { GameStorage.root(app).path }.getOrDefault(_path.value)
      _notice.value = result.fold(
        { app.getString(if (it) R.string.storage_move_done else R.string.storage_move_cleanup) },
        { app.getString(R.string.storage_move_failed, it.message ?: it.javaClass.simpleName) },
      )
      _busy.value = false
    }
  }
}

/** The user selects one location for saves, game data, states and their companion files. */
@Composable
fun StorageSettings(vm: StorageSettingsViewModel = viewModel()) {
  val context = LocalContext.current
  val path by vm.path.collectAsStateWithLifecycle()
  val busy by vm.busy.collectAsStateWithLifecycle()
  val notice by vm.notice.collectAsStateWithLifecycle()
  var choosing by rememberSaveable { mutableStateOf(false) }
  var destination by rememberSaveable { mutableStateOf<String?>(null) }
  var error by rememberSaveable { mutableStateOf<String?>(null) }
  var permissionExplanation by rememberSaveable { mutableStateOf(false) }
  val folders = remember { GameStorage.appFolders(context) }
  val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    if (uri != null) {
      runCatching { GameStorage.pickedFolder(context, uri) }
        .onSuccess { destination = it.path }
        .onFailure { error = it.message }
    }
  }
  val allFiles = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
    if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) pick.launch(null)
    else error = context.getString(R.string.storage_permission_needed)
  }
  val legacyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (granted) pick.launch(null) else error = context.getString(R.string.storage_permission_needed)
  }

  Section(stringResource(R.string.storage_section))
  ListItem(
    headlineContent = { Text(stringResource(R.string.storage_location)) },
    supportingContent = { Text(path) },
    modifier = Modifier.clickable(enabled = !busy) { choosing = true },
  )
  Hint(stringResource(R.string.storage_locations, "$path/hdd/Partition1/UDATA", "$path/hdd/Partition1/TDATA", "$path/states"))
  Hint(stringResource(R.string.storage_description))

  if (choosing) {
    val options = listOf(GameStorage.defaultRoot(context).path to context.getString(R.string.storage_private)) +
      folders.mapIndexed { index, file -> file.path to context.getString(
        if (index == 0) R.string.storage_device_app else R.string.storage_sd_app, file.path,
      ) } + listOf("custom" to context.getString(R.string.storage_custom))
    ChoiceDialog(
      title = stringResource(R.string.storage_location), options = options, current = path,
      onPick = {
        choosing = false
        if (it == "custom") {
          if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) pick.launch(null)
          else permissionExplanation = true
        } else if (it != path) destination = it
      },
      onDismiss = { choosing = false },
    )
  }
  if (permissionExplanation) {
    ConfirmDialog(
      title = stringResource(R.string.storage_custom), text = stringResource(R.string.storage_permission_description),
      confirm = stringResource(R.string.storage_permission_grant),
      onDismiss = { permissionExplanation = false },
      onConfirm = {
        permissionExplanation = false
        if (Build.VERSION.SDK_INT >= 30) {
          runCatching {
            allFiles.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
          }.onFailure { error = context.getString(R.string.storage_permission_needed) }
        } else legacyPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
      },
    )
  }
  destination?.let { dst ->
    ConfirmDialog(
      title = stringResource(R.string.storage_move_title),
      text = stringResource(R.string.storage_move_confirm, path, dst),
      confirm = stringResource(R.string.storage_move),
      onConfirm = { destination = null; vm.move(File(dst)) }, onDismiss = { destination = null },
    )
  }
  if (busy) AlertDialog(
    onDismissRequest = {}, title = { Text(stringResource(R.string.storage_move_title)) },
    text = { Column { CircularProgressIndicator(); Text(stringResource(R.string.storage_moving)) } },
    confirmButton = {},
  )
  (error ?: notice)?.let { message ->
    AlertDialog(
      onDismissRequest = { error = null; vm.dismissNotice() },
      title = { Text(stringResource(R.string.storage_location)) }, text = { Text(message) },
      confirmButton = { TextButton(onClick = { error = null; vm.dismissNotice() }) { Text(stringResource(android.R.string.ok)) } },
    )
  }
}
