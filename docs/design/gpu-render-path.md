# Шейдерный путь для копий (набросок)

План, как перенести смещение фантомных копий на видеокарту, чтобы тянуть гигантов ×16–×32 (100–500 тысяч копий, сотни
блоков в поперечнике). Ничего не сделано, 2026-11-07. Ветки `1.5.0` (NeoForge 1.21.1) и `forge-1.5.0` (Forge 1.20.1);
цифры — из `docs/perf/benchmarks.md`.

**Коротко.** Свой core-шейдер (`ShaderInstance` через `RegisterShadersEvent`) и инстансинг на голом OpenGL 3.3. Меши
блоков общие и загружены на видеокарту один раз, таблица колонн меняется только при сборе, а на кадр процессор шлёт лишь
параметры тварей — сотни чисел вместо сотен тысяч вершин. Вершинный шейдер считает высоту по формулам `BumpShape` и
`Ripple` и рисует всю стопку копий над колонной одним вытянутым кубом с повторённой текстурой. Редкие особые случаи
(заливки `ThinSwell`, мосты, «шов», копии вокруг камеры, полупрозрачные и не целые блоки) остаются на процессорном пути
в том же кадре. С шейдер-паком Iris/Oculus всегда рисует процессорный путь: свой шейдер под паком невидим. Процессорный
путь остаётся основным и запасным.

## 1. Как рисуется сейчас

- `DeformationRenderer.onRenderLevelStage`: на `AFTER_BLOCK_ENTITIES` — `prepare()` и `draw()` для `solid`,
  `cutoutMipped`, `cutout`; на `AFTER_TRANSLUCENT_BLOCKS` и `AFTER_TRIPWIRE_BLOCKS` — свои слои.
- `prepare()`: `bumps()` даёт каждой твари `HeightField` (бугор + рябь); `collect()` раз в 10 тиков или при сдвиге на
  0.5 блока собирает поверхность (`SurfaceCollector`, `ThinSwell`) в `Column`; земля Пробуждения и изнанки —
  `ZoneColumns` (ячейки 8³ с отсевом по фрустуму) и `GroundField`. Дальше **каждый кадр по каждой колонне**:
  `evaluate()` (высота в центре вокселя, сумма по тварям из маски `Column.bumps`), `bakePending()` (до 48 новых колонн
  за кадр), `trim()` по бюджету, `seam()`, для стиля warp — `warpCorners()`.
- `bake()`: модель через `BlockRenderDispatcher.renderBatched` в `VertexList`; мир для неё — `ProbeLevel` (воздух
  вокруг, свет самой светлой открытой соседней клетки исходного блока — `SurfaceLight.brightestOpenNeighbour`, настоящий
  оттенок биома, затенение граней). `validate()` перепекает при смене блока и раз в 20 тиков перечитывает свет.
- `draw()`: над колонной стопка из `ceil(h)` копий на `h, h−1, …` вдоль `Column.axis`; каждая копия — копирование вершин
  со сдвигом на процессоре (`VertexList.emitCulled` с ростом `Column.grow` и скрытыми гранями `hidden()`), плюс
  `emitSlid` для заливок, лесенка «шва» по `Column.cross`, вывернутые грани `emitInside` у камеры, декор сверху. Всё — в
  немедленные буферы `MultiBufferSource`, `endBatch` с polygon offset −4.
- Цена: 0.3–0.4 мс на 1000 копий; ×16 стоит — 30.6 мс рендера, ползёт — 94; ×32 — 673; видеокарта — доли миллисекунды
  даже на 500 000 копий. Каждый кадр — работа O(копий) на процессоре (высоты, вершины, выгрузка), сверху пики пересбора
  (сфера целиком, синхронно; p99 кадра у стоящего ×16 — 133 мс).

**Iris сегодня.** По исходникам Iris 1.21.1 и Oculus 1.20.1 теневой проход рисует слои чанков, сущности и
блок-сущности, а `RenderLevelStageEvent` сам не рассылает (Iris для NeoForge лишь напрямую зовёт обработчик Immersive
Engineering). Но Sodium после каждого слоя чанков, в теневом проходе тоже, зовёт `ClientHooks.dispatchRenderStage` —
приходят `AFTER_SOLID_BLOCKS`, `AFTER_CUTOUT_*`, `AFTER_TRANSLUCENT_BLOCKS` с матрицами тени. Мы рисуем непрозрачные
копии на `AFTER_BLOCK_ENTITIES` (в теневом проходе его нет), а полупрозрачные — на `AFTER_TRANSLUCENT_BLOCKS`, но
матрицу из события на NeoForge не применяем. Выходит, по коду непрозрачные копии в карту теней не попадают. Если тени от
холмов видны, стоит выяснить, что это (шаг 0). На шейдерный путь это не влияет: с паком рисует процессорный путь, ровно
как сейчас, и что работает, то и останется.

## 2. Варианты

| Вариант | Плюсы | Минусы | Iris, тени |
|---|---|---|---|
| **A. Свой шейдер + инстансинг** (GL 3.3, таблицы в TBO) | на кадр O(тварей); без зависимостей; один код на обоих загрузчиках; Sodium/Embeddium не мешают (их рендер чанков не трогаем) | своя отрисовка (состояние GL, туман, свет); два рендера держать в согласии | под паком невидим → процессорный путь, тени как сейчас |
| **B. Смещение на GPU в обычный буфер** (transform feedback GL 3.0 или compute GL 4.3), рисовать ванильным `RenderType` | единственное, что в принципе может жить с паком: под паком вершинный шейдер всегда пакетный, смещать надо до него | Iris ждёт свой формат вершин (`IrisVertexFormats.TERRAIN`: id блока, mid-UV, тангенты) и отрисовку и в теневом проходе — это внутренности Iris; буфер на все вершины; compute нет на macOS (GL 4.1) | теоретически да, через внутренности Iris |
| **C. Flywheel** | готовый инстансинг (GL 3.3) и indirect с отсевом на GPU (GL 4.6), оба загрузчика | жёсткая зависимость; в `InstanceType` только данные экземпляра и шейдеры, своих uniform-ов на кадр нет → параметры твари писать в каждый экземпляр каждый кадр, снова O(N) | Flywheel сам выключается под паком (`!ShadersModHelper.isShaderPackInUse()` в `Backends`); с паками его подружают только сторонние аддоны (Iris Flywheel Compat, Colorwheel) |
| **D. Только процессор**: LOD-копии 2×2×2/4×4×4, сбор по частям или в фоне, высоты не каждый кадр | работает с паками; сбор по частям нужен в любом случае | ×32 не вытянуть: даже в 8–64 раза меньше копий — десятки мс | как сейчас |
| **E. Indirect + SSBO + отсев compute** (GL 4.3–4.6) | быстрее всего на сотнях тысяч | нет на macOS; на Intel indirect медленный и с ошибками (так считает и Flywheel); сложнее | как A |

**Выбор: A** сейчас; **D** (сбор по частям) — параллельно, без него ползущий гигант всё равно дёргается; **B** —
исследование на потом, если игроки с паками захотят гигантов.

Проверено по исходникам (ссылки в конце):

- Незнакомый Iris `ShaderInstance` (наш тоже) под активным паком не пишет ни цвет, ни глубину: `apply()` вызывает
  `DepthColorStorage.disableDepthColor()`. В Oculus 1.20.1 — всегда, в Iris 1.21.1 — если в его конфиге не включено
  `allowUnknownShaders` (по умолчанию выключено); с ним шейдер рисует как есть, без освещения пака, и пропускается в
  теневом проходе. Случая «под паком известно, что работает» для A нет.
- Детект пака: `IrisApi.getInstance().isShaderPackInUse()` (`net.irisshaders.iris.api.v0`, тот же пакет в Iris и
  Oculus; id модов `iris` и `oculus`).
- Майнкрафт просит контекст OpenGL 3.2 core (драйверы дают 4.x, macOS — 4.1). Инстансинг и TBO — 3.1,
  `glDrawElementsInstancedBaseVertex` — 3.2, делитель атрибутов и таймер-запросы — 3.3, compute и SSBO — 4.3.
- Sodium сам не инстансит (большие буферы регионов и multi-draw); наш случай ближе всего к instancing-бэкенду Flywheel.

## 3. Рекомендуемая архитектура

### 3.1 Что лежит на видеокарте

| Буфер | Что внутри | Когда меняется |
|---|---|---|
| Меши: VBO + VAO в формате `DefaultVertexFormat.BLOCK`, индексы — общий `RenderSystem.getSequentialBuffer(QUADS)` | один меш на модель и слой: квады `BakedModel.getQuads` по сторонам; цвет = затенение грани; флаг «красить оттенком» (`tintIndex >= 0`) в свободном `UV2`; ключ — набор списков квадов (обычная модель отдаёт для своего варианта одни и те же объекты) | встретилась новая модель; сброс при перезагрузке ресурсов (`invalidate()`) и смене мира |
| Таблица колонн: TBO `RGBA32UI`, ~32 байта на экземпляр, подряд по мешам | позиция от начала области, ось, флаги (зона, декор, тонкое место под холмом, узел), свет неба и блока с момента запекания, оттенок биома, маска слотов тварей (64 бита), статичный шум зоны (`swell` Пробуждения или фаза `heave` изнанки: от времени они не зависят) | при сборе, сканировании зоны и в `validate()`; не каждый кадр |
| Таблица кадра: TBO `RGBA32F` | до 64 тварей × ~8 текселей (центр, нормаль, направление, A, σ, хвост, ε, время дрожи, рябь, нормаль колец), всё от начала области; земля зоны с её кольцами и глазами игрока; добываемый блок | каждый кадр, `glBufferSubData` на несколько КБ |

«Запечь» колонну для GPU — найти меш по ключу и записать 32 байта: микросекунды против десятков микросекунд
`renderBatched`. Для гиганта это важно: 100 000 колонн при 48 запеканиях за кадр появляются ~35 с при 60 FPS.

### 3.2 Вершинный шейдер

Экземпляр — колонна или её декор. Шейдер берёт запись по `InstanceBase + gl_InstanceID`, считает высоту в центре
вокселя (как `field.at(col.cx, col.cy, col.cz)`) и ставит вершину:

```glsl
float h = 0.0;
for (int b = 0; b < BumpCount; b++)
    if (inMask(col, b)) h += bump(b, center) + ripple(b, center); // BumpShape.evaluate, Ripple.height
if (inZone(col)) h += ground(center, zoneNoise(col));             // AwakeningField.at / HollowField.at
if (h < RENDER_THRESHOLD) { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); return; } // колонна не рисуется

// Копии h, h-1, ..., h-n+1 — один куб от подошвы нижней до верха верхней.
float n = ceil(h);
float along = dot(Position - 0.5, Axis) + 0.5;  // 0 у подошвы блока, 1 у верха (для любой оси)
float lift = along < 0.5 ? h - n + 1.0 : h;     // подошва нижней копии / верх верхней
vec3 pos = colPos + OriginFromCamera + grown(Position) + Axis * lift;
tileT = along < 0.5 ? 0.0 : n;                  // боковая текстура повторится n раз
```

- `bump()` — строка в строку `BumpShape.evaluate` (бугор, хвостовая яма, дрожь с огибающей `max(bump, trail)`), шум —
  порт `PerlinNoise` с таблицей перестановок как `const int[256]`; `ripple()` — `Ripple.height` по
  `HeightField.tangentDistance` и `ringNormal`; `ground()` — слагаемые `AwakeningField.at` и `HollowField.at`.
- Нижняя грань выкидывается (она внутри блока под копией), кроме колонн ближе 2 блоков к камере (`NEAR_CAMERA`). Декор —
  одна копия на `h + 1`, как сейчас. `grown()` — тот же хеш, что `Column.grow`, и рост с расстоянием.
- Точность: колонны — целые от начала области, `OriginFromCamera` считается на процессоре в double; начало для шума
  заранее берётся по модулю периода `PerlinNoise` (256 узлов) — иначе на координатах в миллион дрожь пойдёт ступеньками.
- Свет: `UV2` колонны → `minecraft_sample_lightmap(Sampler2, UV2)`, как в ванильном `rendertype_solid`; день, ночь и
  мерцание факелов приходят с картой освещения. Цвет = затенение из меша × оттенок колонны, если у квада флаг. Uniform
  `Tint` — для `tint on` в автотестах.

Фрагментный шейдер — копия ванильных `rendertype_solid`/`cutout` (отсечение по альфе 0.1, у `cutoutMipped` 0.5) с
`linear_fog`, плюс одно: UV боковой грани — билинейно по углам квада с `fract(tileT)` вдоль оси, выборка `textureGrad` с
производными непрерывной координаты, чтобы на стыках плиток не было полосок мип-уровней.

Вытянутый куб выглядит как стопка непрозрачных кубов без внутренних граней, но это один экземпляр на колонну при любой
высоте: таблица не зависит от высот, и рост амплитуды ничего не пересобирает. Поэтому так рисуются только колонны с
`Column.cube` (целый непрозрачный блок — та же проверка, что в `hidden()`), модель которых состоит из целых граней.

### 3.3 Отсев, свет, туман, особые случаи

- **Отсев.** Таблица режется на ячейки (скажем, 16³) с диапазонами по мешам; процессор проверяет ячейку по фрустуму,
  раздвинув её коробку на максимум высоты в кадре. Задние грани — как у ванильного слоя. Отсев граней по соседям
  (`hidden()`) не нужен: лишние внутренние грани видеокарте дешевле, чем их считать. Отсева по перекрытию нет (решение
  пользователя).
- **Свет — от исходного места, не от смещённого**, как сейчас: вид на обоих путях одинаков. Свет в точке смещения
  потребовал бы 3D-текстуры света области (так делает Flywheel) — потом, если заметят разницу.
- **Туман** — ванильные `FogStart`/`FogEnd`/`FogColor`/`FogShape` из `RenderSystem`: чёрный туман изнанки (`HollowFog`)
  и туман Sodium работают сами.
- **Остаются на процессоре:** заливки (`emitSlid`, у них своё состояние `Column.swell`), мосты прыжка, «шов» (статичную
  часть `seam()` — колонна сзади с другой осью и открытая клетка сбоку — считаем при сборе, на кадр проверяем только
  кандидатов), вывернутые грани у камеры (`emitInside` для единиц колонн, внешние грани рисует GPU), стиль warp, слои
  `translucent` и `tripwire`, не целые непрозрачные блоки (ступени, плиты, заборы, листва, стекло). Колонны делятся на
  GPU и процессорные **при сборе**, и `prepare()` на кадр считает высоты только для процессорных — их единицы.
- **Не рисуются в зоне** узел (флаг в таблице) и блок, который сейчас добывают (его координаты в таблице кадра).
- **Бюджет копий** (`maxDeformedBlocks`) — забота процессорного пути; GPU-пути хватит потолка колонн по памяти
  (32 байта на колонну: миллион — 32 МБ).
- **Пыль** (`kickUpDust`) берёт высоту колонны: для выбранных за тик колонн (их десятки) это `field.at()` на процессоре.

### 3.4 Порядок отрисовки

В `onRenderLevelStage` на `AFTER_BLOCK_ENTITIES`, для каждого непрозрачного слоя: `layer.setupRenderState()` (атлас с
нужными мипами, карта освещения, глубина, отсев — как у ванильного слоя) → ванильные uniform-ы так же, как их ставит
`VertexBuffer.drawWithShader` → `PHANTOM.apply()` → TBO руками на текстурные блоки 12+ (`GlStateManager._activeTexture`,
`glBindTexture(GL_TEXTURE_BUFFER, …)`: `ShaderInstance` знает только 2D-сэмплеры, массивов uniform-ов у него нет —
потому таблицы и в TBO) → polygon offset −4 → свой VAO и по `glDrawElementsInstancedBaseVertex` на меш → отвязать VAO,
`BufferUploader.invalidate()` (иначе ванильный кэш сочтёт свой VAO привязанным) → `PHANTOM.clear()`,
`layer.clearRenderState()`.

Шейдер — `assets/tremor/shaders/core/phantom.json` и `.vsh`/`.fsh`, формат `BLOCK`, регистрация в `RegisterShadersEvent`
(храним экземпляр из колбэка). На Forge 1.20.1 то же событие (`net.minecraftforge.client.event`) и
`new ResourceLocation(...)`; у `fog_distance` в 1.20.1 есть первый аргумент `ModelViewMat`, так что `.vsh` у веток свой.

### 3.5 Переключение и запасной путь

- Конфиг клиента `render.gpuPath`, сначала выключен; по умолчанию — когда пройдёт проверку на обеих платформах.
- Процессорный путь сам, если активен шейдер-пак, нет GL 3.3 или мал `GL_MAX_TEXTURE_BUFFER_SIZE`, выбран стиль warp.
- Проверка каждый кадр (пак переключают на ходу). Сбор (`Column`, `columns`, `ZoneColumns`) общий, переключение
  бесшовное; только процессорный путь допекает `VertexList` по 48 за кадр, и гигант после включения пака проявится
  постепенно — как и сейчас.

```java
/** Whether a shader pack draws the frame: under one our own shader writes nothing (Iris masks colour and depth). */
static boolean shaderPack() {
    if (irisInUse == null) return false;          // neither iris nor oculus is loaded (ModList, once)
    try {
        return (boolean) irisInUse.invokeExact(); // IrisApi.getInstance().isShaderPackInUse(), bound by reflection
    } catch (Throwable t) {
        return true;                              // unknown: the CPU path is the safe one
    }
}
```

## 4. План прототипа

Каждый шаг — отдельный коммит, проверяемый сам по себе; сначала NeoForge, Forge — на шаге 5.

0. **База и Iris.** bench-giant (`TREMOR_AUTOTEST_RD=32`) на 1.5.0 с разбивкой «working it out / to the GPU». С Iris,
   Sodium и паком (Complementary, BSL), низкое солнце, холм ×4: снимок и в лог стадия с `isRenderingShadowPass()` из
   `onRenderLevelStage`. Итог: откуда тени и что процессорный путь обязан сохранить.
1. **Статичное поле кубов.** Шейдер и регистрация, один меш (камень из `getQuads`), TBO с N×N колоннами, смещение
   `sin(время)` в шейдере, отрисовка на `AFTER_BLOCK_ENTITIES`; команда `/tremor gpu field <n>`. Проверка: снимок; туман
   вдали; ночью темно; F3+T не роняет; GUI, рука и F3 целы (утечки состояния GL). Мерить: мс процессора и мс видеокарты
   (`GL_TIME_ELAPSED`) на 65 тысячах и 500 тысячах колонн.
2. **Настоящий бугор, один меш.** `bump()` без дрожи, вытянутые стопки, колонны из `collect()` (таблица пересобирается
   целиком при сборе), одна тварь, конфиг `render.gpuPath`. Проверка: гигант ×4 стоит, снимки обоими путями с `tint on`
   рядом (`tools/contact-sheet.py`) — силуэты совпадают; JUnit на упаковку записи колонны туда-обратно.
3. **Настоящие меши.** Реестр, оттенок и свет колонны, cutout/cutoutMipped, декор. Проверка: луг с цветами, пустыня,
   снег со слоями снега, камень, ночь; после F3+T реестр собирается заново.
4. **Всё поле.** Дрожь, хвостовая яма, рябь, несколько тварей, рост; потом `AwakeningField` и `HollowField`. Проверка:
   echo, ring-terrain, emerge-hill и изнанка обоими путями; GIF кольца, бегущего по земле (движение по снимкам не
   читается).
5. **Гибридный кадр, запасной путь, Forge.** Деление колонн при сборе, процессор — только особые; автопереключение по
   паку и GL; порт на `forge-1.5.0`. Проверка: включить и выключить пак на ходу; `tools/forge-server-test.sh`.
6. **Масштаб.** Ячейки и отсев, обновление таблицы по разнице вместо пересборки, таймер видеокарты в строке `bench`.
   Дальше отдельной задачей — сбор по частям или в фоне.

**Что мерить в bench-giant** (×4/×8/×16/×32, стоит и ползёт, обоими путями): кадр (среднее, p99); мс рендера на
процессоре по частям (подготовка, отправка GPU-пути, процессорные особые случаи); мс видеокарты по таймеру; колонны и
копии; мс и байты обновления таблицы за сбор; мс сбора. Цель: ×16 стоит — меньше 2 мс процессора на рендер, ×32 стоит —
больше 60 FPS на машине из замеров; ползущий гигант после шага 6 упирается уже только в сбор.

## 5. Риски

- **Паки.** С паком гиганты остаются на процессоре и такими же тяжёлыми, а игроков с паками много. Помогут D и, может
  быть, B; игрокам это надо сказать прямо.
- **Два рендера.** Новую механику копий придётся делать дважды, вид может разъехаться. Спасает правило «механика —
  слагаемое поля высот» и автотесты, снимающие оба пути.
- **Состояние GL.** Свой VAO, TBO, активный текстурный блок: забытое восстановление ломает GUI или чужие моды. Обёртки
  `GlStateManager`, `BufferUploader.invalidate()`, проверка GUI после кадра.
- **Железо.** Старые Intel, macOS (GL 4.1), лимит TBO, точность float вдали от нуля — проверка возможностей при старте и
  запасной путь.
- **Вытянутые стопки.** Полоски на стыках плиток при мипах; только целые непрозрачные блоки; у «шва» над обрывом гигант
  даёт до h²/2 копий на колонну на процессоре — если упрёмся, лесенку тоже вытянутыми коробками на GPU.
- **Модели.** Моды связанных текстур могут отдавать новые списки квадов на каждый вызов — ключ по содержимому или
  процессорный путь; несколько индексов оттенка, светящиеся квады — процессорный путь.
- **Сбор и запекание остаются на процессоре.** Без сбора по частям ползущий гигант дёргается, стоящий даёт пик раз в
  10 тиков.
- **Видимость гиганта** от этого не растёт: `server.syncRange`, дальность чанков клиента и `render.renderDistance`
  ограничивают по-прежнему.

## Где проверено

- Iris 1.21.1: [ShadowRenderer](https://github.com/IrisShaders/Iris/blob/1.21.1/common/src/main/java/net/irisshaders/iris/shadows/ShadowRenderer.java),
  [MixinShaderInstance](https://github.com/IrisShaders/Iris/blob/1.21.1/common/src/main/java/net/irisshaders/iris/mixin/MixinShaderInstance.java),
  [IrisConfig](https://github.com/IrisShaders/Iris/blob/1.21.1/common/src/main/java/net/irisshaders/iris/config/IrisConfig.java),
  [MixinShadowRenderer для NeoForge](https://github.com/IrisShaders/Iris/blob/1.21.1/neoforge/src/main/java/net/irisshaders/iris/mixin/forge/MixinShadowRenderer.java),
  [IrisApi](https://github.com/IrisShaders/Iris/blob/1.21.1/common/src/api/java/net/irisshaders/iris/api/v0/IrisApi.java).
- Oculus 1.20.1: [IrisApi](https://github.com/Asek3/Oculus/blob/1.20.1-new/src/main/java/net/irisshaders/iris/api/v0/IrisApi.java),
  [MixinShaderInstance](https://github.com/Asek3/Oculus/blob/1.20.1-new/src/main/java/net/irisshaders/iris/mixin/MixinShaderInstance.java),
  [ShadowRenderer](https://github.com/Asek3/Oculus/blob/1.20.1-new/src/main/java/net/irisshaders/iris/shadows/ShadowRenderer.java).
- Sodium 1.21.1: [LevelRendererMixin](https://github.com/CaffeineMC/sodium/blob/1.21.1/stable/common/src/main/java/net/caffeinemc/mods/sodium/mixin/core/render/world/LevelRendererMixin.java),
  [NeoForgeLevelRenderHooks](https://github.com/CaffeineMC/sodium/blob/1.21.1/stable/neoforge/src/mod/java/net/caffeinemc/mods/sodium/neoforge/level/NeoForgeLevelRenderHooks.java).
- Flywheel 1.21.1: [Backends](https://github.com/Engine-Room/Flywheel/blob/1.21.1/dev/common/src/backend/java/dev/engine_room/flywheel/backend/Backends.java),
  [GlCompat](https://github.com/Engine-Room/Flywheel/blob/1.21.1/dev/common/src/backend/java/dev/engine_room/flywheel/backend/gl/GlCompat.java),
  [InstanceType](https://github.com/Engine-Room/Flywheel/blob/1.21.1/dev/common/src/api/java/dev/engine_room/flywheel/api/instance/InstanceType.java).
- NeoForge 1.21.1: [ClientHooks](https://github.com/neoforged/NeoForge/blob/1.21.1/src/main/java/net/neoforged/neoforge/client/ClientHooks.java)
  (`dispatchRenderStage`), [RenderLevelStageEvent](https://github.com/neoforged/NeoForge/blob/1.21.1/src/main/java/net/neoforged/neoforge/client/event/RenderLevelStageEvent.java),
  [RegisterShadersEvent](https://nekoyue.github.io/ForgeJavaDocs-NG/javadoc/1.21.x-neoforge/net/neoforged/neoforge/client/event/RegisterShadersEvent.html).
- Ванильные шейдеры: `assets/minecraft/shaders/core/rendertype_solid.*` и `include/fog.glsl` в 1.20.1 и 1.21.1
  ([mcmeta](https://github.com/misode/mcmeta)).
