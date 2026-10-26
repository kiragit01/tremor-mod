# Разработка

Заметки для тех, кто собирает мод сам или копается в коде. Для игроков — [README](../README.ru.md) и [гайд](../GUIDE.ru.md).

## Ветки

- `main` — NeoForge 1.21.1 (ModDevGradle, JDK 21).
- `forge-1.20.1` — Forge 1.20.1 (47.x, ModDevGradle legacyforge, JDK 17).

Любая правка идёт в обе ветки. Jar-файлы обеих складываются в общую `build/libs`: `tremor-neoforge-1.21.1-<версия>.jar` и `tremor-forge-1.20.1-<версия>.jar`.

## Сборка и запуск

Всё остальное, кроме JDK, скачает Gradle.

```bash
./gradlew build       # компиляция + юнит-тесты (чистая логика без запуска игры)
./gradlew test        # только тесты
./gradlew runClient   # клиент для ручной проверки (папка ./run)
./gradlew runServer   # выделенный сервер (./run-server)
```

Флаги для dev-запусков:

- `-Ptremor_sodium=true` — добавить Sodium (версия в `gradle.properties`);
- `-Ptremor_voicechat=true` — добавить Simple Voice Chat; `/tremor voice <дБ> [whisper]` изображает речь без микрофона;
- `-Ptremor_hotswap=true --debug-jvm` — hot swap: Gradle возьмёт JetBrains Runtime 21 и будет ждать отладчик на порту 5005. В IntelliJ IDEA то же самое: в run-конфигурации выбрать JRE = JBR 21, запускать под Debug, после правок — Build → Recompile (Ctrl+Shift+F9). Флаг `-XX:+AllowEnhancedClassRedefinition` передаётся всегда, на обычной JVM он просто игнорируется.

## Проверка в игре скриптом (autotest)

`runClientAutotest` запускает клиент в отдельной папке (`./run-autotest`, на ветке Forge — `./run-forge-autotest`), сам создаёт мир с фиксированным сидом, выполняет скрипт из `./autotest` (команды, ожидания, нажатия клавиш, замеры FPS, скриншоты) и закрывает игру.

```bash
./gradlew runClientAutotest -Ptremor_autotest_script=stage5-frenzy.txt
```

Отчёт — `run-autotest/tremor-autotest/<время>/report.txt`, скриншоты рядом. В отчёте SYSTEM — это чат, OVERLAY — строка над хотбаром. Формат скрипта (`wait`, `cmd`, `hold`, `screenshot`, `config`, `lookat`, `view`, `fps`, ...) описан в `tremor.client.dev.Script`.

Если в коде поменялось значение конфига по умолчанию, удали `run-autotest/config/tremor-*.toml`, иначе там останется старое.

`tools/contact-sheet.py <папка отчёта> <out.png> <имена скриншотов...>` склеивает скриншоты в одну картинку. Для движения лучше GIF: пример — `autotest/leap-film.txt` и `autotest/shard-film.txt` (игра замедлена до 4 тиков в секунду, кадр каждый тик; только `main`, в 1.20.1 нет `/tick`).

## Сеть и сервер

- `tools/mp-test.sh mp-alice.txt mp-bob.txt` — выделенный сервер на localhost (`run-mp/server`, офлайн-режим, оба игрока — операторы) и два клиента-автотеста со своими скриптами. Отчёты — в `run-mp/alice|bob/tremor-autotest`. Только `main`.
- `tools/forge-server-test.sh` — готовый jar на настоящем сервере Forge 1.20.1 (`run-prod/forge-1.20.1`). `TREMOR_EXTRA_MODS="x.jar"` кладёт рядом другие моды, например Simple Voice Chat.

## Где что в коде

| Пакет | Что внутри |
|---|---|
| `tremor.core` | Чистая логика без Minecraft API: форма бугра, нормали, граф поверхности, A*, сплайн пути, движение, слух, стадии и «мозг». К миру обращается только через `VoxelView`; что сюда не протекает Minecraft, проверяет `CoreIsolationTest`. |
| `tremor.world` | Адаптеры к миру Minecraft (`LevelVoxelView`, теги, свет). |
| `tremor.entity` | Серверная тварь: состояние в `SavedData`, тик, поиск пути, синхронизация, реакция на изменения блоков, «мозг» по стадиям (`TremorMind`), удар, уход, бешенство от осколка (`Frenzy`). |
| `tremor.hearing` | Слух: игровые события и голос из Simple Voice Chat → вибрации с громкостью и опорой. |
| `tremor.spawn` | Естественное появление. |
| `tremor.awakening` | Пробуждение: зона, фазы, поглощение, исходы (`Outcomes`, выход через край — `EdgeExits`), кратер (`Craters`) и тайники на его дне (`CraterCaches`). |
| `tremor.hollow` | Изнанка: измерение `tremor:hollow`, копирование местности, перенос, возврат; `tremor.hollow.level` — уровень внутри (ходы к узлу, туннель наружу, смыкание, мягкая земля). |
| `tremor.block`, `tremor.item` | Блоки и предметы: узел, трясина, тайник, геофон; осколок, сейсмограф, мультитул. |
| `tremor.config` | Конфиги и пресеты сложности (`Difficulty`). |
| `tremor.sound`, `tremor.network` | Звуки; пакеты сервер → клиент. |
| `tremor.command`, `tremor.debug` | Команды `/tremor`, мультитул и отладочные частицы. |
| `tremor.client` | Клиент: интерполяция, рендер деформации и ряби, холмики над брошенными осколками, звуки; `tremor.client.hollow` — изнанка на клиенте (туман, небо, запрет бега, движение земли). |
| `tremor.client.dev` | Скриптовый прогон (`runClientAutotest`), в обычной игре не активен. |
| `tremor.compat` | Плагин для Simple Voice Chat. |

## Команды

Все — только для операторов (уровень прав 2).

| Команда | Что делает |
|---|---|
| `/tremor spawn [pos]`, `/tremor spawn natural` | Создать тварь у взгляда или в точке (такая сама не уходит), или сразу найти для себя точку естественного появления и создать там естественную. |
| `/tremor despawn` | Убрать. |
| `/tremor info` | Стадия, злость, что делает «мозг», маршрут, часы ухода, стоимость тика, идущее Пробуждение и кратеры. |
| `/tremor goto [pos]`, `/tremor stop` | Отправить в точку; забыть цель и остановиться. |
| `/tremor anger <0..100>`, `/tremor stage <dormant\|alert\|hunting\|awakening>` | Выставить злость или стадию. |
| `/tremor hold` | Держать её взгляд: злость стоит на месте, но слышит и реагирует она как обычно. |
| `/tremor ai <on\|off>` | Включить или выключить «мозг»: без него она слушается только `goto` и `stop`. |
| `/tremor set [<param> <value>\|reset]` | Параметры формы и движения для этой твари. |
| `/tremor difficulty <calm\|normal\|hard\|nightmare>` | Сложность. |
| `/tremor voice <дБ> [whisper]` | Изобразить речь в голосовом чате. |
| `/tremor awaken [игрок]`, `/tremor awaken stop` | Запустить Пробуждение сразу или отменить. |
| `/tremor hollow <enter\|leave\|status\|tonode\|walk>`, `/tremor hollow outcome <victory\|edge\|defeat>` | Отладка изнанки. |
| `/tremor crater [pos]` | Выкопать кратер без события и без вещей. |
| `/tremor restore` | Завершить все события изнанки, вернуть игроков, очистить участки. |
| `/tremor debug <path\|normals\|graph\|hearing> <on\|off>` | Отладочные частицы; `hearing` ещё и пишет над хотбаром, что она услышала. |

## Конфиги

`config/tremor-common.toml` — игра и сервер, `config/tremor-client.toml` — вид и звук у каждого игрока. У каждого ключа есть комментарий. Главное по-русски — в [CONFIG-ru.md](release/CONFIG-ru.md).

## Старые заметки

Рабочие заметки по этапам разработки — `docs/stage*.md`, тексты для страниц мода — `docs/release`, наброски будущего — `docs/design`.
