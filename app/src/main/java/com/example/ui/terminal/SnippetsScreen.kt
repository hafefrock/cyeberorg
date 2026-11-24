package com.example.ui.terminal

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.ai.CommandRiskAssessment
import com.example.ai.CommandRiskClassifier
import com.example.ai.CommandRiskLevel
import com.example.ai.CommandRiskReason
import com.example.data.CommandSnippet
import com.example.data.SnippetImportException
import com.example.data.SnippetJsonCodec
import com.example.terminal.TerminalPasteSanitizer
import com.example.ui.ai.CommandRiskBadge
import com.example.ui.ai.HighRiskCommandConfirmDialog
import com.example.ui.theme.StudioSpacing
import com.example.ui.theme.StudioTheme
import com.example.ui.theme.safeContentPadding
import com.example.ui.theme.studioGlow
import com.example.ui.theme.studioTouchTarget

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnippetsScreen(
    snippets: List<CommandSnippet>,
    onInsertSnippet: (String) -> Unit,
    onCopySnippet: (String) -> Unit,
    onSaveSnippet: (title: String, command: String, tags: List<String>, existingId: String?) -> Unit,
    onDeleteSnippet: (String) -> Unit,
    onBuildExportJson: () -> String,
    onExportSuccess: (Int) -> Unit,
    onImportJson: (String) -> Unit,
    onIoError: (String) -> Unit,
    onExplainSnippet: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var searchQuery by remember { mutableStateOf("") }
    var isSearchOpen by remember { mutableStateOf(false) }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var headerMenuExpanded by remember { mutableStateOf(false) }
    var editorTarget by remember { mutableStateOf<SnippetEditorState?>(null) }
    var detailTarget by remember { mutableStateOf<CommandSnippet?>(null) }
    var deleteConfirmTarget by remember { mutableStateOf<CommandSnippet?>(null) }
    var highRiskConfirmTarget by remember { mutableStateOf<CommandSnippet?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val payload = onBuildExportJson()
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(payload.toByteArray(Charsets.UTF_8))
                stream.flush()
            } ?: error("Unable to open output stream")
        }.onSuccess {
            onExportSuccess(snippets.size)
        }.onFailure { err ->
            onIoError(err.message ?: "Write error")
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val readResult = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                SnippetJsonCodec.readUtf8Bounded(stream).getOrThrow()
            } ?: error("Unable to open input stream")
        }
        readResult.onSuccess { rawJson ->
            onImportJson(rawJson)
        }.onFailure { err ->
            val msg = if (err is SnippetImportException) {
                context.getString(err.messageRes, *err.formatArgs.toTypedArray())
            } else {
                err.message ?: "Read error"
            }
            onIoError(msg)
        }
    }

    val allTags = remember(snippets) {
        snippets.flatMap { it.tags }.distinct().sorted()
    }

    val filteredSnippets = remember(snippets, searchQuery, selectedTag) {
        val q = searchQuery.trim().lowercase()
        snippets.filter { item ->
            val tagMatch = selectedTag == null || item.tags.any { it.equals(selectedTag, ignoreCase = true) }
            val textMatch = q.isEmpty() ||
                item.title.lowercase().contains(q) ||
                item.command.lowercase().contains(q) ||
                item.tags.any { it.lowercase().contains(q) }
            tagMatch && textMatch
        }
    }

    val requestInsertSnippet: (CommandSnippet) -> Unit = { snippet ->
        val risk = CommandRiskClassifier.classify(snippet.command)
        val needsConfirmation = risk.level == CommandRiskLevel.HIGH ||
            TerminalPasteSanitizer.containsControlOrNewline(snippet.command)
        if (needsConfirmation) {
            highRiskConfirmTarget = snippet
        } else {
            onInsertSnippet(snippet.command)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .testTag("snippets_screen")
    ) {
        val isNarrowWidth = maxWidth < dimens.narrowWidthThreshold
        Column(modifier = Modifier.fillMaxSize()) {
            // App bar header with title, '+' new script button, search button, and overflow menu
            Surface(
                color = tokens.layer1,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = dimens.controlHeight)
                            .padding(
                                start = StudioSpacing.lg,
                                end = StudioSpacing.sm,
                                top = StudioSpacing.xs,
                                bottom = StudioSpacing.xs
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(StudioSpacing.xs)
                    ) {
                        Text(
                            text = tokens.formatHeading(stringResource(R.string.snippets_lib_title)),
                            style = compactType.screenTitle,
                            color = tokens.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("scriptbook_title")
                        )

                        val newScriptLabel = stringResource(R.string.snippets_new_btn)
                        val createNewAction = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            editorTarget = SnippetEditorState(isNew = true)
                        }
                        Box(
                            modifier = Modifier
                                .size(dimens.controlHeight)
                                .studioTouchTarget(
                                    minSize = dimens.minTouchTarget,
                                    onOutOfBoundsTap = createNewAction
                                )
                                .background(tokens.accentMuted, tokens.buttonShape)
                                .border(tokens.borderWidth, tokens.accent.copy(alpha = 0.45f), tokens.buttonShape)
                                .clickable(role = Role.Button, onClick = createNewAction)
                                .semantics {
                                    role = Role.Button
                                    contentDescription = newScriptLabel
                                }
                                .testTag("snippets_new_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = newScriptLabel,
                                tint = tokens.accent,
                                modifier = Modifier.size(dimens.iconSize)
                            )
                        }

                        val toggleSearchAction = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (isSearchOpen) {
                                searchQuery = ""
                                isSearchOpen = false
                            } else {
                                isSearchOpen = true
                            }
                        }
                        Box(
                            modifier = Modifier
                                .size(dimens.controlHeight)
                                .studioTouchTarget(
                                    minSize = dimens.minTouchTarget,
                                    onOutOfBoundsTap = toggleSearchAction
                                )
                                .clickable(role = Role.Button, onClick = toggleSearchAction)
                                .testTag("snippets_search_toggle"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isSearchOpen) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = stringResource(R.string.cd_search_scriptbook),
                                tint = if (isSearchOpen || searchQuery.isNotEmpty()) tokens.accent else tokens.textSecondary,
                                modifier = Modifier.size(dimens.iconSize)
                            )
                        }

                        Box {
                            val openMenuAction = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                headerMenuExpanded = true
                            }
                            Box(
                                modifier = Modifier
                                    .size(dimens.controlHeight)
                                    .studioTouchTarget(
                                        minSize = dimens.minTouchTarget,
                                        onOutOfBoundsTap = openMenuAction
                                    )
                                    .clickable(role = Role.Button, onClick = openMenuAction)
                                    .testTag("snippets_overflow_menu"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.cd_scriptbook_more),
                                    tint = tokens.textSecondary,
                                    modifier = Modifier.size(dimens.iconSize)
                                )
                            }

                            DropdownMenu(
                                expanded = headerMenuExpanded,
                                onDismissRequest = { headerMenuExpanded = false },
                                containerColor = tokens.floatingGlass,
                                shape = tokens.smallShape
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = stringResource(R.string.snippets_export_btn),
                                            style = compactType.body,
                                            color = tokens.textPrimary
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.FileUpload,
                                            contentDescription = null,
                                            tint = tokens.accent
                                        )
                                    },
                                    onClick = {
                                        headerMenuExpanded = false
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        exportLauncher.launch("cyberorg_scriptbook.json")
                                    },
                                    modifier = Modifier.testTag("snippets_export_button")
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = stringResource(R.string.snippets_import_btn),
                                            style = compactType.body,
                                            color = tokens.textPrimary
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.FileDownload,
                                            contentDescription = null,
                                            tint = tokens.info
                                        )
                                    },
                                    onClick = {
                                        headerMenuExpanded = false
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                                    },
                                    modifier = Modifier.testTag("snippets_import_button")
                                )
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = isSearchOpen || searchQuery.isNotEmpty(),
                        enter = expandVertically(tween(tokens.motionDurationMs)),
                        exit = shrinkVertically(tween(tokens.motionDurationMs))
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = compactType.body.copy(color = tokens.textPrimary),
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    tint = tokens.accent,
                                    modifier = Modifier.size(dimens.iconSize)
                                )
                            },
                            trailingIcon = if (searchQuery.isNotEmpty()) {
                                {
                                    Box(
                                        modifier = Modifier
                                            .size(dimens.controlHeight)
                                            .studioTouchTarget(
                                                minSize = dimens.minTouchTarget,
                                                onOutOfBoundsTap = { searchQuery = "" }
                                            )
                                            .clickable(role = Role.Button) { searchQuery = "" }
                                            .testTag("snippets_search_clear"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = stringResource(R.string.cd_clear_search),
                                            tint = tokens.textMuted,
                                            modifier = Modifier.size(dimens.iconSize)
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.snippets_search_hint),
                                    style = compactType.body,
                                    color = tokens.textMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = tokens.focusRingColor,
                                unfocusedBorderColor = tokens.borderStart,
                                focusedContainerColor = tokens.baseCanvas,
                                unfocusedContainerColor = tokens.baseCanvas
                            ),
                            shape = tokens.smallShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = StudioSpacing.lg,
                                    end = StudioSpacing.lg,
                                    bottom = StudioSpacing.sm
                                )
                                .testTag("snippets_search_input")
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(dimens.hairlineWidth)
                            .background(tokens.thinBorderBrush)
                    )
                }
            }

            // Single-row horizontal tag filter strip with contentPadding so first capsule is never edge-clipped
            if (allTags.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = dimens.minTouchTarget)
                        .testTag("scriptbook_tag_bar"),
                    contentPadding = PaddingValues(horizontal = StudioSpacing.lg, vertical = StudioSpacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(StudioSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    item(key = "tag_all") {
                        TagFilterPill(
                            label = stringResource(R.string.snippets_filter_all),
                            selected = selectedTag == null,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedTag = null
                            },
                            modifier = Modifier.testTag("tag_filter_all")
                        )
                    }
                    items(allTags, key = { "tag_$it" }) { tag ->
                        TagFilterPill(
                            label = "#$tag",
                            selected = selectedTag.equals(tag, ignoreCase = true),
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedTag = if (selectedTag.equals(tag, ignoreCase = true)) null else tag
                            },
                            modifier = Modifier.testTag("tag_filter_$tag")
                        )
                    }
                }
            }

            // Library content / distinct empty states
            when {
                snippets.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(StudioSpacing.lg),
                        contentAlignment = Alignment.Center
                    ) {
                        ScriptbookEmptyStateCard(
                            icon = Icons.Default.Code,
                            title = stringResource(R.string.snippets_empty_library_title),
                            description = stringResource(R.string.snippets_empty_library_desc),
                            actionLabel = stringResource(R.string.snippets_create_first_btn),
                            isPrimaryAction = true,
                            actionTestTag = "snippets_create_first_button",
                            cardTestTag = "snippets_empty_library",
                            onAction = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                editorTarget = SnippetEditorState(isNew = true)
                            }
                        )
                    }
                }

                filteredSnippets.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(StudioSpacing.lg),
                        contentAlignment = Alignment.Center
                    ) {
                        ScriptbookEmptyStateCard(
                            icon = Icons.Default.SearchOff,
                            title = stringResource(R.string.snippets_empty_search_title),
                            description = stringResource(R.string.snippets_empty_search_desc),
                            actionLabel = stringResource(R.string.snippets_clear_filters_btn),
                            isPrimaryAction = false,
                            actionTestTag = "snippets_clear_filters_button",
                            cardTestTag = "snippets_empty_search",
                            onAction = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                searchQuery = ""
                                selectedTag = null
                            }
                        )
                    }
                }

                else -> {
                    val listState = rememberLazyListState()
                    LaunchedEffect(selectedTag, searchQuery) {
                        listState.scrollToItem(0)
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .testTag("scriptbook_list"),
                        contentPadding = PaddingValues(
                            start = StudioSpacing.lg,
                            end = StudioSpacing.lg,
                            top = StudioSpacing.sm,
                            bottom = StudioSpacing.lg
                        ),
                        verticalArrangement = Arrangement.spacedBy(dimens.listSpacing)
                    ) {
                        items(filteredSnippets, key = { it.id }) { snippet ->
                            ScriptCard(
                                snippet = snippet,
                                isNarrowWidth = isNarrowWidth,
                                onOpenDetail = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    detailTarget = snippet
                                },
                                onInsert = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    requestInsertSnippet(snippet)
                                },
                                onCopy = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onCopySnippet(snippet.command)
                                },
                                onEdit = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    editorTarget = SnippetEditorState(
                                        existingId = snippet.id,
                                        title = snippet.title,
                                        command = snippet.command,
                                        tagsCsv = snippet.tags.joinToString(", "),
                                        isNew = false
                                    )
                                },
                                onDeleteRequest = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    deleteConfirmTarget = snippet
                                },
                                onExplain = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onExplainSnippet(snippet.command)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    detailTarget?.let { snippet ->
        ScriptDetailBottomSheet(
            snippet = snippet,
            onDismiss = { detailTarget = null },
            onInsert = {
                detailTarget = null
                requestInsertSnippet(snippet)
            },
            onCopy = {
                onCopySnippet(snippet.command)
            },
            onExplain = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onExplainSnippet(snippet.command)
            },
            onEdit = {
                detailTarget = null
                editorTarget = SnippetEditorState(
                    existingId = snippet.id,
                    title = snippet.title,
                    command = snippet.command,
                    tagsCsv = snippet.tags.joinToString(", "),
                    isNew = false
                )
            },
            onDeleteRequest = {
                deleteConfirmTarget = snippet
            }
        )
    }

    editorTarget?.let { target ->
        SnippetEditorDialog(
            initialState = target,
            dialogTitle = stringResource(
                if (target.isNew) R.string.snippets_dialog_create_title else R.string.snippets_dialog_edit_title
            ),
            onDismiss = { editorTarget = null },
            onConfirm = { title, command, tags ->
                onSaveSnippet(title, command, tags, target.existingId)
                editorTarget = null
            }
        )
    }

    deleteConfirmTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteConfirmTarget = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            containerColor = tokens.floatingGlass,
            shape = tokens.cardShape,
            title = {
                Text(
                    text = stringResource(R.string.snippets_delete_title),
                    style = compactType.cardTitle,
                    color = tokens.textPrimary
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.snippets_delete_desc, target.title),
                    style = compactType.body,
                    color = tokens.textSecondary
                )
            },
            confirmButton = {
                val confirmDeleteAction = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val deletedId = target.id
                    deleteConfirmTarget = null
                    if (detailTarget?.id == deletedId) {
                        detailTarget = null
                    }
                    onDeleteSnippet(deletedId)
                }
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    Button(
                        onClick = confirmDeleteAction,
                        shape = tokens.buttonShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tokens.error,
                            contentColor = tokens.onAccent
                        ),
                        contentPadding = safeContentPadding(tokens.buttonShape, dimens.cardPaddingH),
                        modifier = Modifier
                            .height(dimens.controlHeight)
                            .studioTouchTarget(
                                minSize = dimens.minTouchTarget,
                                onOutOfBoundsTap = confirmDeleteAction
                            )
                            .testTag("confirm_delete_snippet")
                    ) {
                        Text(
                            text = stringResource(R.string.action_delete),
                            style = compactType.tabOrChip
                        )
                    }
                }
            },
            dismissButton = {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    TextButton(
                        onClick = { deleteConfirmTarget = null },
                        contentPadding = PaddingValues(horizontal = dimens.cardPaddingH),
                        modifier = Modifier
                            .height(dimens.controlHeight)
                            .studioTouchTarget(
                                minSize = dimens.minTouchTarget,
                                onOutOfBoundsTap = { deleteConfirmTarget = null }
                            )
                            .testTag("cancel_delete_snippet")
                    ) {
                        Text(
                            text = stringResource(R.string.action_cancel),
                            style = compactType.tabOrChip,
                            color = tokens.textSecondary
                        )
                    }
                }
            },
            modifier = Modifier
                .padding(horizontal = StudioSpacing.lg)
                .widthIn(max = dimens.dialogMaxWidth)
                .testTag("snippet_delete_confirm_dialog")
        )
    }

    highRiskConfirmTarget?.let { snippet ->
        val risk = remember(snippet.command) {
            val classified = CommandRiskClassifier.classify(snippet.command)
            if (classified.level == CommandRiskLevel.HIGH) {
                classified
            } else {
                CommandRiskAssessment(
                    level = CommandRiskLevel.MEDIUM,
                    reason = CommandRiskReason.MULTILINE_OR_CONTROL_INSERT
                )
            }
        }
        HighRiskCommandConfirmDialog(
            command = snippet.command,
            risk = risk,
            onDismiss = { highRiskConfirmTarget = null },
            onConfirmInsert = {
                highRiskConfirmTarget = null
                onInsertSnippet(snippet.command)
            }
        )
    }
}

@Composable
private fun ScriptbookEmptyStateCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    actionLabel: String,
    isPrimaryAction: Boolean,
    actionTestTag: String,
    cardTestTag: String,
    onAction: () -> Unit
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography

    Surface(
        modifier = Modifier
            .widthIn(max = dimens.emptyCardMaxWidth)
            .fillMaxWidth()
            .testTag(cardTestTag),
        color = tokens.layer1,
        shape = tokens.cardShape,
        border = BorderStroke(tokens.borderWidth, tokens.thinBorderBrush)
    ) {
        Column(
            modifier = Modifier.padding(safeContentPadding(tokens.cardShape, StudioSpacing.lg, StudioSpacing.lg)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(dimens.listSpacing)
        ) {
            Box(
                modifier = Modifier
                    .size(dimens.emptyStateIconContainerSize)
                    .clip(CircleShape)
                    .background(if (isPrimaryAction) tokens.accentMuted else tokens.layer2),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isPrimaryAction) tokens.accent else tokens.textSecondary,
                    modifier = Modifier.size(dimens.navIconSize)
                )
            }
            Text(
                text = title,
                style = compactType.cardTitle,
                color = tokens.textPrimary,
                textAlign = TextAlign.Center
            )
            Text(
                text = description,
                style = compactType.body,
                color = tokens.textSecondary,
                textAlign = TextAlign.Center
            )
            if (isPrimaryAction) {
                Box(
                    modifier = Modifier.studioGlow(
                        color = tokens.glowColor,
                        radius = tokens.glowRadius,
                        shape = tokens.buttonShape
                    )
                ) {
                    Button(
                        onClick = onAction,
                        shape = tokens.buttonShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tokens.primaryButtonContainer,
                            contentColor = tokens.primaryButtonContent
                        ),
                        contentPadding = safeContentPadding(tokens.buttonShape, StudioSpacing.lg),
                        modifier = Modifier
                            .height(dimens.controlHeight)
                            .studioTouchTarget(
                                minSize = dimens.minTouchTarget,
                                onOutOfBoundsTap = onAction
                            )
                            .testTag(actionTestTag)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(dimens.iconSize)
                        )
                        Spacer(modifier = Modifier.width(StudioSpacing.xs))
                        Text(
                            text = actionLabel,
                            style = compactType.tabOrChip,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            } else {
                OutlinedButton(
                    onClick = onAction,
                    shape = tokens.buttonShape,
                    border = BorderStroke(tokens.borderWidth, tokens.thinBorderBrush),
                    contentPadding = PaddingValues(horizontal = StudioSpacing.lg),
                    modifier = Modifier
                        .height(dimens.controlHeight)
                        .studioTouchTarget(
                            minSize = dimens.minTouchTarget,
                            onOutOfBoundsTap = onAction
                        )
                        .testTag(actionTestTag)
                ) {
                    Text(
                        text = actionLabel,
                        style = compactType.tabOrChip,
                        color = tokens.textPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun ScriptCard(
    snippet: CommandSnippet,
    isNarrowWidth: Boolean,
    onOpenDetail: () -> Unit,
    onInsert: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDeleteRequest: () -> Unit,
    onExplain: () -> Unit
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val risk = remember(snippet.command) { CommandRiskClassifier.classify(snippet.command) }
    var cardMenuExpanded by remember(snippet.id) { mutableStateOf(false) }
    val cardCd = stringResource(R.string.cd_script_card, snippet.title)
    val insertLabel = stringResource(R.string.snippets_insert)

    val visibleTags = remember(snippet.tags) { snippet.tags.take(3) }
    val hiddenTagCount = (snippet.tags.size - 3).coerceAtLeast(0)
    val cardBorderBrush = if (risk.level == CommandRiskLevel.HIGH) {
        SolidColor(tokens.error.copy(alpha = 0.55f))
    } else {
        tokens.thinBorderBrush
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .studioGlow(
                color = tokens.glowColor,
                radius = tokens.glowRadius,
                shape = tokens.cardShape
            )
    ) {
        // Non-clipping container so child buttons (28.dp visual) preserve full >= 48.dp touch bounds
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(tokens.layer1, tokens.cardShape)
                .border(tokens.borderWidth, cardBorderBrush, tokens.cardShape)
                .clickable(
                    role = Role.Button,
                    onClick = onOpenDetail
                )
                .semantics {
                    role = Role.Button
                    contentDescription = cardCd
                }
                .testTag("snippet_card_${snippet.id}")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(safeContentPadding(tokens.cardShape, dimens.cardPaddingH, dimens.cardPaddingV)),
                horizontalArrangement = Arrangement.spacedBy(StudioSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left content column: title + compact risk badge, max 2-line mono preview, and tags
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(dimens.listSpacing)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(StudioSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = snippet.title,
                            style = compactType.cardTitle,
                            color = tokens.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("snippet_title_${snippet.id}")
                        )
                        CommandRiskBadge(
                            risk = risk,
                            modifier = Modifier.wrapContentWidth()
                        )
                    }

                    // Max 2-line mono command preview
                    Surface(
                        color = tokens.baseCanvas,
                        shape = tokens.smallShape,
                        border = BorderStroke(tokens.borderWidth, tokens.borderEnd),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = snippet.command,
                            style = compactType.monoPreview,
                            color = tokens.accent,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(safeContentPadding(tokens.smallShape, StudioSpacing.sm, StudioSpacing.xs))
                        )
                    }

                    // Compact tags row (max 3 pills + overflow "+N", height = chipHeight = 24.dp)
                    if (visibleTags.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(StudioSpacing.xs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            visibleTags.forEach { tag ->
                                Surface(
                                    color = tokens.layer2,
                                    shape = tokens.pillShape,
                                    border = BorderStroke(tokens.borderWidth, tokens.thinBorderBrush),
                                    modifier = Modifier.height(dimens.chipHeight)
                                ) {
                                    Box(
                                        modifier = Modifier.padding(safeContentPadding(tokens.pillShape, StudioSpacing.sm)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "#$tag",
                                            style = compactType.tabOrChip,
                                            color = tokens.textSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            if (hiddenTagCount > 0) {
                                Surface(
                                    color = tokens.layer2,
                                    shape = tokens.pillShape,
                                    border = BorderStroke(tokens.borderWidth, tokens.thinBorderBrush),
                                    modifier = Modifier
                                        .height(dimens.chipHeight)
                                        .semantics(mergeDescendants = true) {}
                                        .testTag("snippet_tag_overflow_${snippet.id}")
                                ) {
                                    Box(
                                        modifier = Modifier.padding(safeContentPadding(tokens.pillShape, StudioSpacing.sm)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "+$hiddenTagCount",
                                            style = compactType.tabOrChip,
                                            color = tokens.textMuted,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Right action column (wrap width, vertically centered on card): compact Insert + 3-dot overflow
                Row(
                    modifier = Modifier.wrapContentWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(StudioSpacing.xs)
                ) {
                    val insertModifier = if (isNarrowWidth) {
                        Modifier.size(dimens.controlHeightSmall)
                    } else {
                        Modifier.height(dimens.controlHeightSmall)
                    }
                    Box(
                        modifier = insertModifier
                            .studioTouchTarget(
                                minSize = dimens.minTouchTarget,
                                onOutOfBoundsTap = onInsert
                            )
                            .background(tokens.primaryButtonContainer, tokens.buttonShape)
                            .clickable(role = Role.Button, onClick = onInsert)
                            .semantics {
                                role = Role.Button
                                contentDescription = insertLabel
                            }
                            .then(
                                if (isNarrowWidth) {
                                    Modifier
                                } else {
                                    Modifier.padding(safeContentPadding(tokens.buttonShape, StudioSpacing.sm))
                                }
                            )
                            .testTag("snippet_insert_${snippet.id}"),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isNarrowWidth) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                contentDescription = null,
                                tint = tokens.primaryButtonContent,
                                modifier = Modifier.size(dimens.iconSizeSmall)
                            )
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(StudioSpacing.xs)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                    contentDescription = null,
                                    tint = tokens.primaryButtonContent,
                                    modifier = Modifier.size(dimens.iconSizeSmall)
                                )
                                Text(
                                    text = insertLabel,
                                    style = compactType.tabOrChip,
                                    color = tokens.primaryButtonContent,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    val explainDesc = stringResource(R.string.cd_explain_command)
                    Box(
                        modifier = Modifier
                            .studioTouchTarget(
                                minSize = dimens.minTouchTarget,
                                onOutOfBoundsTap = onExplain
                            )
                            .clickable(role = Role.Button, onClick = onExplain)
                            .semantics {
                                role = Role.Button
                                contentDescription = explainDesc
                            }
                            .testTag("snippet_explain_${snippet.id}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(dimens.controlHeightSmall)
                                .background(tokens.layer2, tokens.buttonShape)
                                .border(tokens.borderWidth, tokens.borderStart, tokens.buttonShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                                contentDescription = explainDesc,
                                tint = tokens.info,
                                modifier = Modifier.size(dimens.iconSizeSmall)
                            )
                        }
                    }

                    Box {
                        val openCardMenu = { cardMenuExpanded = true }
                        Box(
                            modifier = Modifier
                                .size(dimens.controlHeightSmall)
                                .studioTouchTarget(
                                    minSize = dimens.minTouchTarget,
                                    onOutOfBoundsTap = openCardMenu
                                )
                                .clickable(role = Role.Button, onClick = openCardMenu)
                                .testTag("snippet_more_${snippet.id}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.cd_script_actions, snippet.title),
                                tint = tokens.textSecondary,
                                modifier = Modifier.size(dimens.iconSize)
                            )
                        }

                        DropdownMenu(
                            expanded = cardMenuExpanded,
                            onDismissRequest = { cardMenuExpanded = false },
                            containerColor = tokens.floatingGlass,
                            shape = tokens.smallShape
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.ai_btn_copy),
                                        style = compactType.body,
                                        color = tokens.textPrimary
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = null,
                                        tint = tokens.textSecondary
                                    )
                                },
                                onClick = {
                                    cardMenuExpanded = false
                                    onCopy()
                                },
                                modifier = Modifier.testTag("snippet_menu_copy_${snippet.id}")
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.action_edit),
                                        style = compactType.body,
                                        color = tokens.textPrimary
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = null,
                                        tint = tokens.textSecondary
                                    )
                                },
                                onClick = {
                                    cardMenuExpanded = false
                                    onEdit()
                                },
                                modifier = Modifier.testTag("snippet_menu_edit_${snippet.id}")
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.action_delete),
                                        style = compactType.body,
                                        color = tokens.error
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = null,
                                        tint = tokens.error
                                    )
                                },
                                onClick = {
                                    cardMenuExpanded = false
                                    onDeleteRequest()
                                },
                                modifier = Modifier.testTag("snippet_menu_delete_${snippet.id}")
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ScriptDetailBottomSheet(
    snippet: CommandSnippet,
    onDismiss: () -> Unit,
    onInsert: () -> Unit,
    onCopy: () -> Unit,
    onExplain: () -> Unit,
    onEdit: () -> Unit,
    onDeleteRequest: () -> Unit
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val risk = remember(snippet.command) { CommandRiskClassifier.classify(snippet.command) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = tokens.floatingGlass,
        contentColor = tokens.textPrimary,
        shape = tokens.sheetShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(safeContentPadding(tokens.sheetShape, StudioSpacing.lg, StudioSpacing.lg))
                .padding(bottom = StudioSpacing.xl)
                .testTag("script_detail_sheet"),
            verticalArrangement = Arrangement.spacedBy(StudioSpacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(StudioSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = snippet.title,
                    style = compactType.screenTitle,
                    color = tokens.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                CommandRiskBadge(risk = risk)
            }

            if (snippet.tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(StudioSpacing.xs),
                    verticalArrangement = Arrangement.spacedBy(StudioSpacing.xs)
                ) {
                    snippet.tags.forEach { tag ->
                        Surface(
                            color = tokens.layer2,
                            shape = tokens.pillShape,
                            border = BorderStroke(tokens.borderWidth, tokens.thinBorderBrush),
                            modifier = Modifier.height(dimens.chipHeight)
                        ) {
                            Box(
                                modifier = Modifier.padding(safeContentPadding(tokens.pillShape, StudioSpacing.sm)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "#$tag",
                                    style = compactType.tabOrChip,
                                    color = tokens.textSecondary
                                )
                            }
                        }
                    }
                }
            }

            Surface(
                color = tokens.baseCanvas,
                shape = tokens.smallShape,
                border = BorderStroke(tokens.borderWidth, tokens.thinBorderBrush),
                modifier = Modifier.fillMaxWidth()
            ) {
                SelectionContainer {
                    Text(
                        text = snippet.command,
                        style = compactType.monoPreview,
                        color = tokens.textPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(safeContentPadding(tokens.smallShape, dimens.cardPaddingH, dimens.cardPaddingV))
                    )
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(StudioSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(StudioSpacing.sm)
            ) {
                Button(
                    onClick = onInsert,
                    shape = tokens.buttonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tokens.primaryButtonContainer,
                        contentColor = tokens.primaryButtonContent
                    ),
                    contentPadding = safeContentPadding(tokens.buttonShape, dimens.cardPaddingH),
                    modifier = Modifier
                        .height(dimens.controlHeight)
                        .studioTouchTarget(
                            minSize = dimens.minTouchTarget,
                            onOutOfBoundsTap = onInsert
                        )
                        .testTag("detail_insert_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                        contentDescription = null,
                        modifier = Modifier.size(dimens.iconSize)
                    )
                    Spacer(modifier = Modifier.width(StudioSpacing.xs))
                    Text(
                        text = stringResource(R.string.snippets_insert),
                        style = compactType.tabOrChip,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                OutlinedButton(
                    onClick = onCopy,
                    shape = tokens.buttonShape,
                    border = BorderStroke(tokens.borderWidth, tokens.thinBorderBrush),
                    contentPadding = safeContentPadding(tokens.buttonShape, dimens.cardPaddingH),
                    modifier = Modifier
                        .height(dimens.controlHeight)
                        .studioTouchTarget(
                            minSize = dimens.minTouchTarget,
                            onOutOfBoundsTap = onCopy
                        )
                        .testTag("detail_copy_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        tint = tokens.textSecondary,
                        modifier = Modifier.size(dimens.iconSizeSmall)
                    )
                    Spacer(modifier = Modifier.width(StudioSpacing.xs))
                    Text(
                        text = stringResource(R.string.ai_btn_copy),
                        style = compactType.tabOrChip,
                        color = tokens.textPrimary
                    )
                }

                OutlinedButton(
                    onClick = onExplain,
                    shape = tokens.buttonShape,
                    border = BorderStroke(tokens.borderWidth, tokens.info.copy(alpha = 0.55f)),
                    contentPadding = safeContentPadding(tokens.buttonShape, dimens.cardPaddingH),
                    modifier = Modifier
                        .height(dimens.controlHeight)
                        .studioTouchTarget(
                            minSize = dimens.minTouchTarget,
                            onOutOfBoundsTap = onExplain
                        )
                        .testTag("detail_explain_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                        contentDescription = null,
                        tint = tokens.info,
                        modifier = Modifier.size(dimens.iconSizeSmall)
                    )
                    Spacer(modifier = Modifier.width(StudioSpacing.xs))
                    Text(
                        text = stringResource(R.string.ai_btn_explain),
                        style = compactType.tabOrChip,
                        color = tokens.info
                    )
                }

                OutlinedButton(
                    onClick = onEdit,
                    shape = tokens.buttonShape,
                    border = BorderStroke(tokens.borderWidth, tokens.thinBorderBrush),
                    contentPadding = safeContentPadding(tokens.buttonShape, dimens.cardPaddingH),
                    modifier = Modifier
                        .height(dimens.controlHeight)
                        .studioTouchTarget(
                            minSize = dimens.minTouchTarget,
                            onOutOfBoundsTap = onEdit
                        )
                        .testTag("detail_edit_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        tint = tokens.textSecondary,
                        modifier = Modifier.size(dimens.iconSizeSmall)
                    )
                    Spacer(modifier = Modifier.width(StudioSpacing.xs))
                    Text(
                        text = stringResource(R.string.action_edit),
                        style = compactType.tabOrChip,
                        color = tokens.textPrimary
                    )
                }

                OutlinedButton(
                    onClick = onDeleteRequest,
                    shape = tokens.buttonShape,
                    border = BorderStroke(tokens.borderWidth, tokens.error.copy(alpha = 0.55f)),
                    contentPadding = safeContentPadding(tokens.buttonShape, dimens.cardPaddingH),
                    modifier = Modifier
                        .height(dimens.controlHeight)
                        .studioTouchTarget(
                            minSize = dimens.minTouchTarget,
                            onOutOfBoundsTap = onDeleteRequest
                        )
                        .testTag("detail_delete_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = null,
                        tint = tokens.error,
                        modifier = Modifier.size(dimens.iconSizeSmall)
                    )
                    Spacer(modifier = Modifier.width(StudioSpacing.xs))
                    Text(
                        text = stringResource(R.string.action_delete),
                        style = compactType.tabOrChip,
                        color = tokens.error
                    )
                }
            }
        }
    }
}

data class SnippetEditorState(
    val existingId: String? = null,
    val title: String = "",
    val command: String = "",
    val tagsCsv: String = "",
    val isNew: Boolean = true
)

@Composable
fun SnippetEditorDialog(
    initialState: SnippetEditorState,
    dialogTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (title: String, command: String, tags: List<String>) -> Unit
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val haptic = LocalHapticFeedback.current
    var title by remember(initialState) { mutableStateOf(initialState.title) }
    var command by remember(initialState) { mutableStateOf(initialState.command) }
    var tagsCsv by remember(initialState) { mutableStateOf(initialState.tagsCsv) }

    val saveAction = {
        if (command.isNotBlank()) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            val parsedTags = tagsCsv.split(',')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .distinct()
            onConfirm(title, command, parsedTags)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .padding(horizontal = StudioSpacing.lg)
            .widthIn(max = dimens.dialogMaxWidth),
        containerColor = tokens.floatingGlass,
        title = {
            Text(
                text = dialogTitle,
                style = compactType.cardTitle,
                color = tokens.textPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(StudioSpacing.sm)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    textStyle = compactType.body.copy(color = tokens.textPrimary),
                    label = { Text(stringResource(R.string.snippets_field_title), style = compactType.tabOrChip) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("snippet_editor_title")
                )
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    minLines = 2,
                    maxLines = 6,
                    label = { Text(stringResource(R.string.snippets_field_command), style = compactType.tabOrChip) },
                    textStyle = compactType.monoPreview.copy(color = tokens.textPrimary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("snippet_editor_command")
                )
                OutlinedTextField(
                    value = tagsCsv,
                    onValueChange = { tagsCsv = it },
                    singleLine = true,
                    textStyle = compactType.body.copy(color = tokens.textPrimary),
                    label = { Text(stringResource(R.string.snippets_field_tags), style = compactType.tabOrChip) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("snippet_editor_tags")
                )
            }
        },
        confirmButton = {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                Button(
                    enabled = command.isNotBlank(),
                    onClick = saveAction,
                    shape = tokens.buttonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tokens.primaryButtonContainer,
                        contentColor = tokens.primaryButtonContent
                    ),
                    contentPadding = safeContentPadding(tokens.buttonShape, dimens.cardPaddingH),
                    modifier = Modifier
                        .height(dimens.controlHeight)
                        .studioTouchTarget(
                            minSize = dimens.minTouchTarget,
                            enabled = command.isNotBlank(),
                            onOutOfBoundsTap = saveAction
                        )
                        .testTag("snippet_editor_save_button")
                ) {
                    Text(stringResource(R.string.action_save), style = compactType.tabOrChip)
                }
            }
        },
        dismissButton = {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                TextButton(
                    onClick = onDismiss,
                    contentPadding = PaddingValues(horizontal = dimens.cardPaddingH),
                    modifier = Modifier
                        .height(dimens.controlHeight)
                        .studioTouchTarget(
                            minSize = dimens.minTouchTarget,
                            onOutOfBoundsTap = onDismiss
                        )
                ) {
                    Text(stringResource(R.string.action_cancel), style = compactType.tabOrChip, color = tokens.textSecondary)
                }
            }
        },
        shape = tokens.cardShape
    )
}

@Composable
private fun TagFilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val bgColor = if (selected) tokens.accentMuted else tokens.layer1
    val borderColor = if (selected) tokens.accent.copy(alpha = 0.65f) else tokens.borderStart

    Box(
        modifier = modifier
            .height(dimens.chipHeight)
            .studioTouchTarget(
                minSize = dimens.minTouchTarget,
                onOutOfBoundsTap = onClick
            )
            .background(bgColor, tokens.pillShape)
            .border(tokens.borderWidth, borderColor, tokens.pillShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = label
            }
            .padding(safeContentPadding(tokens.pillShape, StudioSpacing.sm)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = compactType.tabOrChip,
            color = if (selected) tokens.accent else tokens.textSecondary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}
