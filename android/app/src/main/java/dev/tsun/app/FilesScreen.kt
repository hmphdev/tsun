package dev.tsun.app

import android.graphics.Bitmap
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Locale

private const val editLimit = 131_072
private val images = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
private val htmlExtensions = setOf("html", "htm")

@Composable
internal fun FilesScreen(
    api: DaemonApi, scope: FileScope, title: String, branch: String?, handlesBack: Boolean,
    onDirtyChange: (Boolean) -> Unit, onBusyChange: (Boolean) -> Unit, onHome: () -> Unit,
) {
    var directory by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val file = selected
    if (file != null) {
        key(file) {
            FilePage(api, scope, file, handlesBack, onDirtyChange, onBusyChange) {
                onDirtyChange(false)
                selected = null
            }
        }
    } else {
        fun goUp() {
            if (directory.isEmpty()) onHome() else directory = directory.substringBeforeLast('/', "")
        }
        BackHandler(enabled = handlesBack, onBack = ::goUp)
        key(directory) {
            DirectoryPage(api, scope, title, branch, directory, ::goUp) { entry ->
                val path = if (directory.isEmpty()) entry.name else "$directory/${entry.name}"
                if (entry.directory) directory = path else selected = path
            }
        }
    }
}

@Composable
private fun DirectoryPage(
    api: DaemonApi, scope: FileScope, title: String, branch: String?, path: String,
    up: () -> Unit, select: (Entry) -> Unit,
) {
    val context = LocalContext.current
    var listing by remember { mutableStateOf<Listing?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(api, scope, path, reload) {
        refreshing = true
        error = null
        try { listing = api.entries(scope, path) }
        catch (e: Exception) { error = e.userMessage() }
        finally { refreshing = false }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 21.dp)) {
        Eyebrow(if (scope.run != null) "TASK WORKTREE" else "PROJECT CHECKOUT",
            Modifier.padding(top = 23.dp), color = inks.pine)
        Text(title, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.headlineMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        branch?.let {
            Text(it, Modifier.padding(top = 4.dp), style = CodeType, color = inks.quiet,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton(Glyph.Back, if (path.isEmpty()) "Back to runs" else "Parent folder", onClick = up)
            Text(path.ifEmpty { if (scope.run != null) "Worktree root" else "Project root" }, Modifier.weight(1f),
                style = if (path.isEmpty()) MaterialTheme.typography.bodyMedium else CodeType,
                color = inks.quiet, maxLines = 1, overflow = TextOverflow.Ellipsis)
            GlyphButton(Glyph.Refresh, "Refresh folder", enabled = !refreshing) { reload++ }
        }
        HorizontalDivider(color = inks.rule)
        val current = listing
        if (error != null) {
            Notice(error.orEmpty(), Modifier.fillMaxWidth().padding(top = 12.dp), error = true)
        }
        when {
            current == null && refreshing -> LoadingLine("Reading folder…")
            current == null -> Unit
            current.entries.isEmpty() -> EmptyNote("Nothing to open", "This folder has no visible files.")
            else -> LazyColumn(Modifier.weight(1f), state = rememberLazyListState(), contentPadding = PaddingValues(bottom = 14.dp)) {
                items(current.entries, key = { it.name }) { entry ->
                    val extension = entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    val icon = when {
                        entry.directory -> Glyph.Folder
                        extension in images -> Glyph.Image
                        else -> Glyph.File
                    }
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { select(entry) }
                        .heightIn(min = 54.dp).padding(horizontal = 2.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                        LineIcon(icon, color = if (entry.directory) inks.pine else inks.quiet, size = 19)
                        Text(entry.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (!entry.directory) {
                            Text(Formatter.formatShortFileSize(context, entry.size), color = inks.quiet,
                                style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                        LineIcon(Glyph.Next, size = 15)
                    }
                    HorizontalDivider(color = inks.rule)
                }
                if (current.truncated) item {
                    Notice("Only the first 2,000 entries are shown.", Modifier.fillMaxWidth().padding(top = 9.dp))
                }
            }
        }
    }
}

private enum class FileAction { Save, Export }
private data class FileMessage(val text: String, val error: Boolean = false)

@Composable
private fun FilePage(
    api: DaemonApi, fileScope: FileScope, path: String, handlesBack: Boolean,
    onDirtyChange: (Boolean) -> Unit, onBusyChange: (Boolean) -> Unit, close: () -> Unit,
) {
    val name = path.substringAfterLast('/')
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val isImage = extension in images
    val isHtml = extension in htmlExtensions
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    var document by remember { mutableStateOf<Document?>(null) }
    var draft by remember { mutableStateOf("") }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var preview by rememberSaveable { mutableStateOf(isHtml) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    var action by remember { mutableStateOf<FileAction?>(null) }
    var message by remember { mutableStateOf<FileMessage?>(null) }
    var discardTo by remember { mutableStateOf<(() -> Unit)?>(null) }
    val original = document
    val dirty = original != null && draft != original.text
    val editable = original != null && original.text.length <= editLimit

    fun actionChanged(next: FileAction?) {
        action = next
        onBusyChange(next != null)
    }

    fun guard(next: () -> Unit) {
        if (action != null) return
        if (dirty) discardTo = next else next()
    }

    val mime = remember(extension) {
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        if (uri == null) actionChanged(null) else {
            actionChanged(FileAction.Export)
            scope.launch {
                try {
                    api.export(fileScope, path) {
                        resolver.openOutputStream(uri, "w") ?: throw IOException("Couldn't open the save destination.")
                    }
                    message = FileMessage("Exported $name")
                } catch (e: Exception) {
                    message = FileMessage(e.userMessage() + " The exported copy may be incomplete.", error = true)
                } finally { actionChanged(null) }
            }
        }
    }

    fun save() {
        val before = document ?: return
        if (action != null || !dirty || !editable) return
        val edited = draft
        actionChanged(FileAction.Save)
        message = null
        scope.launch {
            try {
                val revision = api.save(fileScope, path, edited, before.revision)
                document = Document(edited, revision)
                onDirtyChange(false)
                message = FileMessage("Saved in Debian")
            } catch (e: Exception) { message = FileMessage(e.userMessage(), error = true) }
            finally { actionChanged(null) }
        }
    }

    LaunchedEffect(api, fileScope, path, reload) {
        loading = true
        loadError = null
        message = null
        document = null
        bitmap = null
        try {
            if (isImage) bitmap = decodeBitmap(api.image(fileScope, path))
            else {
                val loaded = api.document(fileScope, path)
                draft = loaded.text
                document = loaded
                onDirtyChange(false)
            }
        } catch (e: Exception) { loadError = e.userMessage() }
        finally { loading = false }
    }

    BackHandler(enabled = handlesBack) { guard(close) }
    discardTo?.let { next ->
        AlertDialog(
            onDismissRequest = { discardTo = null },
            title = { Text("Discard unsaved edits?") },
            text = { Text("The file in Debian will keep its last saved version.") },
            confirmButton = { TextButton({ discardTo = null; onDirtyChange(false); next() }) { Text("Discard") } },
            dismissButton = { TextButton({ discardTo = null }) { Text("Keep editing") } },
        )
    }

    Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 13.dp, bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton(Glyph.Back, "Back to folder", enabled = action == null) { guard(close) }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Eyebrow(if (isImage) "IMAGE" else if (isHtml) "HTML DOCUMENT" else "FILE", color = inks.pine)
                Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            GlyphButton(Glyph.Export, if (dirty) "Export last saved version" else "Export file", enabled = action == null) {
                message = null
                actionChanged(FileAction.Export)
                try { picker.launch(name) }
                catch (e: Exception) {
                    actionChanged(null)
                    message = FileMessage(e.userMessage(), error = true)
                }
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 7.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val subtitle = when {
                action == FileAction.Export -> "Exporting…"
                dirty -> "Unsaved edits"
                isImage && bitmap != null -> "Pinch to zoom"
                isHtml && preview -> "Offline preview"
                original != null && !editable -> "Read only"
                original != null -> "UTF-8 text"
                else -> ""
            }
            Text(subtitle, Modifier.weight(1f), color = if (dirty) inks.rust else inks.quiet,
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (isHtml && original != null && editable) {
                TextButton(onClick = { preview = !preview }, enabled = action == null) {
                    Text(if (preview) "Edit" else "Preview")
                }
            }
            if (editable && (dirty || !preview)) {
                Button(onClick = ::save, enabled = dirty && action == null, shape = Corner,
                    contentPadding = PaddingValues(horizontal = 13.dp, vertical = 5.dp)) {
                    Text(if (action == FileAction.Save) "Saving…" else "Save")
                }
            }
            GlyphButton(Glyph.Refresh, "Reopen from Debian", enabled = !loading && action == null) {
                guard { reload++ }
            }
        }
        message?.let { Notice(it.text, Modifier.fillMaxWidth().padding(bottom = 8.dp), error = it.error) }
        val failed = loadError
        val image = bitmap
        when {
            loading -> LoadingLine("Opening file…")
            failed != null -> Notice(failed, Modifier.fillMaxWidth().padding(top = 9.dp), error = true)
            image != null -> RasterPreview(image, name, Modifier.weight(1f).fillMaxWidth())
            original != null && isHtml && preview -> HtmlPreview(draft, Modifier.weight(1f).fillMaxWidth())
            original != null && editable -> Surface(
                Modifier.weight(1f).fillMaxWidth(), shape = Corner, color = inks.sheet,
                border = BorderStroke(1.dp, inks.rule),
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = { edited ->
                        if (edited.length <= editLimit) {
                            draft = edited
                            onDirtyChange(edited != original.text)
                            message = null
                        } else message = FileMessage("This editor supports up to 128 Ki characters.", error = true)
                    },
                    enabled = action == null,
                    modifier = Modifier.fillMaxSize(), textStyle = CodeType.copy(color = inks.ink),
                    cursorBrush = SolidColor(inks.pine),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    decorationBox = { content ->
                        Box(Modifier.fillMaxSize().padding(12.dp)) {
                            if (draft.isEmpty()) Text("Empty file", color = inks.quiet, style = CodeType)
                            content()
                        }
                    },
                )
            }
            else -> EmptyNote("Too large to edit", "You can still export this file from Debian.")
        }
    }
}

@Composable
private fun LoadingLine(label: String) {
    Row(Modifier.padding(horizontal = 5.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = inks.quiet)
    }
}
