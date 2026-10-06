# Публикация Tremor на Modrinth и CurseForge

Обе площадки на английском: все поля заполняй по-английски. Русский можно добавить блоком в конце полного описания.

Файл мода: `build/libs/tremor-1.0.0.jar` (собрать: `./gradlew build`).
Иконка: `docs/release/icon-512.png` (512×512).
Полное описание: `docs/release/description-en.md` (русское — `description-ru.md`).

---

## Modrinth

### Окно «Creating a project»

| Поле | Значение |
|---|---|
| Type | Project (это мод) |
| Name | `Tremor` |
| URL | `tremor` |
| Visibility | Public (станет видно после одобрения модераторами) |
| Summary | `Something vast crawls beneath the ground and hears your every step. Sneak, stay still, or be swallowed by the earth.` |

### Страница проекта (после создания)

- **Icon** — `icon-512.png`.
- **Description** — содержимое `description-en.md` целиком (Markdown поддерживается).
- **Categories** (Settings → Tags): `Adventure`, `Mobs`, `Game Mechanics`.
- **Environment**: Client — **Required**, Server — **Required**.
- **License**: `All Rights Reserved` (в списке: «All rights reserved / No license»).
- **Links** (необязательно): Issues / Source — оставь пустыми, раз репозиторий приватный.
- **Gallery**: скриншоты и гифки (бугор в траве, кольца ряби, холм, кратер). Подписи короткие, например
  `The ground rises where it moves`, `It is listening`, `The earth takes you`.

### Версия (Versions → Create a version)

| Поле | Значение |
|---|---|
| Version number | `1.0.0` |
| Version title | `Tremor 1.0.0` |
| Release channel | `Beta` (первый публичный выпуск: честно и снижает претензии) или `Release` |
| Loaders | `NeoForge` |
| Game versions | `1.21.1` |
| Dependencies | нет |
| Files | `tremor-1.0.0.jar` (primary) |
| Changelog | см. ниже |

Changelog:

```
First public release.
- The entity: a mound that crawls beneath the ground and hunts by vibration.
- The Awakening and the Hollow.
- Shards, the seismograph, the geophone, Muffled Steps.
- The Nether, multiplayer, Sodium and Iris support, quality preset.
```

Модерация на Modrinth обычно занимает от нескольких часов до пары дней. Если что-то не так, модератор напишет в
проект.

---

## CurseForge

Создание: authors.curseforge.com → Create Project → Minecraft → Mods.

| Поле | Значение |
|---|---|
| Project name | `Tremor` |
| Summary | `A mound beneath the ground that hunts you by sound. Sneak, freeze, or be swallowed.` |
| Description | содержимое `description-en.md` (редактор понимает Markdown, переключатель вверху) |
| Main category | `Mobs` |
| Additional categories | `Adventure and RPG`, `World Gen` не ставь (мир не генерируем) |
| License | `All Rights Reserved` |
| Avatar / logo | `icon-512.png` |
| Allow 3rd-party distribution | **Включи**, если хочешь, чтобы мод попадал в сборки на других лаунчерах |

Загрузка файла (Files → Upload file):

| Поле | Значение |
|---|---|
| File | `tremor-1.0.0.jar` |
| Display name | `Tremor 1.0.0` |
| Release type | `Beta` или `Release` |
| Game version | `1.21.1` |
| Mod loader | `NeoForge` |
| Java | `Java 21` |
| Environment | Client и Server |
| Changelog | тот же, что для Modrinth |

CurseForge тоже проверяет проект и файл вручную, обычно в течение суток.

---

## Короткие тексты (если где-то просят ещё)

- Слоган в несколько слов: **`The ground is listening.`**
- Одно предложение: `A horror mob that lives beneath the ground and hunts you by the vibrations of your steps.`

Русские варианты (для своих каналов, Discord, VK):

- Слоган: **«Земля слушает.»**
- Кратко: «Под землёй ползёт тварь, которая слышит каждый твой шаг. Крадись, замри — или земля тебя поглотит.»
- Одно предложение: «Хоррор-моб, который живёт под землёй и охотится на тебя по вибрациям шагов.»
