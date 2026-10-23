package com.kekih.simplefilemanager

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            // Permissions granted
        } else {
            Toast.makeText(this, "Нужны разрешения для доступа к файлам", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestStoragePermissions()

        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FileManagerScreen()
                }
            }
        }
    }

    private fun requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        } else {
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            if (permissions.any {
                    ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                }) {
                requestPermissionLauncher.launch(permissions)
            }
        }
    }
}

@Composable
fun isSystemInDarkTheme(): Boolean {
    return androidx.compose.foundation.isSystemInDarkTheme()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen() {
    val context = LocalContext.current
    var currentPath by remember {
        mutableStateOf(Environment.getExternalStorageDirectory().absolutePath)
    }
    var files by remember { mutableStateOf(listOf<File>()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var newName by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    fun loadFiles(path: String) {
        val dir = File(path)
        if (dir.exists() && dir.isDirectory) {
            files = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?: emptyList()
        } else {
            files = emptyList()
            Toast.makeText(context, "Не удалось открыть папку", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(currentPath) {
        loadFiles(currentPath)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("File Manager", style = MaterialTheme.typography.titleMedium)
                        Text(
                            currentPath,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    if (currentPath != Environment.getExternalStorageDirectory().absolutePath) {
                        IconButton(onClick = {
                            val parent = File(currentPath).parentFile
                            if (parent != null) currentPath = parent.absolutePath
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { loadFiles(currentPath) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                    }
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "Создать папку")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            items(files) { file ->
                FileItem(
                    file = file,
                    onClick = {
                        if (file.isDirectory) {
                            currentPath = file.absolutePath
                        } else {
                            Toast.makeText(context, "Файл: ${file.name}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onLongClick = {
                        selectedFile = file
                        showDeleteConfirm = true
                    },
                    onRename = {
                        selectedFile = file
                        newName = file.name
                        showRenameDialog = true
                    }
                )
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Новая папка") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Имя папки") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) {
                        val newDir = File(currentPath, newName)
                        if (newDir.mkdir()) {
                            loadFiles(currentPath)
                            Toast.makeText(context, "Папка создана", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Ошибка создания", Toast.LENGTH_SHORT).show()
                        }
                    }
                    newName = ""
                    showCreateDialog = false
                }) {
                    Text("Создать")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    newName = ""
                    showCreateDialog = false
                }) {
                    Text("Отмена")
                }
            }
        )
    }

    if (showRenameDialog && selectedFile != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Переименовать") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Новое имя") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    selectedFile?.let { file ->
                        val newFile = File(file.parent, newName)
                        if (file.renameTo(newFile)) {
                            loadFiles(currentPath)
                            Toast.makeText(context, "Переименовано", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Ошибка", Toast.LENGTH_SHORT).show()
                        }
                    }
                    showRenameDialog = false
                    selectedFile = null
                    newName = ""
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRenameDialog = false
                    selectedFile = null
                    newName = ""
                }) {
                    Text("Отмена")
                }
            }
        )
    }

    if (showDeleteConfirm && selectedFile != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Удалить?") },
            text = { Text("Удалить ${selectedFile?.name}?") },
            confirmButton = {
                TextButton(onClick = {
                    selectedFile?.let { file ->
                        val success = if (file.isDirectory) file.deleteRecursively() else file.delete()
                        if (success) {
                            loadFiles(currentPath)
                            Toast.makeText(context, "Удалено", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Ошибка удаления", Toast.LENGTH_SHORT).show()
                        }
                    }
                    showDeleteConfirm = false
                    selectedFile = null
                }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    selectedFile = null
                }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
fun FileItem(
    file: File,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRename: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    ListItem(
        headlineContent = {
            Text(
                file.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                buildString {
                    append(if (file.isDirectory) "Папка" else formatSize(file.length()))
                    append(" • ")
                    append(dateFormat.format(Date(file.lastModified())))
                },
                style = MaterialTheme.typography.bodySmall
            )
        },
        leadingContent = {
            Icon(
                imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                contentDescription = null,
                tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            IconButton(onClick = onRename) {
                Icon(Icons.Default.Edit, contentDescription = "Переименовать")
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
    HorizontalDivider()
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.2f GB".format(gb)
}
