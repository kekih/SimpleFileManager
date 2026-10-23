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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipInputStream

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (!permissions.all { it.value }) {
            Toast.makeText(this, "Нужны разрешения для доступа к файлам", Toast.LENGTH_LONG).show()
        }
    }

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestStoragePermissions()

        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FileManagerScreen(
                        onInstallApk = { installApk(it) },
                        onInstallXapk = { installXapk(it) },
                        onPlayMedia = { file, isAudio -> openPlayer(file, isAudio) }
                    )
                }
            }
        }
    }

    private fun requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
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

    private fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) packageManager.canRequestPackageInstalls()
        else true

    private fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            installPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
            Toast.makeText(this, "Разрешите установку из неизвестных источников", Toast.LENGTH_LONG).show()
        }
    }

    private fun installApk(file: File) {
        if (!file.exists()) {
            Toast.makeText(this, "Файл не найден", Toast.LENGTH_SHORT).show()
            return
        }
        if (!canInstallPackages()) {
            requestInstallPermission()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка установки: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun installXapk(file: File) {
        if (!file.exists()) {
            Toast.makeText(this, "Файл не найден", Toast.LENGTH_SHORT).show()
            return
        }
        if (!canInstallPackages()) {
            requestInstallPermission()
            return
        }

        Toast.makeText(this, "Распаковка XAPK...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val extractDir = File(cacheDir, "xapk_${System.currentTimeMillis()}")
                extractDir.mkdirs()

                ZipInputStream(BufferedInputStream(FileInputStream(file))).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val outFile = File(extractDir, entry.name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            BufferedOutputStream(FileOutputStream(outFile)).use { bos ->
                                zis.copyTo(bos)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }

                val apks = extractDir.walkTopDown()
                    .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
                    .toList()

                if (apks.isEmpty()) {
                    runOnUiThread {
                        Toast.makeText(this, "В XAPK не найдено APK", Toast.LENGTH_LONG).show()
                    }
                    extractDir.deleteRecursively()
                    return@Thread
                }

                val mainApk = apks.find {
                    it.name.equals("base.apk", true) || it.name.contains("base", true)
                } ?: apks.maxByOrNull { it.length() } ?: apks.first()

                extractDir.walkTopDown()
                    .filter { it.isFile && it.extension.equals("obb", ignoreCase = true) }
                    .forEach { obb ->
                        try {
                            val relative = obb.relativeTo(extractDir).path
                            if (relative.contains("Android/obb", ignoreCase = true) ||
                                relative.contains("Android\\obb", ignoreCase = true)
                            ) {
                                val target = File(Environment.getExternalStorageDirectory(), relative)
                                target.parentFile?.mkdirs()
                                obb.copyTo(target, overwrite = true)
                            }
                        } catch (_: Exception) {
                        }
                    }

                runOnUiThread {
                    Toast.makeText(this, "Установка ${mainApk.name}...", Toast.LENGTH_SHORT).show()
                    installApk(mainApk)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Ошибка XAPK: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun openPlayer(file: File, isAudio: Boolean) {
        startActivity(
            Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_PATH, file.absolutePath)
                putExtra(PlayerActivity.EXTRA_IS_AUDIO, isAudio)
            }
        )
    }
}

@Composable
fun isSystemInDarkTheme(): Boolean = androidx.compose.foundation.isSystemInDarkTheme()

enum class FileType { FOLDER, APK, XAPK, AUDIO, VIDEO, IMAGE, OTHER }

fun getFileType(file: File): FileType {
    if (file.isDirectory) return FileType.FOLDER
    return when (file.extension.lowercase()) {
        "apk" -> FileType.APK
        "xapk", "apks", "apkm" -> FileType.XAPK
        "mp3", "wav", "ogg", "m4a", "flac", "aac", "wma", "opus" -> FileType.AUDIO
        "mp4", "mkv", "avi", "webm", "3gp", "mov", "flv", "m4v", "ts" -> FileType.VIDEO
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> FileType.IMAGE
        else -> FileType.OTHER
    }
}

fun getFileIcon(type: FileType): ImageVector = when (type) {
    FileType.FOLDER -> Icons.Default.Folder
    FileType.APK, FileType.XAPK -> Icons.Default.Android
    FileType.AUDIO -> Icons.Default.AudioFile
    FileType.VIDEO -> Icons.Default.VideoFile
    FileType.IMAGE -> Icons.Default.Image
    FileType.OTHER -> Icons.Default.InsertDriveFile
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(
    onInstallApk: (File) -> Unit,
    onInstallXapk: (File) -> Unit,
    onPlayMedia: (File, Boolean) -> Unit
) {
    val context = LocalContext.current
    var currentPath by remember {
        mutableStateOf(Environment.getExternalStorageDirectory().absolutePath)
    }
    var files by remember { mutableStateOf(listOf<File>()) }
    var loading by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var newName by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showActionDialog by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    // Load directory off the main thread — main lag source on big folders
    LaunchedEffect(currentPath, refreshKey) {
        loading = true
        val result = withContext(Dispatchers.IO) {
            val dir = File(currentPath)
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()
                    ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    ?: emptyList()
            } else null
        }
        loading = false
        if (result == null) {
            files = emptyList()
            Toast.makeText(context, "Не удалось открыть папку", Toast.LENGTH_SHORT).show()
        } else {
            files = result
        }
    }

    fun handleFileClick(file: File) {
        when (getFileType(file)) {
            FileType.FOLDER -> currentPath = file.absolutePath
            FileType.APK -> onInstallApk(file)
            FileType.XAPK -> onInstallXapk(file)
            FileType.AUDIO -> onPlayMedia(file, true)
            FileType.VIDEO -> onPlayMedia(file, false)
            FileType.IMAGE -> {
                try {
                    val uri = FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", file
                    )
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "image/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    )
                } catch (_: Exception) {
                    Toast.makeText(context, "Не удалось открыть изображение", Toast.LENGTH_SHORT).show()
                }
            }
            FileType.OTHER -> {
                selectedFile = file
                showActionDialog = true
            }
        }
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
                            File(currentPath).parentFile?.let { currentPath = it.absolutePath }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                    }
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "Создать папку")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (loading && files.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(files, key = { it.absolutePath }) { file ->
                    FileItem(
                        file = file,
                        onClick = { handleFileClick(file) },
                        onRename = {
                            selectedFile = file
                            newName = file.name
                            showRenameDialog = true
                        },
                        onDelete = {
                            selectedFile = file
                            showDeleteConfirm = true
                        }
                    )
                }
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
                        if (File(currentPath, newName).mkdir()) {
                            refreshKey++
                            Toast.makeText(context, "Папка создана", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Ошибка создания", Toast.LENGTH_SHORT).show()
                        }
                    }
                    newName = ""
                    showCreateDialog = false
                }) { Text("Создать") }
            },
            dismissButton = {
                TextButton(onClick = {
                    newName = ""
                    showCreateDialog = false
                }) { Text("Отмена") }
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
                        if (file.renameTo(File(file.parent, newName))) {
                            refreshKey++
                            Toast.makeText(context, "Переименовано", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Ошибка", Toast.LENGTH_SHORT).show()
                        }
                    }
                    showRenameDialog = false
                    selectedFile = null
                    newName = ""
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRenameDialog = false
                    selectedFile = null
                    newName = ""
                }) { Text("Отмена") }
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
                        val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
                        if (ok) {
                            refreshKey++
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
                }) { Text("Отмена") }
            }
        )
    }

    if (showActionDialog && selectedFile != null) {
        AlertDialog(
            onDismissRequest = { showActionDialog = false },
            title = { Text(selectedFile!!.name) },
            text = { Text("Что сделать с файлом?") },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        val uri = FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", selectedFile!!
                        )
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "*/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                },
                                "Открыть с помощью"
                            )
                        )
                    } catch (_: Exception) {
                        Toast.makeText(context, "Не удалось открыть", Toast.LENGTH_SHORT).show()
                    }
                    showActionDialog = false
                    selectedFile = null
                }) { Text("Открыть") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showActionDialog = false
                    selectedFile = null
                }) { Text("Отмена") }
            }
        )
    }
}

@Composable
fun FileItem(
    file: File,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    val type = remember(file.absolutePath) { getFileType(file) }

    ListItem(
        headlineContent = {
            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                buildString {
                    append(
                        when (type) {
                            FileType.FOLDER -> "Папка"
                            FileType.APK -> "APK"
                            FileType.XAPK -> "XAPK"
                            FileType.AUDIO -> "Аудио"
                            FileType.VIDEO -> "Видео"
                            FileType.IMAGE -> "Изображение"
                            else -> formatSize(file.length())
                        }
                    )
                    if (type != FileType.FOLDER) {
                        append(" • ")
                        append(formatSize(file.length()))
                    }
                    append(" • ")
                    append(dateFormat.format(Date(file.lastModified())))
                },
                style = MaterialTheme.typography.bodySmall
            )
        },
        leadingContent = {
            Icon(
                imageVector = getFileIcon(type),
                contentDescription = null,
                tint = when (type) {
                    FileType.FOLDER -> MaterialTheme.colorScheme.primary
                    FileType.APK, FileType.XAPK -> MaterialTheme.colorScheme.tertiary
                    FileType.AUDIO -> MaterialTheme.colorScheme.secondary
                    FileType.VIDEO -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        },
        trailingContent = {
            Row {
                IconButton(onClick = onRename) {
                    Icon(Icons.Default.Edit, contentDescription = "Переименовать")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Удалить")
                }
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
    return "%.2f GB".format(mb / 1024.0)
}
