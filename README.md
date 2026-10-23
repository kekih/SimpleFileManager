# Simple File Manager

Простой файловый менеджер для Android на **Kotlin + Jetpack Compose**.

## Возможности
- Просмотр файлов и папок
- Навигация по директориям
- Создание папок
- Удаление файлов/папок
- Переименование
- Копирование пути
- Material Design 3
- Поддержка тёмной темы

## Сборка

### Локально
```bash
./gradlew assembleDebug
```

### GitHub Actions
При каждом push в `main` автоматически собирается debug APK.
Готовый APK можно скачать во вкладке **Actions** → последний workflow → Artifacts.

## Установка
1. Скачай APK из Actions
2. Разреши установку из неизвестных источников
3. Установи

## Разрешения
- `READ_EXTERNAL_STORAGE` / `MANAGE_EXTERNAL_STORAGE` (для доступа к файлам)

## Стек
- Kotlin
- Jetpack Compose
- Material 3
- AndroidX

---
Сделано для быстрого использования и экспериментов.
