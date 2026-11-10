package com.tuntas.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tuntas.app.domain.*
import com.tuntas.app.platform.blocker.BlockerState
import com.tuntas.app.platform.scheduling.ReconciliationReason
import com.tuntas.app.platform.scheduling.SchedulingState
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import java.util.UUID

data class AppOption(val packageName: String, val label: String)
private val dayLabels = listOf("Senin", "Selasa", "Rabu", "Kamis", "Jumat", "Sabtu", "Minggu")
private val draftSaver = listSaver<TaskDraft, String>(
    save = { listOf(it.title, it.date, it.time, it.lead, it.kind.name, it.weekdays.joinToString(",") { d -> d.name }, it.monthDay) },
    restore = { TaskDraft(it[0], it[1], it[2], it[3], RepeatKind.valueOf(it[4]), it[5].split(',').filter(String::isNotEmpty).map(DayOfWeek::valueOf).toSet(), it[6]) }
)

@Composable
fun TuntasScreen(
    controller: TaskController, blocker: BlockerState, scheduling: SchedulingState,
    apps: List<AppOption>, interceptedPackage: String?, interceptionEvent: Int,
    onAccessibility: () -> Unit, onExactAlarm: () -> Unit, onDialer: () -> Unit, onOpenApp: (String) -> Unit
) {
    val state by controller.state.collectAsState()
    var screen by rememberSaveable { mutableStateOf("Task") }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<TaskState?>(null) }
    var deleting by remember { mutableStateOf<TaskState?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    val enabled = state.loaded && !state.busy
    val mode = presentationMode(state)
    val destination = normalDestination(state, screen, editingId)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(mode) {
        if (mode != PresentationMode.NORMAL) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
            editingId = null; deleting = null; confirmation = null; screen = "Task"; search = ""
        }
    }
    // In-app deadline wakeup uses the existing reconciled platform trigger, not another scheduler.
    // No periodic editor busy toggles / IME feedback loop. Alarm and timer share the serial executor.
    LaunchedEffect(scheduling.pendingTrigger?.triggerAtEpochMillis) {
        scheduling.pendingTrigger?.triggerAtEpochMillis?.let { at ->
            delay((at - System.currentTimeMillis()).coerceAtLeast(0))
            controller.refresh(ReconciliationReason.MANUAL_DIAGNOSTIC)
        }
    }
    LaunchedEffect(interceptionEvent) {
        if (interceptedPackage != null && presentationMode(controller.state.value) == PresentationMode.NORMAL) {
            screen = "Fokus"; editingId = null
        }
    }
    // The branch is synchronous: restored tabs/editor/dialogs never render for even one locked frame.
    if (destination == null) {
        RestrictedScreen(state, controller, blocker, scheduling, apps, onDialer, onOpenApp)
        return
    }
    BackHandler(enabled = editingId != null || screen != "Task") { if (editingId != null) editingId = null else screen = "Task" }

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.statusBars,
        bottomBar = {
            if (editingId == null) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                listOf("Task", "Fokus", "Darurat", "Izin").forEach { tab ->
                    NavigationBarItem(selected = screen == tab, onClick = { screen = tab },
                        icon = { NavigationGlyph(tab) }, label = { Text(tab) })
                }
            }
        }
    ) { insets ->
        Column(Modifier.padding(insets).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Tuntas", style = MaterialTheme.typography.titleLarge)
                    MutedText("Versi uji • data lokal")
                }
                if (editingId != null) TextButton(onClick = { editingId = null }) { Text("Kembali") }
                else Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer) {
                    Text("OFFLINE", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let {
                Notice(it, warning = true)
                OutlinedButton(onClick = { controller.refresh(ReconciliationReason.MANUAL_DIAGNOSTIC) }, enabled = !state.busy) { Text("Muat ulang / coba rekonsiliasi") }
            }
            if (editingId != null) {
                val id = editingId!!
                TaskEditor(id, state.snapshot.tasks[id]?.task, enabled, state.saveVersion, controller::save) { editingId = null }
            } else when (screen) {
                "Task" -> {
                    SectionTitle("Ruang untuk fokus", "Atur yang penting. Selesaikan satu per satu.")
                    Button(onClick = { editingId = UUID.randomUUID().toString() }, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("+  Tambah task") }
                    if (!state.loaded) Notice("Memuat data lokal… Jika gagal, gunakan Muat ulang.")
                    else if (state.snapshot.tasks.isEmpty()) EmptyState("Mulai dari satu hal", "Buat task pertamamu, tentukan jam mulai, lalu beri ruang untuk fokus.")
                    else {
                        val pendingCount = state.snapshot.tasks.values.count { it.pending != null }
                        Text("TASK KAMU • ${state.snapshot.tasks.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (pendingCount > 0) Notice("$pendingCount task tertunda — buka Fokus untuk menyelesaikan.")
                    }
                    if (!blocker.isServiceReady || !scheduling.exactAlarmPermissionGranted || scheduling.isDegraded) {
                        Notice(if (!blocker.isServiceReady) "Pembatasan belum siap. Periksa izin dan sambungan layanan di Izin." else "Alarm tidak tepat: pengingat dapat terlambat. Periksa Izin.", true)
                        TextButton(onClick = { screen = "Izin" }) { Text("Periksa kesiapan") }
                    }
                    state.snapshot.tasks.values.sortedWith(compareBy<TaskState> { it.pending == null }.thenBy { it.task.firstDate.atTime(it.task.startTime) }).forEach { row ->
                        TaskCard(row, enabled, { if (presentationMode(controller.state.value) == PresentationMode.NORMAL) editingId = row.task.id }, { if (presentationMode(controller.state.value) == PresentationMode.NORMAL) deleting = row }, { confirmation = row })
                    }
                }
                "Fokus" -> {
                    val pending = state.snapshot.tasks.values.filter { it.pending != null }
                    SectionTitle("Satu per satu.", "Tidak perlu terburu-buru. Kembali ke hal yang penting.")
                    if (pending.isEmpty()) EmptyState("Ruangmu sudah tenang", "Tidak ada task tertunda. Task terjadwal akan muncul di sini saat waktunya.")
                    else Notice("${pending.size} task tertunda. Pembatasan diminta sampai semuanya selesai.")
                    if (interceptedPackage != null) MutedText("Aplikasi di luar daftar darurat diarahkan ke Tuntas saat ada task tertunda.")
                    Notice(if (blocker.isServiceReady) "Layanan tersambung • pembatasan best-effort, bukan jaminan." else "Pembatasan tidak siap: izin aksesibilitas belum aktif atau layanan terputus.", !blocker.isServiceReady)
                    if (!scheduling.exactAlarmPermissionGranted || scheduling.isDegraded) Notice("Alarm tepat tidak tersedia: pengingat dapat terlambat.", true)
                    pending.forEach { row -> TaskCard(row, enabled, { if (presentationMode(controller.state.value) == PresentationMode.NORMAL) editingId = row.task.id }, { if (presentationMode(controller.state.value) == PresentationMode.NORMAL) deleting = row }, { confirmation = row }) }
                    OutlinedButton(onClick = onDialer, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Buka telepon (tanpa menelepon)") }
                    TextButton(onClick = { screen = "Darurat" }, modifier = Modifier.fillMaxWidth()) { Text("Aplikasi darurat tetap tersedia") }
                    MutedText("Tertunda melewati tengah malam tetap wajib diselesaikan. Beberapa jadwal terlewat dari task yang sama digabung menjadi satu.")
                }
                "Darurat" -> {
                    SectionTitle("Tetap terhubung", "Aplikasi pilihanmu tetap tersedia saat fokus.")
                    Notice("Tuntas, telepon/darurat, keyboard dan bagian sistem penting selalu diizinkan. Aturan sistem wajib tidak dapat diubah.")
                    MutedText("Tombol telepon pernah diuji tanpa panggilan pada perangkat uji; akses darurat dari lockscreen dan perangkat lain belum diverifikasi.")
                    OutlinedButton(onClick = onDialer, modifier = Modifier.fillMaxWidth()) { Text("Buka telepon (tanpa menelepon)") }
                    OutlinedTextField(search, { search = it }, label = { Text("Cari aplikasi") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    val options = (apps + state.snapshot.allowlist.map { pkg -> AppOption(pkg, pkg) }).distinctBy { it.packageName }
                        .sortedBy { it.label.lowercase() }.filter { it.label.contains(search, true) || it.packageName.contains(search, true) }
                    if (options.isEmpty()) MutedText(if (search.isBlank()) "Tidak ada aplikasi launcher terdeteksi. Coba buka ulang Tuntas setelah memasang aplikasi." else "Tidak ada aplikasi yang cocok.")
                    options.forEach { option ->
                        val mandatory = option.packageName in blocker.mandatorySystemSafePackages
                        val selected = mandatory || option.packageName in state.snapshot.allowlist
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(selected, enabled && !mandatory, Role.Checkbox) { controller.setAllowlisted(option.packageName, it) }, verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(selected, onCheckedChange = null)
                                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                        Text(option.label, style = MaterialTheme.typography.titleMedium)
                                        MutedText(option.packageName)
                                        if (mandatory) MutedText("Akses sistem wajib — selalu diizinkan")
                                    }
                                }
                                if (selected) TextButton(onClick = { onOpenApp(option.packageName) }) { Text("Buka ${option.label}") }
                            }
                        }
                    }
                }
                else -> PermissionPanel(blocker, scheduling, onAccessibility, onExactAlarm, onDialer) { controller.refresh(ReconciliationReason.MANUAL_DIAGNOSTIC) }
            }
            Spacer(Modifier.height(24.dp).navigationBarsPadding())
        }
    }
    confirmation?.let { row ->
        AlertDialog(onDismissRequest = { controller.complete(row.task.id, row.pending!!, false); confirmation = null },
            title = { Text("Konfirmasi selesai") },
            text = { Text("Apakah kamu yakin sudah menyelesaikan task ini?\n\n${row.task.title}\nHanya kejadian ini yang diselesaikan; jadwal berulang tetap berjalan.") },
            dismissButton = { TextButton(onClick = { controller.complete(row.task.id, row.pending!!, false); confirmation = null }) { Text("Belum") } },
            confirmButton = { TextButton(enabled = enabled, onClick = { controller.complete(row.task.id, row.pending!!, true); confirmation = null }) { Text("Ya, sudah selesai") } })
    }
    deleting?.let { row ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Hapus task?") },
            text = { Text("${row.task.title}\nTask, jadwal, dan kejadian tertundanya akan dihapus dari perangkat ini.") },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Batal") } },
            confirmButton = { TextButton(enabled = enabled, onClick = { controller.delete(row.task.id); deleting = null }) { Text("Hapus") } })
    }
}

/** A separate composition: no normal navigation/editor/picker survives entry into lock/loading. */
@Composable
private fun RestrictedScreen(
    state: TaskUiState, controller: TaskController, blocker: BlockerState, scheduling: SchedulingState,
    apps: List<AppOption>, onDialer: () -> Unit, onOpenApp: (String) -> Unit
) {
    var confirmation by remember { mutableStateOf<TaskState?>(null) }
    val pending = state.snapshot.tasks.values.filter { it.pending != null }
    val ready = state.loaded && !state.busy
    // Back dismisses a confirmation first; it cannot navigate into the normal app.
    // Home/Android safety recovery remain available. This is NOT an OS kiosk.
    BackHandler { confirmation = null }
    LaunchedEffect(state.snapshot, state.loaded) {
        confirmation?.let { row ->
            if (!state.loaded || state.snapshot.tasks[row.task.id]?.pending != row.pending) confirmation = null
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
        .verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Tuntas", style = MaterialTheme.typography.titleLarge)
        SectionTitle(if (state.loaded) "Waktunya fokus." else "Memeriksa data lokal…",
            if (state.loaded) "Selesaikan task tertunda untuk kembali ke pengaturan." else "Task dan pengaturan belum dapat diubah sampai data terverifikasi.")
        // Emergency access is before the potentially long task list, also during hydration/errors.
        OutlinedButton(onClick = onDialer, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Buka telepon (tanpa menelepon)") }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Notice(it, warning = true) }
        if (state.error != null || !state.loaded) {
            OutlinedButton(onClick = { controller.refresh(ReconciliationReason.MANUAL_DIAGNOSTIC) }, enabled = !state.busy) { Text("Muat ulang / coba rekonsiliasi") }
        }
        if (state.loaded) {
            Notice("${pending.size} task tertunda • perubahan task dan daftar aplikasi dikunci.")
            pending.forEach { row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(row.task.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        MutedText("Tertunda sejak ${row.pending!!.localStart}")
                        MutedText(recurrenceLabel(row.task.recurrence))
                        Button(onClick = { confirmation = row }, enabled = ready, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Tandai selesai…") }
                    }
                }
            }
            Text("Aplikasi yang boleh dibuka", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            MutedText("Daftar hanya-baca. Pilihan dapat diubah setelah semua task tertunda selesai.")
            val choices = lockAppOptions(apps, state.snapshot.allowlist)
            if (choices.isEmpty()) MutedText("Belum ada aplikasi pilihan. Telepon tetap tersedia di bawah.")
            choices.forEach { choice ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(choice.app.label, style = MaterialTheme.typography.titleMedium)
                        MutedText(choice.app.packageName)
                        if (choice.launchable) OutlinedButton(onClick = { onOpenApp(choice.app.packageName) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Buka ${choice.app.label}") }
                        else MutedText("Tidak ditemukan aplikasi yang dapat dibuka.")
                    }
                }
            }
            if (!blocker.isServiceReady) Notice("Layanan pembatasan belum siap. Mode fokus dalam aplikasi tetap aktif; pembatasan aplikasi lain bersifat best-effort.", true)
            if (!scheduling.exactAlarmPermissionGranted || scheduling.isDegraded) Notice("Alarm tepat tidak tersedia: pengingat dapat terlambat.", true)
        }
        MutedText("Telepon/darurat, keyboard dan akses sistem wajib tetap diizinkan. Tuntas bukan pengunci sistem.")
        MutedText("Pemulihan aman tetap melalui Setelan Android → Aksesibilitas → Tuntas. Jangan hapus data bila penyimpanan bermasalah.")
    }
    confirmation?.takeIf { row -> state.loaded && state.snapshot.tasks[row.task.id]?.pending == row.pending }?.let { row ->
        AlertDialog(onDismissRequest = { controller.complete(row.task.id, row.pending!!, false); confirmation = null },
            title = { Text("Konfirmasi selesai") },
            text = { Text("Apakah kamu yakin sudah menyelesaikan task ini?\n\n${row.task.title}\nHanya kejadian ini yang diselesaikan; jadwal berulang tetap berjalan.") },
            dismissButton = { TextButton(onClick = { controller.complete(row.task.id, row.pending!!, false); confirmation = null }) { Text("Belum") } },
            confirmButton = { TextButton(enabled = ready, onClick = { controller.complete(row.task.id, row.pending!!, true); confirmation = null }) { Text("Ya, sudah selesai") } })
    }
}

/** Local vector-like glyphs, no downloaded icon/font dependency. Labels provided by NavigationBarItem. */
@Composable
private fun NavigationGlyph(tab: String) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp).clearAndSetSemantics { }) {
        val unit = size.width / 24f
        val stroke = Stroke(1.8f * unit)
        when (tab) {
            "Task" -> {
                drawRoundRect(color, Offset(4 * unit, 3 * unit), Size(16 * unit, 18 * unit), CornerRadius(3 * unit), style = stroke)
                for (y in listOf(8, 12, 16)) drawLine(color, Offset(8 * unit, y * unit), Offset(16 * unit, y * unit), 1.8f * unit)
            }
            "Fokus" -> {
                drawCircle(color, 9 * unit, style = stroke)
                drawCircle(color, 4 * unit, style = stroke)
                drawCircle(color, 1.3f * unit)
            }
            "Darurat" -> {
                val path = Path().apply { moveTo(12 * unit, 2 * unit); lineTo(21 * unit, 6 * unit); lineTo(19 * unit, 16 * unit); lineTo(12 * unit, 22 * unit); lineTo(5 * unit, 16 * unit); lineTo(3 * unit, 6 * unit); close() }
                drawPath(path, color, style = stroke)
                drawLine(color, Offset(12 * unit, 7 * unit), Offset(12 * unit, 16 * unit), 1.8f * unit)
                drawLine(color, Offset(8 * unit, 11.5f * unit), Offset(16 * unit, 11.5f * unit), 1.8f * unit)
            }
            else -> {
                drawCircle(color, 9 * unit, style = stroke)
                drawLine(color, Offset(12 * unit, 11 * unit), Offset(12 * unit, 17 * unit), 1.8f * unit)
                drawCircle(color, 1.1f * unit, Offset(12 * unit, 7 * unit))
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MutedText(text: String) { Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

@Composable
private fun Notice(text: String, warning: Boolean = false) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        color = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant) {
        Text(text, Modifier.padding(16.dp).semantics { if (warning) liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EmptyState(title: String, message: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TaskCard(row: TaskState, enabled: Boolean, onEdit: () -> Unit, onDelete: () -> Unit, onComplete: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = MaterialTheme.shapes.small,
                color = if (row.pending != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (row.pending != null) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) {
                Text(taskStatusLabel(row), Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
            }
            Text(row.task.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text("${row.task.startTime.format(DateTimeFormatter.ofPattern("HH:mm"))}  •  ${row.task.firstDate}", style = MaterialTheme.typography.bodyLarge)
            MutedText(recurrenceLabel(row.task.recurrence))
            if ((row.task.leadMinutes ?: 0) > 0) MutedText("Kunci ${row.task.leadMinutes} menit sebelum mulai")
            else MutedText("Kunci saat jam mulai")
            if (row.pending != null) {
                MutedText("Tertunda sejak ${row.pending.localStart.toLocalDate()} ${row.pending.localStart.toLocalTime()}")
                Button(onClick = onComplete, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Tandai selesai…") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onEdit, enabled = enabled) { Text("Ubah") }
                TextButton(onClick = onDelete, enabled = enabled) { Text("Hapus…") }
            }
        }
    }
}

internal fun taskStatusLabel(row: TaskState): String = when {
    row.pending != null -> "Tertunda"
    row.task.recurrence == Recurrence.Once && row.completedThrough != null -> "Selesai"
    else -> "Terjadwal"
}

internal fun recurrenceLabel(rule: Recurrence): String = when (rule) {
    Recurrence.Once -> "Sekali"
    Recurrence.Daily -> "Setiap hari"
    is Recurrence.Weekly -> rule.days.sortedBy { it.value }.joinToString(", ") { dayLabels[it.value - 1] }
    is Recurrence.Monthly -> "Tanggal ${rule.day} setiap bulan (bulan tanpa tanggal tersebut dilewati)"
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TaskEditor(id: String, task: Task?, enabled: Boolean, saveVersion: Long, onSave: (Task) -> Unit, onBack: () -> Unit) {
    var draft by rememberSaveable(id, stateSaver = draftSaver) { mutableStateOf(task?.let(TaskDraft::from) ?: TaskDraft()) }
    var error by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var waitingAt by rememberSaveable(id) { mutableStateOf<Long?>(null) }
    var dateOpen by rememberSaveable(id) { mutableStateOf(false) }
    var timeOpen by rememberSaveable(id) { mutableStateOf(false) }
    LaunchedEffect(saveVersion) { if (waitingAt != null && saveVersion > waitingAt!!) onBack() }
    SectionTitle(if (task == null) "Apa yang ingin dituntaskan?" else "Atur ulang task", "Pilih waktu. Beri perhatian penuh.")
    OutlinedTextField(draft.title, { draft = draft.copy(title = it) }, label = { Text("Judul / target (wajib)") }, modifier = Modifier.fillMaxWidth(), enabled = enabled)
    OutlinedTextField(draft.date, {}, readOnly = true, label = { Text("Tanggal pertama") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = enabled,
        trailingIcon = { TextButton(onClick = { dateOpen = true }, enabled = enabled) { Text("Pilih") } })
    OutlinedTextField(draft.time, {}, readOnly = true, label = { Text("Jam mulai (wajib)") }, placeholder = { Text("Pilih jam") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = enabled,
        trailingIcon = { TextButton(onClick = { timeOpen = true }, enabled = enabled) { Text("Pilih") } })
    OutlinedTextField(draft.lead, { draft = draft.copy(lead = it) }, label = { Text("Kunci lebih awal (menit)") }, supportingText = { Text("Opsional • kosong berarti saat jam mulai") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    Text("Pengulangan", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RepeatKind.entries.forEach { kind ->
            FilterChip(draft.kind == kind, { draft = draft.copy(kind = kind) }, enabled = enabled, label = { Text(kind.label) }, modifier = Modifier.heightIn(min = 48.dp))
        }
    }
    if (draft.kind == RepeatKind.WEEKLY) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DayOfWeek.entries.forEach { day ->
            FilterChip(day in draft.weekdays, { draft = draft.copy(weekdays = if (day in draft.weekdays) draft.weekdays - day else draft.weekdays + day) },
                enabled = enabled, label = { Text(dayLabels[day.value - 1].take(3)) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = dayLabels[day.value - 1] })
        }
    }
    if (draft.kind == RepeatKind.MONTHLY) {
        OutlinedTextField(draft.monthDay, { draft = draft.copy(monthDay = it) }, label = { Text("Tanggal setiap bulan (1–31)") }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        MutedText("Bulan tanpa tanggal tersebut dilewati, bukan dipindah ke akhir bulan.")
    }
    MutedText("Jam mengikuti zona waktu perangkat. Tanggal lampau/lead yang sudah lewat dapat langsung menjadi tertunda. Mengubah jadwal tidak menghapus kejadian tertunda.")
    error?.let { Notice(it, warning = true) }
    Button(enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), onClick = {
        val result = draft.validate(id)
        error = result.error
        result.task?.let { waitingAt = saveVersion; onSave(it) }
    }) { Text("Simpan task") }
    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Batal / kembali") }
    if (dateOpen) {
        val initial = runCatching { LocalDate.parse(draft.date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
        val picker = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(onDismissRequest = { dateOpen = false },
            confirmButton = { TextButton(onClick = { picker.selectedDateMillis?.let { draft = draft.copy(date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }; dateOpen = false }, enabled = picker.selectedDateMillis != null) { Text("Pilih tanggal") } },
            dismissButton = { TextButton(onClick = { dateOpen = false }) { Text("Batal") } }) {
            DatePicker(picker, title = { Text("Tanggal pertama", Modifier.padding(24.dp)) })
        }
    }
    if (timeOpen) {
        val initial = runCatching { LocalTime.parse(draft.time) }.getOrDefault(LocalTime.of(9, 0))
        val picker = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
        var keyboard by rememberSaveable { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { timeOpen = false }, title = { Text("Jam mulai") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                if (keyboard) TimeInput(picker) else TimePicker(picker)
                TextButton(onClick = { keyboard = !keyboard }) { Text(if (keyboard) "Gunakan jam" else "Ketik jam") }
            } },
            confirmButton = { TextButton(onClick = { draft = draft.copy(time = LocalTime.of(picker.hour, picker.minute).format(DateTimeFormatter.ofPattern("HH:mm"))); timeOpen = false }) { Text("Pilih jam") } },
            dismissButton = { TextButton(onClick = { timeOpen = false }) { Text("Batal") } })
    }
}

@Composable
private fun PermissionPanel(blocker: BlockerState, schedule: SchedulingState, accessibility: () -> Unit, exact: () -> Unit, dialer: () -> Unit, refresh: () -> Unit) {
    var details by rememberSaveable { mutableStateOf(false) }
    SectionTitle("Siap untuk fokus?", "Izin yang jelas. Kendali tetap di tanganmu.")
    Notice(if (blocker.isServiceReady && schedule.exactAlarmPermissionGranted && !schedule.isDegraded) "Layanan tersambung • alarm tepat tersedia. Tetap best-effort, bukan jaminan proteksi." else "Kesiapan belum lengkap. Periksa status layanan dan alarm di bawah.", !blocker.isServiceReady || !schedule.exactAlarmPermissionGranted || schedule.isDegraded)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("01  Aksesibilitas", style = MaterialTheme.typography.titleMedium)
            Text("Izin ${if (blocker.accessibilityServiceEnabled) "aktif" else "tidak aktif"} • layanan ${if (blocker.serviceConnectedInProcess) "tersambung" else "terputus"}")
            if (!blocker.isServiceReady) Text("Pembatasan belum siap. Izin aktif saja tidak berarti layanan tersambung.", color = MaterialTheme.colorScheme.error)
            Text("Dengan persetujuanmu, layanan melihat nama paket/jendela aplikasi untuk mengarahkan aplikasi terlarang kembali ke Tuntas. Tidak mengambil isi layar, teks, sandi, atau notifikasi. Aktifkan sendiri hanya jika kamu setuju.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = accessibility, modifier = Modifier.fillMaxWidth()) { Text("Pengaturan aksesibilitas") }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("02  Alarm & pengingat", style = MaterialTheme.typography.titleMedium)
            Text(if (schedule.exactAlarmPermissionGranted && !schedule.isDegraded) "Alarm tepat tersedia" else "Tidak tersedia / fallback tidak tepat", color = if (schedule.isDegraded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            MutedText("Zona: ${schedule.currentZoneId}")
            Text("Digunakan untuk waktu kunci. Tanpa izin, waktu dapat terlambat. Doze, hemat baterai dan pembatasan OEM tetap dapat menunda eksekusi, bahkan dengan izin.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = exact, modifier = Modifier.fillMaxWidth()) { Text("Izin alarm & pengingat") }
        }
    }
    OutlinedButton(onClick = refresh, modifier = Modifier.fillMaxWidth()) { Text("Segarkan status & jadwal") }
    TextButton(onClick = { details = !details }, modifier = Modifier.fillMaxWidth().semantics { stateDescription = if (details) "Terbuka" else "Tertutup" }) { Text(if (details) "Tutup penjelasan & pemulihan −" else "Penjelasan, privasi & pemulihan +") }
    if (details) {
        Notice("Tuntas bekerja secara jujur dan best-effort. Tidak kebal bypass; tidak mengunci sistem, tidak menggunakan root/Device Owner. Semua data disimpan lokal, tanpa akun atau jaringan.")
        MutedText("Pada Android 13+, APK sideload mungkin memerlukan ‘Izinkan setelan terbatas’ di info aplikasi.")
        MutedText("Setelah restart, buka kunci perangkat dahulu. Force-stop menghentikan alarm sampai aplikasi dibuka kembali. Uji layar mati, reboot, pencabutan izin dan akses darurat di perangkatmu sebelum mengandalkan aplikasi ini.")
        MutedText("Pemulihan aman: buka Setelan Android → Aksesibilitas → Tuntas → nonaktifkan layanan; lalu hapus aplikasi lewat info aplikasi. Tuntas tidak mencegah ini. Uninstall/hapus data menghilangkan task lokal. Jika muncul kesalahan penyimpanan/skema, jangan hapus data: simpan evidence dan minta bantuan. Belum ada migrasi dari versi skema lain.")
    }
    TextButton(onClick = dialer, modifier = Modifier.fillMaxWidth()) { Text("Buka telepon — tanpa panggilan uji") }
}
