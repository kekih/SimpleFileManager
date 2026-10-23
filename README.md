# Simple File Manager

Простой файловый менеджер для Android на **Kotlin + Jetpack Compose**.

## Возможности
- Просмотр файлов и папок
- Навигация по директориям
- Создание папок
- Удаление файлов/папок
- Переименование
- **Установка APK** (по тапу)
- **Установка XAPK / APKS / APKM** (распаковка + установка)
- **Аудиоплеер** (mp3, wav, ogg, flac, m4a, aac…)
- **Видеоплеер** (mp4, mkv, avi, webm, 3gp…)
- Открытие изображений
- Material Design 3 + тёмная тема

## Сборка

При каждом push в `main` GitHub Actions автоматически собирает debug APK.

Готовый APK: **Actions** → последний workflow → Artifacts → `app-debug`.

### Локально
```bash
./gradlew assembleDebug
```

## Установка
1. Скачай APK из Actions
2. Разреши установку из неизвестных источников
3. Установи
4. При первом запуске дай доступ ко всем файлам (MANAGE_EXTERNAL_STORAGE)
5. Для установки APK/XAPK — разреши «Установку неизвестных приложений»

## Разрешения
- `MANAGE_EXTERNAL_STORAGE` — доступ к файлам
- `REQUEST_INSTALL_PACKAGES` — установка APK/XAPK

## Стек
- Kotlin
- Jetpack Compose + Material 3
- Media3 (ExoPlayer) — аудио/видео
- FileProvider — безопасная установка APK

---
Версия 1.1
