# kizBoot

Утилита для Android, которая записывает образы дисков (**.iso / .img**) на USB-накопители
напрямую с телефона через **USB OTG** — аналог Rufus/Etcher для кармана.

Стек: **Kotlin · Jetpack Compose · Material 3 (тёмная тема) · Android USB Host API · Coroutines · Foreground Service (dataSync)**

![kizBoot](kizboot%20icon.jpg)

## Возможности

- **Выбор образа** через системный SAF (Storage Access Framework) с фильтром `*.iso` / `*.img`, отображение имени и точного размера.
- **Список USB-накопителей** через `UsbManager`: vendor/product ID, ёмкость (через SCSI READ CAPACITY), запрос системного разрешения, кнопка «Обновить».
- **Настройки**: «Keep screen on» (не гасить экран) и «Верификация после записи».
- **Прогресс записи**: LinearProgressIndicator, процент, скорость МБ/с, ETA, лог текущего сектора (LBA).
- **Кнопки**: «Записать», «Отмена», «Форматировать в FAT32».
- **Верификация**: побайтовое сравнительное чтение записанного объёма с исходным файлом.
- **Очистка + FAT32**: зануление первых 10 МиБ (MBR/GPT) и настоящая разметка FAT32 — MBR с разделом типа `0x0C`, VBR с BPB, FSInfo, резервная копия VBR, две таблицы FAT и корневой каталог с меткой `KIZBOOT`.

## Как это работает

USB-накопители не принимают «сырые» байты в bulk OUT эндпоинт — они разговаривают на
протоколе **USB Mass Storage Bulk-Only (BBB)**. kizBoot реализует его поверх
`android.hardware.usb`:

1. `UsbController` находит mass-storage устройства, запрашивает разрешение,
   захватывает интерфейс и bulk-эндпоинты (`UsbDeviceConnection.claimInterface`).
2. `ScsiTransport` формирует **CBW (31 байт) → данные → CSW (13 байт)** и выполняет
   SCSI-команды: `WRITE(10)`, `READ(10)`, `READ CAPACITY(10)`, `INQUIRY`,
   `TEST UNIT READY`, `REQUEST SENSE`, `SYNCHRONIZE CACHE(10)`, `BULK ONLY MASS STORAGE RESET`.
   Сброс STALL (`CLEAR_FEATURE ENDPOINT_HALT`), ретраи и тайм-ауты обрабатываются в этом слое.
3. `BootEngine` читает образ из SAF `Uri` (`ParcelFileDescriptor`) буфером **1 МиБ**
   (константа на 4 МиБ — `READ_BUFFER_BYTES_LARGE`) и шлёт его командами WRITE(10)
   по **128 КиБ (256 секторов)** на транзакцию, добивая хвост файла нулями до границы сектора.
4. `BootWriterService` — foreground-сервис с типом **dataSync**, постоянным уведомлением
   (прогресс-бар + кнопка «Отмена») и **PARTIAL_WAKE_LOCK**: запись не прерывается
   ни при блокировке экрана, ни при сворачивании приложения. Обрыв кабеля ловится
   броадкастом `USB_DEVICE_DETACHED` и ошибкой bulk-передачи.

## Сборка

1. Откройте папку `kizBoot` в **Android Studio** (Koala/2024.1 или новее — Gradle 8.9 + AGP 8.7.3 подтянутся через wrapper).
2. Дождитесь Gradle Sync (нужен JDK 17+; Studio использует собственный).
3. `Build → Generate Signed APK` или `./gradlew assembleDebug`.

Минимальные требования: **Android 8.0 (API 26)**, целевой SDK 34.

## Использование

1. Подключите флешку через **OTG-адаптер** → нажмите «Обновить» (или приложение откроется само по `USB_DEVICE_ATTACHED`).
2. Нажмите «Предоставить доступ» на карточке устройства.
3. Выберите образ через SAF.
4. «Записать» → подтвердите удаление данных → следите за прогрессом.

> **Внимание: запись уничтожает все данные на накопителе безвозвратно.**

## Ограничения тестирования

- **Эмулятор не умеет USB Host** — функциональный тест записи возможен только на реальном телефоне с OTG-адаптером и флешкой. Собрать и установить APK можно и на эмуляторе, но список накопителей будет пуст.
- Носители больше **2 ТиБ** не поддерживаются (READ CAPACITY(10)).
- FAT32-разметка следует ограничениям Microsoft: не предназначена для носителей > 2 ТиБ.
- На некоторых телефонах с USB-C порт в режиме host ограничен по питанию — для крупных HDD используйте хаб с внешним питанием.

## Структура

```
app/src/main/java/org/kiz/boot/
├── MainActivity.kt              — SAF-пикер, права, приёмники USB, тема
├── MainViewModel.kt             — экранное состояние и команды сервису
├── core/                        — WriterBus (StateFlow-шина), модели, форматирование
├── usb/UsbController.kt         — поиск накопителей, разрешения, сессия
├── usb/ScsiTransport.kt         — CBW/CSW и SCSI-команды (BBB)
├── usb/BootEngine.kt            — запись/верификация/очистка блоками 1 МиБ
├── fat32/Fat32Formatter.kt      — MBR + VBR + FSInfo + FAT ×2 + корневой каталог
├── service/BootWriterService.kt — foreground dataSync, WakeLock, уведомления
└── ui/                          — Compose-экран и тёмная тема M3 (#121212 / #FF6D00)
```
