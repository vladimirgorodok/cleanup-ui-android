# Очистка · OpenChamber / OpenCode

Android-приложение с WebView-интерфейсом для:

- поиска установленных `com.openchamber.app`, `app.getopencode` и проверки версий;
- самообновления этого APK;
- проверки актуального ядра OpenCode AI (`anomalyco/opencode`);
- поиска рабочих каталогов и анализа их размера;
- безопасной очистки кэшей, временных файлов, старых APK и миниатюр;
- отображения описаний процессов, прогресса и журнала.

## Сборка

```bash
./gradlew assembleRelease
```

APK: `app/build/outputs/apk/release/app-release.apk`.

Ключ подписи не лежит в репозитории. Для сборки с подписью задайте переменные:

```
CLEANUP_STORE_FILE=/path/to/cleanup-ui-release.jks
CLEANUP_STORE_PASSWORD=...
CLEANUP_KEY_ALIAS=cleanup
CLEANUP_KEY_PASSWORD=...
```

Без ключа релизная сборка получается неподписанной.

## Разрешения

- `MANAGE_EXTERNAL_STORAGE` — доступ ко всем файлам (включается в настройках Android 11+).
- `REQUEST_INSTALL_PACKAGES` — установка скачанных APK обновлений.
- `INTERNET` — проверка версий на GitHub.

`Android/data/<-package>/...` может быть недоступен даже при разрешении «Доступ ко всем файлам» — это ограничение Android. Приложение показывает такие каталоги как недоступные и чистит только то, к чему есть доступ.

## Источники версий и обновлений

| Компонент | Проверка версии | APK обновления |
|---|---|---|
| Очистка UI | `vladimirgorodok/cleanup-ui-android` releases/latest | `cleanup-ui.apk` из latest release (имя ассета должно сохраняться) |
| OpenChamber | `openchamber/openchamber` releases/latest | `OpenChamber-canary-android.apk` из release `canary` |
| OpenCode Mobile | `alvarolorentedev/opencode-mobile` releases/latest | `opencode-mobile.apk` из latest release |
| OpenCode AI (ядро) | `anomalyco/opencode` releases/latest | обновляется вместе с мобильными приложениями |

## Безопасность

Очистка выполняется только по выбранным задачам и только внутри найденных рабочих каталогов. Задачи с риском `medium` («старые APK», «пустые каталоги») требуют отдельного подтверждения.
