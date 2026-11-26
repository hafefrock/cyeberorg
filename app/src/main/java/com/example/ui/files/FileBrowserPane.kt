package com.example.ui.files

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import com.example.R
import com.example.settings.DestructiveConfirmDialog
import com.example.termux.DistroPaths
import com.example.termux.TermuxPaths
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StudioSpacing
import com.example.ui.theme.StudioTheme
import java.io.File

private data class FileLocation(val labelRes: Int, val dir: File)

/** Files tab: browse the Termux home, the Ubuntu home and the phone's storage; commands go to the terminal. */
@Composable
fun FileBrowserPane(
    onTerminalCommand: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val paths = remember { TermuxPaths(context.filesDir) }

    val locations = remember {
        buildList {
            add(FileLocation(R.string.files_loc_home, paths.home))
            if (DistroPaths.isInstalled(paths.prefix, DistroPaths.UBUNTU)) {
                add(FileLocation(R.string.files_loc_ubuntu, File(DistroPaths.rootfs(paths.prefix, DistroPaths.UBUNTU), "root")))
            }
            val phone = File(DistroPaths.ANDROID_STORAGE)
            if (phone.canRead()) add(FileLocation(R.string.files_loc_phone, phone))
        }
    }
    var locIndex by rememberSaveable { mutableStateOf(0) }
    val location = locations.getOrElse(locIndex) { locations.first() }
    var currentPath by rememberSaveable { mutableStateOf<String?>(null) }
    val dir = File(currentPath?.takeIf { it.startsWith(location.dir.path) } ?: location.dir.path)
    var refresh by remember { mutableIntStateOf(0) }
    val entries = remember(dir.path, refresh) { FileBrowserModel.list(dir) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<FileEntry?>(null) }

    val parent = FileBrowserModel.parentWithin(dir, location.dir)
    BackHandler(enabled = parent != null) { currentPath = parent?.path }

    Column(modifier = modifier.fillMaxSize().testTag("file_browser")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = StudioSpacing.lg, vertical = StudioSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(StudioSpacing.sm)
        ) {
            locations.forEachIndexed { index, loc ->
                val selected = index == locIndex
                Box(
                    modifier = Modifier
                        .background(if (selected) tokens.accentMuted else tokens.layer1, tokens.smallShape)
                        .border(
                            tokens.borderWidth,
                            if (selected) tokens.accent.copy(alpha = 0.6f) else tokens.textMuted.copy(alpha = 0.3f),
                            tokens.smallShape
                        )
                        .clickable(role = Role.Tab) {
                            locIndex = index
                            currentPath = null
                        }
                        .padding(horizontal = StudioSpacing.md, vertical = StudioSpacing.sm)
                        .testTag("files_loc_$index")
                ) {
                    Text(
                        text = stringResource(loc.labelRes),
                        style = compactType.tabOrChip,
                        color = if (selected) tokens.accent else tokens.textSecondary,
                        maxLines = 1
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = StudioSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StudioSpacing.sm)
        ) {
            Icon(
                imageVector = Icons.Default.ArrowUpward,
                contentDescription = stringResource(R.string.files_up),
                tint = if (parent != null) tokens.accent else tokens.textMuted,
                modifier = Modifier
                    .clickable(enabled = parent != null, role = Role.Button) { currentPath = parent?.path }
                    .padding(StudioSpacing.xs)
                    .size(dimens.iconSize)
                    .testTag("files_up")
            )
            Text(
                text = dir.path,
                style = compactType.monoPreview,
                color = tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.files_cd_here),
                style = compactType.tabOrChip,
                color = tokens.accent,
                maxLines = 1,
                modifier = Modifier
                    .clickable(role = Role.Button) { onTerminalCommand(FileBrowserModel.cdCommand(dir.path)) }
                    .padding(StudioSpacing.sm)
                    .testTag("files_cd_here")
            )
        }

        if (entries == null) {
            Text(
                text = stringResource(R.string.files_unreadable),
                style = compactType.body,
                color = tokens.textSecondary,
                modifier = Modifier.padding(StudioSpacing.lg)
            )
        } else if (entries.isEmpty()) {
            Text(
                text = stringResource(R.string.files_empty),
                style = compactType.body,
                color = tokens.textSecondary,
                modifier = Modifier.padding(StudioSpacing.lg)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = StudioSpacing.lg, vertical = StudioSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(StudioSpacing.xs)
            ) {
                items(entries, key = { it.path }) { entry ->
                    Box {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(tokens.layer1, tokens.smallShape)
                                .border(BorderStroke(tokens.borderWidth, tokens.thinBorderBrush), tokens.smallShape)
                                .clickable(role = Role.Button) {
                                    if (entry.isDir) currentPath = entry.path else menuFor = entry.path
                                }
                                .padding(horizontal = StudioSpacing.md, vertical = StudioSpacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(StudioSpacing.md)
                        ) {
                            Icon(
                                imageVector = if (entry.isDir) Icons.Default.Folder else Icons.Default.Description,
                                contentDescription = null,
                                tint = if (entry.isDir) tokens.accent else tokens.textSecondary,
                                modifier = Modifier.size(dimens.iconSize)
                            )
                            Text(
                                text = entry.name,
                                style = compactType.monoPreview,
                                fontFamily = JetBrainsMonoFamily,
                                color = tokens.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (!entry.isDir) {
                                Text(
                                    text = FileBrowserModel.formatSize(entry.size),
                                    style = compactType.navLabel,
                                    color = tokens.textMuted,
                                    maxLines = 1
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = null,
                                tint = tokens.textSecondary,
                                modifier = Modifier
                                    .clickable(role = Role.Button) { menuFor = entry.path }
                                    .padding(StudioSpacing.xs)
                                    .size(dimens.iconSize)
                            )
                        }
                        DropdownMenu(
                            expanded = menuFor == entry.path,
                            onDismissRequest = { menuFor = null }
                        ) {
                            if (entry.isDir) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.files_cd_here)) },
                                    onClick = {
                                        menuFor = null
                                        onTerminalCommand(FileBrowserModel.cdCommand(entry.path))
                                    }
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.files_action_view)) },
                                    onClick = {
                                        menuFor = null
                                        onTerminalCommand(FileBrowserModel.viewCommand(entry.path))
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.files_action_copy_path)) },
                                onClick = {
                                    menuFor = null
                                    clipboard.setText(AnnotatedString(entry.path))
                                    Toast.makeText(context, R.string.files_path_copied, Toast.LENGTH_SHORT).show()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.files_action_delete), color = tokens.error) },
                                onClick = {
                                    menuFor = null
                                    pendingDelete = entry
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        DestructiveConfirmDialog(
            title = stringResource(R.string.files_delete_title),
            message = stringResource(R.string.files_delete_message, target.name),
            confirmLabel = stringResource(R.string.files_action_delete),
            onConfirm = {
                pendingDelete = null
                val ok = runCatching { File(target.path).deleteRecursively() }.getOrDefault(false)
                if (!ok) Toast.makeText(context, R.string.files_delete_failed, Toast.LENGTH_LONG).show()
                refresh++
            },
            onDismiss = { pendingDelete = null },
            dialogTestTag = "files_delete_dialog",
            confirmTestTag = "files_delete_confirm",
            cancelTestTag = "files_delete_cancel"
        )
    }
}
