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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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
        if (permissions.all { it.value }) {
            // ok
        } else {
            Toast.makeText(this, "Нужны разрешения для доступа к файлам", Toast.LENGTH_LONG).show()
        }
    }

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // user returned from settings
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestStoragePermissions()

        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FileManagerScreen(
                        onInstallApk = { file -> installApk(file) },
                        onInstallXapk = { file -> installXapk(file) },
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

    private fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            packageManager.canRequestPackageInstalls()
        } else true
    }

    private fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")
            )
            installPermissionLauncher.launch(intent)
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
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
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

                // Find main APK (prefer base.apk or largest .apk)
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

                // Copy OBB if present
                val obbFiles = extractDir.walkTopDown()
                    .filter { it.isFile && it.extension.equals("obb", ignoreCase = true) }
                    .toList()

                if (obbFiles.isNotEmpty()) {
                    val obbDir = File(Environment.getExternalStorageDirectory(), "Android/obb")
                    // package name from manifest is hard without parsing; put next to apk for now
                    // Many XAPKs have android/obb/<package>/ structure already
                    for (obb in obbFiles) {
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
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_PATH, file.absolutePath)
            putExtra(PlayerActivity.EXTRA_IS_AUDIO, isAudio)
        }
        startActivity(intent)
    }
}

@Composable
fun isSystemInDarkTheme(): Boolean {
    return androidx.compose.foundation.isSystemInDarkTheme()
}

enum class FileType {
    FOLDER, APK, XAPK, AUDIO, VIDEO, IMAGE, OTHER
}

fun getFileType(file: File): FileType {
    if (file.isDirectory) return FileType.FOLDER
    val ext = file.extension.lowercase()
    return when (ext) {
        "apk" -> FileType.APK
        "xapk", "apks", "apkm" -> FileType.XAPK
        "mp3", "wav", "ogg", "m4a", "flac", "aac", "wma", "opus" -> FileType.AUDIO
        "mp4", "mkv", "avi", "webm", "3gp", "mov", "flv", "m4v", "ts" -> FileType.VIDEO
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> FileType.IMAGE
        else -> FileType.OTHER
    }
}

fun getFileIcon(type: FileType): ImageVector {
    return when (type) {
        FileType.FOLDER -> Icons.Default.Folder
        FileType.APK, FileType.XAPK -> Icons.Default.Android
        FileType.AUDIO -> Icons.Default.AudioFile
        FileType.VIDEO -> Icons.Default.VideoFile
        FileType.IMAGE -> Icons.Default.Image
        FileType.OTHER -> Icons.Default.InsertDriveFile
    }
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
    var showCreateDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var newName by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showActionDialog by remember { mutableStateOf(false) }

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
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "image/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
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
                    onClick = { handleFileClick(file) },
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

    if (showActionDialog && selectedFile != null) {
        AlertDialog(
            onDismissRequest = { showActionDialog = false },
            title = { Text(selectedFile!!.name) },
            text = { Text("Что сделать с файлом?") },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            selectedFile!!
                        )
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "*/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Открыть с помощью"))
                    } catch (e: Exception) {
                        Toast.makeText(context, "Не удалось открыть", Toast.LENGTH_SHORT).show()
                    }
                    showActionDialog = false
                    selectedFile = null
                }) {
                    Text("Открыть")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showActionDialog = false
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
    val type = getFileType(file)

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
