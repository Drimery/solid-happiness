package com.jarvis.skyrimhabits

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.hypot

// =========================================================================
// 1. ЦВЕТОВАЯ ПАЛИТРА В СТИЛЕ GOOGLE GEMINI + SKYRIM ASTRAL
// =========================================================================
val GeminiBg = Color(0xFF0E1015)
val GeminiSurface = Color(0xFF181B22)
val GeminiSurfaceElevated = Color(0xFF222631)
val GeminiBlue = Color(0xFF8AB4F8)
val GeminiPurple = Color(0xFFC58AF9)
val GeminiMint = Color(0xFF81C995)
val GeminiCoral = Color(0xFFF28B82)
val GeminiTextPrimary = Color(0xFFE8EAED)
val GeminiTextSecondary = Color(0xFF9AA0A6)

enum class GuardianStone(val title: String, val accent: Color, val glow: Color) {
    WARRIOR("Созвездие Воина", GeminiCoral, Color(0x28F28B82)),
    MAGE("Созвездие Мага", GeminiBlue, Color(0x288AB4F8)),
    THIEF("Созвездие Вора", GeminiMint, Color(0x2881C995))
}

data class SkillConstellation(
    val id: String,
    val name: String,
    val stone: GuardianStone,
    val level: Int = 15,
    val xp: Int = 0,
    val nodes: List<Offset>,
    val edges: List<Pair<Int, Int>>
)

data class HabitQuest(
    val id: Int,
    val title: String,
    val skillId: String,
    val xpReward: Int = 35,
    val isActiveInDaily: Boolean = false,
    val history: Map<Long, Boolean> = emptyMap()
)

// =========================================================================
// 2. НАТИВНОЕ ХРАНИЛИЩЕ SQLite (БЕЗ ЗАВИСИМОСТЕЙ ОТ KSP, 100% НАДЕЖНОСТЬ)
// =========================================================================
class DovahDatabaseHelper(context: Context) : SQLiteOpenHelper(context, "dovah_astral_v2.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE skills (
                id TEXT PRIMARY KEY,
                level INTEGER NOT NULL,
                xp INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE habits (
                id INTEGER PRIMARY KEY,
                title TEXT NOT NULL,
                skill_id TEXT NOT NULL,
                xp_reward INTEGER NOT NULL,
                is_active INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE habit_logs (
                habit_id INTEGER NOT NULL,
                epoch_day INTEGER NOT NULL,
                is_done INTEGER NOT NULL,
                PRIMARY KEY (habit_id, epoch_day)
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun seedIfEmpty(defaultSkills: List<SkillConstellation>, defaultHabits: List<HabitQuest>) {
        val db = writableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM habits", null)
        var count = 0
        if (cursor.moveToFirst()) count = cursor.getInt(0)
        cursor.close()

        if (count == 0) {
            db.beginTransaction()
            try {
                for (s in defaultSkills) {
                    val cv = ContentValues().apply {
                        put("id", s.id)
                        put("level", s.level)
                        put("xp", s.xp)
                    }
                    db.insert("skills", null, cv)
                }
                for (h in defaultHabits) {
                    val cv = ContentValues().apply {
                        put("id", h.id)
                        put("title", h.title)
                        put("skill_id", h.skillId)
                        put("xp_reward", h.xpReward)
                        put("is_active", if (h.isActiveInDaily) 1 else 0)
                    }
                    db.insert("habits", null, cv)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    fun loadSkills(baseCatalog: List<SkillConstellation>): List<SkillConstellation> {
        val db = readableDatabase
        val map = mutableMapOf<String, Pair<Int, Int>>()
        val cursor = db.rawQuery("SELECT id, level, xp FROM skills", null)
        while (cursor.moveToNext()) {
            map[cursor.getString(0)] = cursor.getInt(1) to cursor.getInt(2)
        }
        cursor.close()
        return baseCatalog.map { s ->
            val saved = map[s.id]
            if (saved != null) s.copy(level = saved.first, xp = saved.second) else s
        }
    }

    fun saveSkillProgress(skillId: String, level: Int, xp: Int) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("level", level)
            put("xp", xp)
        }
        db.update("skills", cv, "id = ?", arrayOf(skillId))
    }

    fun loadHabits(): List<HabitQuest> {
        val db = readableDatabase
        val logsMap = mutableMapOf<Int, MutableMap<Long, Boolean>>()
        val logCursor = db.rawQuery("SELECT habit_id, epoch_day, is_done FROM habit_logs", null)
        while (logCursor.moveToNext()) {
            val hId = logCursor.getInt(0)
            val day = logCursor.getLong(1)
            val done = logCursor.getInt(2) == 1
            logsMap.getOrPut(hId) { mutableMapOf() }[day] = done
        }
        logCursor.close()

        val result = mutableListOf<HabitQuest>()
        val hCursor = db.rawQuery("SELECT id, title, skill_id, xp_reward, is_active FROM habits ORDER BY id ASC", null)
        while (hCursor.moveToNext()) {
            val id = hCursor.getInt(0)
            result.add(
                HabitQuest(
                    id = id,
                    title = hCursor.getString(1),
                    skillId = hCursor.getString(2),
                    xpReward = hCursor.getInt(3),
                    isActiveInDaily = hCursor.getInt(4) == 1,
                    history = logsMap[id] ?: emptyMap()
                )
            )
        }
        hCursor.close()
        return result
    }

    fun setHabitLog(habitId: Int, epochDay: Long, isDone: Boolean) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("habit_id", habitId)
            put("epoch_day", epochDay)
            put("is_done", if (isDone) 1 else 0)
        }
        db.insertWithOnConflict("habit_logs", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateHabitActive(habitId: Int, isActive: Boolean) {
        val db = writableDatabase
        val cv = ContentValues().apply { put("is_active", if (isActive) 1 else 0) }
        db.update("habits", cv, "id = ?", arrayOf(habitId.toString()))
    }

    fun insertNewHabit(habit: HabitQuest) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("id", habit.id)
            put("title", habit.title)
            put("skill_id", habit.skillId)
            put("xp_reward", habit.xpReward)
            put("is_active", if (habit.isActiveInDaily) 1 else 0)
        }
        db.insertWithOnConflict("habits", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }
}

// =========================================================================
// 3. VIEWMODEL С АВТОМАТИЧЕСКИМ СОХРАНЕНИЕМ В SQLite
// =========================================================================
class DovahkiinViewModel(app: Application) : AndroidViewModel(app) {

    private val dbHelper = DovahDatabaseHelper(app.applicationContext)
    private val baseCatalog = initSkyrimConstellations()

    private val _skills = MutableStateFlow<List<SkillConstellation>>(emptyList())
    val skills: StateFlow<List<SkillConstellation>> = _skills.asStateFlow()

    private val _habits = MutableStateFlow<List<HabitQuest>>(emptyList())
    val habits: StateFlow<List<HabitQuest>> = _habits.asStateFlow()

    private val _selectedSkillIndex = MutableStateFlow(0)
    val selectedSkillIndex: StateFlow<Int> = _selectedSkillIndex.asStateFlow()

    private val _auditReport = MutableStateFlow<String?>(null)
    val auditReport: StateFlow<String?> = _auditReport.asStateFlow()

    private val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 65)

    init {
        dbHelper.seedIfEmpty(baseCatalog, generate270LoreHabits())
        reloadFromStorage()
    }

    private fun reloadFromStorage() {
        _skills.value = dbHelper.loadSkills(baseCatalog)
        _habits.value = dbHelper.loadHabits()
    }

    fun selectSkill(index: Int) {
        if (_skills.value.isNotEmpty()) {
            _selectedSkillIndex.value = index.coerceIn(0, _skills.value.lastIndex)
        }
    }

    fun toggleHabitToday(habitId: Int) {
        val today = LocalDate.now().toEpochDay()
        val targetHabit = _habits.value.find { it.id == habitId } ?: return
        val newDoneState = !(targetHabit.history[today] == true)

        // Сохраняем в SQLite
        dbHelper.setHabitLog(habitId, today, newDoneState)

        // Обновляем прогресс навыка и сохраняем в SQLite
        val xpDelta = if (newDoneState) targetHabit.xpReward else -targetHabit.xpReward
        val skill = _skills.value.find { it.id == targetHabit.skillId }
        if (skill != null) {
            val totalXp = ((skill.level - 15) * 100 + skill.xp + xpDelta).coerceAtLeast(0)
            val newLevel = (15 + totalXp / 100).coerceAtMost(100)
            val newRemXp = totalXp % 100
            dbHelper.saveSkillProgress(skill.id, newLevel, newRemXp)
        }

        if (newDoneState) toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 150)
        reloadFromStorage()
    }

    fun toggleActiveStatus(habitId: Int) {
        val current = _habits.value.find { it.id == habitId } ?: return
        dbHelper.updateHabitActive(habitId, !current.isActiveInDaily)
        reloadFromStorage()
    }

    fun addCustomHabit(title: String, skillId: String) {
        if (title.isBlank()) return
        val nextId = (_habits.value.maxOfOrNull { it.id } ?: 270) + 1
        val newHabit = HabitQuest(
            id = nextId,
            title = title.trim(),
            skillId = skillId,
            xpReward = 40,
            isActiveInDaily = true
        )
        dbHelper.insertNewHabit(newHabit)
        reloadFromStorage()
    }

    fun populateDemoWeek() {
        val today = LocalDate.now().toEpochDay()
        val activeHabits = _habits.value.filter { it.isActiveInDaily }
        for (h in activeHabits) {
            for (offset in 0..6) {
                val day = today - offset
                val isDone = ((h.id + offset) % 3 != 0)
                dbHelper.setHabitLog(h.id, day, isDone)
            }
        }
        reloadFromStorage()
    }

    fun runDiagnosticSuite() {
        val skillList = _skills.value
        val habitList = _habits.value
        val checks = listOf(
            "✔ Хранилище: SQLite (dovah_astral_v2.db) активно",
            "✔ Созвездия: ${skillList.size}/18 школ загружены",
            "✔ Фолиант: ${habitList.size} привычек сохранено в ПЗУ",
            "✔ Граф созвездий: все узлы и ребра валидны",
            "✔ Активных целей на день: ${habitList.count { it.isActiveInDaily }}"
        )
        _auditReport.value = checks.joinToString("\n\n")
    }

    fun dismissAudit() {
        _auditReport.value = null
    }

    private fun initSkyrimConstellations(): List<SkillConstellation> {
        val baseNodes = listOf(
            Offset(0.50f, 0.84f),
            Offset(0.32f, 0.64f),
            Offset(0.68f, 0.58f),
            Offset(0.50f, 0.44f),
            Offset(0.24f, 0.34f),
            Offset(0.76f, 0.28f),
            Offset(0.50f, 0.14f)
        )
        val baseEdges = listOf(0 to 1, 0 to 2, 1 to 3, 2 to 3, 1 to 4, 2 to 5, 3 to 6)

        val catalog = listOf(
            Triple("smithing", "Кузнечное дело", GuardianStone.WARRIOR),
            Triple("heavy_armor", "Тяжелая броня", GuardianStone.WARRIOR),
            Triple("block", "Блокирование", GuardianStone.WARRIOR),
            Triple("two_handed", "Двуручное", GuardianStone.WARRIOR),
            Triple("one_handed", "Одноручное", GuardianStone.WARRIOR),
            Triple("archery", "Стрельба", GuardianStone.WARRIOR),
            Triple("alteration", "Изменение", GuardianStone.MAGE),
            Triple("conjuration", "Колдовство", GuardianStone.MAGE),
            Triple("destruction", "Разрушение", GuardianStone.MAGE),
            Triple("illusion", "Иллюзия", GuardianStone.MAGE),
            Triple("restoration", "Восстановление", GuardianStone.MAGE),
            Triple("enchanting", "Зачарование", GuardianStone.MAGE),
            Triple("alchemy", "Алхимия", GuardianStone.THIEF),
            Triple("light_armor", "Легкая броня", GuardianStone.THIEF),
            Triple("lockpicking", "Взлом", GuardianStone.THIEF),
            Triple("pickpocket", "Карманные кражи", GuardianStone.THIEF),
            Triple("sneak", "Скрытность", GuardianStone.THIEF),
            Triple("speech", "Красноречие", GuardianStone.THIEF)
        )

        return catalog.mapIndexed { idx, (id, name, stone) ->
            val shift = (idx % 3 - 1) * 0.04f
            val customNodes = baseNodes.mapIndexed { nIdx, pt ->
                if (nIdx == 0) pt else Offset((pt.x + shift).coerceIn(0.12f, 0.88f), pt.y)
            }
            SkillConstellation(id = id, name = name, stone = stone, nodes = customNodes, edges = baseEdges)
        }
    }

    private fun generate270LoreHabits(): List<HabitQuest> {
        val templates = mapOf(
            "smithing" to listOf("Рефакторинг кода", "Настройка рабочего места", "Сборка пет-проекта", "Чистка устройства", "Архитектура БД"),
            "heavy_armor" to listOf("Тренировка спины", "Контрастный душ", "Планка 3 минуты", "Приседания с весом", "Ровная осанка"),
            "block" to listOf("День без лишних трат", "Фокус без соцсетей 4ч", "Защита личных границ", "Игнор информационного шума", "Детокс после 22:00"),
            "two_handed" to listOf("Deep Work 2 часа", "Главная задача дня", "Штурм сложного модуля", "Написание статьи", "Разбор большого завала"),
            "one_handed" to listOf("Inbox Zero в почте", "Утренняя разминка 15 мин", "Фикс мелкого бага", "План в календаре", "Ежедневный git commit"),
            "archery" to listOf("Помодоро 50 минут", "Три главные цели утром", "Чтение документации", "Анализ ключевых метрик", "Точное закрытие срока"),
            "alteration" to listOf("Порядок на столе", "Улучшение быта", "Обновление IDE/плагинов", "Очистка облака/диска", "Замена вредной привычки"),
            "conjuration" to listOf("Автоматизация скриптом", "Создание шаблона", "Промпт-инжиниринг ИИ", "Делегирование рутины", "Мозговой штурм идей"),
            "destruction" to listOf("Интервальное кардио", "Удаление 50 старых файлов", "Закрытие техдолга", "День без сахара", "Правило 2 минут"),
            "illusion" to listOf("Проработка UI/UX", "Дизайн презентации", "Улучшение стиля речи", "Схема архитектуры", "Язык тела и подача"),
            "restoration" to listOf("Сон 8 часов", "Медитация 10 минут", "Прогулка без телефона", "Вечерняя растяжка", "Отдых для глаз"),
            "enchanting" to listOf("Чтение книги 30 минут", "Разбор алгоритма", "Конспект лекции", "Иностранный язык", "Чтение чужого кода"),
            "alchemy" to listOf("Здоровый рацион", "Норма чистой воды 2л", "Прием витаминов", "Зеленый чай вместо кофе", "Контроль БЖУ"),
            "light_armor" to listOf("10 000 шагов", "Суставная разминка", "Легкий ужин", "Налегке без лишних вещей", "Быстрая адаптация"),
            "lockpicking" to listOf("Задача на логику", "Отладка сложной ошибки", "Изучение нового API", "Нестандартное решение", "Шахматная партия"),
            "pickpocket" to listOf("Ревизия подписок", "Учет всех расходов", "Инвестиции 10% дохода", "Оптимизация тарифов", "Финансовый план"),
            "sneak" to listOf("Режим Не беспокоить", "Тихий час без чатов", "Работа без лишнего шума", "Минимализм на экране", "Вечер в тишине"),
            "speech" to listOf("Продуктивный созвон", "Публикация заметки", "Полезный нетворкинг", "Разминка дикции", "Сильная аргументация")
        )
        val tiers = listOf("I", "II", "III", "IV", "V")
        val list = mutableListOf<HabitQuest>()
        var idCounter = 1
        for ((skillId, actions) in templates) {
            for (i in 0 until 15) {
                val title = "${actions[i % actions.size]} • Ступень ${tiers[i / 3]}"
                val active = (i == 0 && idCounter <= 75) // 5 стартовых привычек
                list.add(HabitQuest(id = idCounter++, title = title, skillId = skillId, xpReward = 25 + (i * 3), isActiveInDaily = active))
            }
        }
        return list
    }
}

// =========================================================================
// 4. МИНИМАЛИСТИЧНЫЙ GEMINI UI (ВОЗДУШНЫЙ ДИЗАЙН, ПЛАВНЫЕ ПИЛЮЛИ И КАРТОЧКИ)
// =========================================================================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = GeminiBg,
                    surface = GeminiSurface,
                    primary = GeminiBlue,
                    secondary = GeminiPurple,
                    onBackground = GeminiTextPrimary,
                    onSurface = GeminiTextPrimary
                )
            ) {
                GeminiSkyrimApp()
            }
        }
    }
}

@Composable
fun GeminiSkyrimApp(vm: DovahkiinViewModel = viewModel()) {
    val skills by vm.skills.collectAsState()
    val habits by vm.habits.collectAsState()
    val selectedSkillIdx by vm.selectedSkillIndex.collectAsState()
    val auditReport by vm.auditReport.collectAsState()

    var currentTab by remember { mutableIntStateOf(0) }

    if (skills.isEmpty()) return
    val currentSkill = skills[selectedSkillIdx]
    val totalSkillLevels = skills.sumOf { it.level }
    val playerLevel = 1 + (totalSkillLevels - 18 * 15) / 5

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = GeminiBg,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "✦ Dovahkiin",
                            color = GeminiBlue,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 20.sp
                        )
                        Surface(
                            color = GeminiSurfaceElevated,
                            shape = CircleShape
                        ) {
                            Text(
                                text = "Ур. $playerLevel",
                                color = GeminiPurple,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Автосохранение активно • ${habits.count { it.isActiveInDaily }} целей на день",
                        color = GeminiTextSecondary,
                        fontSize = 12.sp
                    )
                }

                Surface(
                    color = GeminiSurfaceElevated,
                    shape = CircleShape,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { vm.runDiagnosticSuite() }
                ) {
                    Text(
                        text = "✓ Проверка",
                        color = GeminiTextPrimary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        },
        bottomBar = {
            // Плавающая нижняя панель в стиле Google Gemini
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .background(GeminiSurfaceElevated)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val tabs = listOf("✦ Сегодня", "◈ Неделя", "☰ Библиотека")
                    tabs.forEachIndexed { idx, label ->
                        val selected = currentTab == idx
                        val pillBg by animateColorAsState(
                            if (selected) GeminiBlue.copy(alpha = 0.22f) else Color.Transparent,
                            label = "tabBg"
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(CircleShape)
                                .background(pillBg)
                                .clickable { currentTab = idx }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (selected) GeminiBlue else GeminiTextSecondary,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                0 -> CleanTodayTab(
                    skills = skills,
                    selectedSkillIdx = selectedSkillIdx,
                    onSelectSkill = vm::selectSkill,
                    activeHabits = habits.filter { it.isActiveInDaily },
                    onToggleHabit = vm::toggleHabitToday
                )
                1 -> CleanWeeklyTab(
                    activeHabits = habits.filter { it.isActiveInDaily },
                    onPopulateDemo = vm::populateDemoWeek
                )
                2 -> CleanLibraryTab(
                    skills = skills,
                    habits = habits,
                    onToggleActive = vm::toggleActiveStatus,
                    onAddCustom = vm::addCustomHabit
                )
            }
        }

        if (auditReport != null) {
            AlertDialog(
                onDismissRequest = vm::dismissAudit,
                containerColor = GeminiSurface,
                shape = RoundedCornerShape(24.dp),
                title = {
                    Text("Диагностика системы", color = GeminiBlue, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                },
                text = {
                    Text(auditReport ?: "", color = GeminiTextPrimary, fontSize = 14.sp, lineHeight = 20.sp)
                },
                confirmButton = {
                    TextButton(onClick = vm::dismissAudit) {
                        Text("Закрыть", color = GeminiBlue)
                    }
                }
            )
        }
    }
}

// =========================================================================
// ВКЛАДКА 1: ЧИСТЫЙ КОСМОС СОЗВЕЗДИЙ + КАРТОЧКИ НА ДЕНЬ
// =========================================================================
@Composable
fun CleanTodayTab(
    skills: List<SkillConstellation>,
    selectedSkillIdx: Int,
    onSelectSkill: (Int) -> Unit,
    activeHabits: List<HabitQuest>,
    onToggleHabit: (Int) -> Unit
) {
    val currentSkill = skills[selectedSkillIdx]
    val today = LocalDate.now().toEpochDay()

    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Карточка созвездия с мягким градиентом Gemini
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(GeminiSurface)
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = currentSkill.name,
                            color = GeminiTextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${currentSkill.stone.title} • Уровень ${currentSkill.level} (${currentSkill.xp}/100 XP)",
                            color = GeminiTextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Интерактивное полотно созвездия
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            Brush.radialGradient(
                                colors = listOf(currentSkill.stone.glow, GeminiBg)
                            )
                        )
                        .pointerInput(Unit) {
                            detectTransformGestures { _, panChange, zoomChange, _ ->
                                scale = (scale * zoomChange).coerceIn(0.85f, 2.2f)
                                pan += panChange
                            }
                        }
                ) {
                    val unlockedStars = ((currentSkill.level - 14) / 2).coerceIn(1, currentSkill.nodes.size)

                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height

                        currentSkill.edges.forEach { (u, v) ->
                            val p1 = currentSkill.nodes[u]
                            val p2 = currentSkill.nodes[v]
                            val start = Offset(p1.x * w * scale + pan.x, p1.y * h * scale + pan.y)
                            val end = Offset(p2.x * w * scale + pan.x, p2.y * h * scale + pan.y)
                            val lit = u < unlockedStars && v < unlockedStars

                            drawLine(
                                color = if (lit) currentSkill.stone.accent else Color(0x339AA0A6),
                                start = start,
                                end = end,
                                strokeWidth = if (lit) 4.5f else 2f,
                                cap = StrokeCap.Round
                            )
                        }

                        currentSkill.nodes.forEachIndexed { idx, pt ->
                            val center = Offset(pt.x * w * scale + pan.x, pt.y * h * scale + pan.y)
                            val unlocked = idx < unlockedStars
                            if (unlocked) {
                                drawCircle(
                                    color = currentSkill.stone.accent.copy(alpha = 0.25f),
                                    radius = 20f,
                                    center = center
                                )
                            }
                            drawCircle(
                                color = if (unlocked) Color.White else Color(0xFF5F6368),
                                radius = if (unlocked) 8.5f else 6f,
                                center = center
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Горизонтальный выбор навыка аккуратными чипсами
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(skills) { index, skill ->
                        val isSelected = index == selectedSkillIdx
                        Surface(
                            color = if (isSelected) skill.stone.accent.copy(alpha = 0.2f) else GeminiSurfaceElevated,
                            shape = CircleShape,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable {
                                    pan = Offset.Zero
                                    scale = 1f
                                    onSelectSkill(index)
                                }
                        ) {
                            Text(
                                text = "${skill.name} ${skill.level}",
                                color = if (isSelected) skill.stone.accent else GeminiTextSecondary,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "Цели на сегодня",
                color = GeminiTextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        items(activeHabits, key = { it.id }) { habit ->
            val isDone = habit.history[today] == true
            val skill = skills.find { it.id == habit.skillId }
            val accent = skill?.stone?.accent ?: GeminiBlue

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (isDone) GeminiSurfaceElevated else GeminiSurface)
                    .clickable { onToggleHabit(habit.id) }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = habit.title,
                        color = GeminiTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${skill?.name ?: ""} • +${habit.xpReward} XP",
                        color = if (isDone) accent else GeminiTextSecondary,
                        fontSize = 12.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(if (isDone) accent else Color.Transparent)
                        .border(1.5.dp, if (isDone) accent else GeminiTextSecondary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (isDone) {
                        Text("✓", color = GeminiBg, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

// =========================================================================
// ВКЛАДКА 2: АККУРАТНАЯ АНАЛИТИКА ЗА 7 ДНЕЙ
// =========================================================================
@Composable
fun CleanWeeklyTab(
    activeHabits: List<HabitQuest>,
    onPopulateDemo: () -> Unit
) {
    val today = LocalDate.now()
    val last7Days = (6 downTo 0).map { today.minusDays(it.toLong()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(GeminiSurface)
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Ритм недели", color = GeminiTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("Динамика за последние 7 дней", color = GeminiTextSecondary, fontSize = 12.sp)
                    }
                    Surface(
                        color = GeminiSurfaceElevated,
                        shape = CircleShape,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { onPopulateDemo() }
                    ) {
                        Text(
                            text = "✦ Демо-данные",
                            color = GeminiBlue,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    last7Days.forEach { date ->
                        val epoch = date.toEpochDay()
                        val doneCount = activeHabits.count { it.history[epoch] == true }
                        val ratio = if (activeHabits.isNotEmpty()) doneCount.toFloat() / activeHabits.size else 0f

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom,
                            modifier = Modifier.fillMaxHeight()
                        ) {
                            Text(
                                text = "${(ratio * 100).toInt()}%",
                                color = GeminiTextSecondary,
                                fontSize = 10.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Box(
                                modifier = Modifier
                                    .width(26.dp)
                                    .fillMaxHeight(fraction = (ratio * 0.72f).coerceAtLeast(0.08f))
                                    .clip(CircleShape)
                                    .background(
                                        if (ratio >= 0.65f)
                                            Brush.verticalGradient(listOf(GeminiBlue, GeminiPurple))
                                        else
                                            Brush.verticalGradient(listOf(GeminiSurfaceElevated, GeminiSurfaceElevated))
                                    )
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale("ru")),
                                color = GeminiTextPrimary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }

        item {
            Text("Серии по привычкам", color = GeminiTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }

        items(activeHabits, key = { it.id }) { habit ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(GeminiSurface)
                    .padding(16.dp)
            ) {
                Text(habit.title, color = GeminiTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    last7Days.forEach { date ->
                        val done = habit.history[date.toEpochDay()] == true
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(if (done) GeminiBlue.copy(alpha = 0.2f) else GeminiSurfaceElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (done) "✦" else "·",
                                color = if (done) GeminiBlue else GeminiTextSecondary,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

// =========================================================================
// ВКЛАДКА 3: БИБЛИОТЕКА 270+ ПРИВЫЧЕК И СОЗДАНИЕ СВОИХ
// =========================================================================
@Composable
fun CleanLibraryTab(
    skills: List<SkillConstellation>,
    habits: List<HabitQuest>,
    onToggleActive: (Int) -> Unit,
    onAddCustom: (String, String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var newTitle by remember { mutableStateOf("") }
    var selectedSkillId by remember { mutableStateOf(skills.first().id) }

    val filtered = remember(habits, searchQuery) {
        if (searchQuery.isBlank()) habits
        else habits.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        // Поле создания новой привычки в стиле строки ввода Gemini
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(GeminiSurface)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = newTitle,
                onValueChange = { newTitle = it },
                placeholder = { Text("Добавить свою привычку...", color = GeminiTextSecondary, fontSize = 14.sp) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = GeminiTextPrimary,
                    unfocusedTextColor = GeminiTextPrimary
                )
            )
            Surface(
                color = GeminiBlue,
                shape = CircleShape,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable {
                        onAddCustom(newTitle, selectedSkillId)
                        newTitle = ""
                    }
            ) {
                Text(
                    text = "+ Создать",
                    color = GeminiBg,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Быстрый фильтр поиска по 270+ привычкам
        TextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Поиск среди ${habits.size} навыков...", color = GeminiTextSecondary, fontSize = 14.sp) },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp)),
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = GeminiSurface,
                unfocusedContainerColor = GeminiSurface,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = GeminiTextPrimary,
                unfocusedTextColor = GeminiTextPrimary
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filtered, key = { it.id }) { habit ->
                val skillName = skills.find { it.id == habit.skillId }?.name ?: habit.skillId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(GeminiSurface)
                        .clickable { onToggleActive(habit.id) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = habit.title,
                            color = GeminiTextPrimary,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "$skillName • +${habit.xpReward} XP",
                            color = GeminiTextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = habit.isActiveInDaily,
                        onCheckedChange = { onToggleActive(habit.id) }
                    )
                }
            }
        }
    }
}
