package com.pocketllm.ui.chat

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketllm.Graph
import com.pocketllm.R
import com.pocketllm.data.ChatMessage
import com.pocketllm.data.ChatSession
import com.pocketllm.data.ThinkingLevel
import com.pocketllm.engine.InferenceEngine
import com.pocketllm.ui.chat.render.ResponseRenderer
import com.pocketllm.ui.chat.render.containsRtlScript
import com.pocketllm.ui.chat.render.decodeEntities
import com.pocketllm.ui.chat.render.textDirectionOf
import com.pocketllm.ui.components.AppChip
import com.pocketllm.ui.components.ConfirmDialog
import com.pocketllm.ui.components.StatusNotice
import com.pocketllm.ui.components.SmallActionButton
import com.pocketllm.ui.theme.IconSize
import com.pocketllm.ui.theme.SectionLabel
import com.pocketllm.ui.theme.Spacing
import com.pocketllm.ui.theme.Starfield
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    // Auto-follow is a preference, not a constant scroll: an explicit reader
    // scroll wins until they come back to the newest message.
    var followLatest by remember { mutableStateOf(true) }
    var lastFirstIndex by remember { mutableStateOf(-1) }
    var lastFirstOffset by remember { mutableStateOf(0) }
    var lastMessageCount by remember { mutableStateOf(-1) }
    val snackbar = remember { SnackbarHostState() }
    val view = LocalView.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val caps by vm.activeCapabilities.collectAsStateWithLifecycle()

    val mediaDir = remember { File(context.cacheDir, "media").apply { mkdirs() } }
    val recorder = remember { VoiceRecorder(context, mediaDir) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableStateOf(0) }

    LaunchedEffect(isRecording) {
        while (isRecording) {
            kotlinx.coroutines.delay(300)
            recordingSeconds = recorder.elapsedSeconds
        }
    }

    val captureFile = remember { File(mediaDir, "capture.jpg") }
    val captureUri = remember(captureFile) {
        FileProvider.getUriForFile(context, "com.pocketllm.fileprovider", captureFile)
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved ->
        if (saved) {
            BitmapFactory.decodeFile(captureFile.absolutePath)?.let { bmp ->
                val scaled = scaleDown(bmp)
                val dest = File(mediaDir, "photo_${System.currentTimeMillis()}.jpg")
                runCatching {
                    java.io.FileOutputStream(dest).use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                    if (!vm.addAttachment(
                            Attachment.Photo(
                                id = "photo_${System.currentTimeMillis()}",
                                uri = dest.absolutePath,
                                label = context.getString(R.string.attachment_photo),
                            ),
                        )
                    ) {
                        dest.delete()
                    }
                }
            }
        }
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input)?.let { bmp ->
                        val dest = File(mediaDir, "photo_${System.currentTimeMillis()}.jpg")
                        val scaled = scaleDown(bmp)
                        java.io.FileOutputStream(dest).use {
                            scaled.compress(Bitmap.CompressFormat.JPEG, 82, it)
                        }
                        if (!vm.addAttachment(
                                Attachment.Photo(
                                    id = "photo_${System.currentTimeMillis()}",
                                    uri = dest.absolutePath,
                                    label = context.getString(R.string.attachment_photo),
                                ),
                            )
                        ) {
                            dest.delete()
                        }
                    }
                }
            }
        }
    }
    val recordPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && recorder.start() != null) {
            isRecording = true
            recordingSeconds = 0
        }
    }
    val toggleRecording: () -> Unit = {
        if (isRecording) {
            val result = recorder.stop()
            isRecording = false
            if (result != null && result.durationMs > 0 && result.file.exists()) {
                if (!vm.addAttachment(
                        Attachment.Voice(
                            id = "voice_${System.currentTimeMillis()}",
                            uri = result.file.absolutePath,
                            label = formatVoiceDuration(result.durationMs),
                            durationMs = result.durationMs,
                        ),
                    )
                ) {
                    result.file.delete()
                }
            }
        } else if (recorder.hasPermission() && recorder.start() != null) {
            isRecording = true
            recordingSeconds = 0
        } else if (!recorder.hasPermission()) {
            recordPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(ui.error) {
        val message = ui.error ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        vm.dismissError()
    }

    LaunchedEffect(ui.currentSessionId) {
        // Opening a conversation lands on its newest message; later token updates
        // only follow when the reader is already near the bottom.
        followLatest = true
        if (ui.messages.isNotEmpty()) {
            listState.scrollToItem(ui.messages.lastIndex)
        }
        lastFirstIndex = listState.firstVisibleItemIndex
        lastFirstOffset = listState.firstVisibleItemScrollOffset
        lastMessageCount = ui.messages.size
    }

    LaunchedEffect(ui.messages.size, ui.messages.lastOrNull()?.content?.length) {
        if (ui.messages.isEmpty()) return@LaunchedEffect

        val first = listState.firstVisibleItemIndex
        val offset = listState.firstVisibleItemScrollOffset
        val userMoved = lastFirstIndex >= 0 &&
            (first != lastFirstIndex || offset != lastFirstOffset)
        val atBottom = listState.isNearBottom()
        val sizeGrew = lastMessageCount >= 0 && ui.messages.size > lastMessageCount

        when {
            sizeGrew -> followLatest = true // a new turn always starts at the bottom
            userMoved -> followLatest = atBottom // reader scroll wins
        }
        if (sizeGrew) {
            // A new turn starts pinned to its own reply even if the reader was
            // scrolled back through history when they hit send.
            followLatest = true
            listState.scrollToItem(ui.messages.lastIndex)
        } else if (followLatest && atBottom) {
            // Instant follow, never an animation: animating on every token fights
            // the reader's own scroll gesture and yanks the view back down.
            listState.scrollToItem(ui.messages.lastIndex)
        }

        lastFirstIndex = listState.firstVisibleItemIndex
        lastFirstOffset = listState.firstVisibleItemScrollOffset
        lastMessageCount = ui.messages.size
    }

    DisposableEffect(Unit) {
        onDispose {
            if (recorder.isRecording) recorder.stop()
        }
    }
    DisposableEffect(ui.generating, ui.keepScreenOn) {
        view.keepScreenOn = ui.generating && ui.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(chatBackground()),
    ) {
        Starfield(Modifier.matchParentSize(), animate = !ui.generating && !Graph.benchMode)
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
                navigationIcon = {
                    BrandMark(Modifier.padding(start = 16.dp, end = 10.dp))
                },
                title = {
                    Column {
                        Text(
                            text = ui.currentSession?.title ?: stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(ui.modelStatus)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = listOfNotNull(
                                    ui.activeModel?.displayName,
                                    modelStatusText(ui.modelStatus),
                                    stringResource(
                                        com.pocketllm.data.Catalog.languageLabelRes(ui.activeModel?.id),
                                    ),
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    if (caps.reasoning) {
                        ThinkingLevelChip(
                            level = ui.thinkingLevel,
                            onSelect = vm::setThinkingLevel,
                        )
                    }
                    IconButton(onClick = vm::openSessions) {
                        Icon(
                            Icons.Rounded.History,
                            contentDescription = stringResource(R.string.cd_sessions),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(IconSize.TopBar),
                        )
                    }
                    IconButton(onClick = vm::newChat) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = stringResource(R.string.cd_new_chat),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(IconSize.TopBar),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            ChatInputBar(
                value = ui.input,
                attachments = ui.attachments,
                generating = ui.generating,
                enabled = ui.activeModel != null,
                photoEnabled = caps.vision,
                voiceEnabled = caps.audio,
                isRecording = isRecording,
                recordingSeconds = recordingSeconds,
                onValueChange = vm::onInputChange,
                onSend = {
                    vm.send()
                    // Dismiss the keyboard so the nav bar slides back into view.
                    focusManager.clearFocus()
                },
                onStop = vm::stop,
                onTakePhoto = { cameraLauncher.launch(captureUri) },
                onPickPhoto = { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onToggleRecord = toggleRecording,
                onRemoveAttachment = vm::removeAttachment,
                languageWarning = containsRtlScript(ui.input) &&
                    com.pocketllm.data.Catalog.languageSupport(ui.activeModel?.id) ==
                    com.pocketllm.data.Catalog.LanguageSupport.ENGLISH,
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (ui.messages.isEmpty()) {
                EmptyChat(
                    onSuggestion = { text ->
                        vm.onInputChange(text)
                        vm.send()
                    },
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 96.dp),
                ) {
                    itemsIndexed(
                        ui.messages,
                        key = { index, message -> "${index}:${message.ts}" },
                    ) { index, message ->
                        val isLast = index == ui.messages.lastIndex
                        val sameAuthor = index > 0 && ui.messages[index - 1].isUser == message.isUser
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    top = when {
                                        index == 0 -> 0.dp
                                        sameAuthor -> Spacing.S
                                        else -> Spacing.L
                                    },
                                ),
                        ) {
                            MessageRow(
                                message = message,
                                streaming = ui.generating && isLast,
                                isLastAssistant = isLast && !message.isUser,
                                onCopied = {
                                    scope.launch {
                                        snackbar.showSnackbar(context.getString(R.string.chat_copied))
                                    }
                                },
                                onRegenerate = vm::regenerate,
                            )
                        }
                    }
                    item {
                        val stats = ui.stats
                        if (stats != null) {
                            Text(
                                text = stats,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = Spacing.L, bottom = Spacing.XS),
                            )
                        }
                    }
                }
            }
        }
    }
        }

    if (ui.sessionsOpen) {
        SessionSheet(
            sessions = ui.sessions,
            currentId = ui.currentSessionId,
            onSelect = vm::selectSession,
            onDelete = vm::requestDeleteSession,
            onNewChat = vm::newChat,
            onDismiss = vm::closeSessions,
        )
    }

    ui.sessionToDelete?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.session_delete_title),
            body = stringResource(R.string.session_delete_body, target.title),
            confirmLabel = stringResource(R.string.common_delete),
            dismissLabel = stringResource(R.string.common_cancel),
            onConfirm = vm::confirmDeleteSession,
            onDismiss = vm::cancelDeleteSession,
            icon = Icons.Rounded.DeleteOutline,
        )
    }
}

/**
 * The empty-state focus: a single quiet star with a soft halo, drawn from the
 * same sky as the starfield rather than repeating the app or message marks.
 */
@Composable
private fun HeroStar(modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onBackground
    Canvas(modifier.size(88.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val outer = size.minDimension * 0.28f
        val inner = outer * 0.42f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(ink.copy(alpha = 0.12f), Color.Transparent),
                center = Offset(cx, cy),
                radius = size.minDimension * 0.48f,
            ),
            radius = size.minDimension * 0.48f,
            center = Offset(cx, cy),
        )
        val star = Path()
        val step = (Math.PI / 5.0).toFloat()
        repeat(10) { i ->
            val radius = if (i % 2 == 0) outer else inner
            val angle = i * step - (Math.PI / 2.0).toFloat()
            val x = cx + cos(angle) * radius
            val y = cy + sin(angle) * radius
            if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
        }
        star.close()
        drawPath(path = star, color = ink.copy(alpha = 0.85f))
        drawCircle(color = ink.copy(alpha = 0.5f), radius = outer * 0.22f, center = Offset(cx, cy))
    }
}

@Composable
private fun BrandMark(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(34.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_brand_mark),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(IconSize.TopBar),
        )
    }
}

@Composable
private fun chatBackground(): Brush {
    val scheme = MaterialTheme.colorScheme
    return Brush.verticalGradient(
        colorStops = arrayOf(
            0f to scheme.background,
            1f to scheme.surfaceVariant,
        ),
    )
}

@Composable
private fun modelStatusText(status: InferenceEngine.Status): String = when (status) {
    is InferenceEngine.Status.NoModel -> stringResource(R.string.chat_no_model)
    is InferenceEngine.Status.Loading -> stringResource(R.string.chat_loading_model)
    is InferenceEngine.Status.Ready -> stringResource(R.string.chat_ready)
    is InferenceEngine.Status.Failed -> stringResource(R.string.chat_load_failed)
}

@Composable
private fun StatusDot(status: InferenceEngine.Status) {
    val color = when (status) {
        is InferenceEngine.Status.NoModel -> MaterialTheme.colorScheme.outline
        is InferenceEngine.Status.Loading -> MaterialTheme.colorScheme.onSurfaceVariant
        is InferenceEngine.Status.Ready -> MaterialTheme.colorScheme.onSurface
        is InferenceEngine.Status.Failed -> MaterialTheme.colorScheme.error
    }
    // A quiet pulse while the model loads, so the status line reads as "working".
    val transition = rememberInfiniteTransition(label = "status")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "statusAlpha",
    )
    val alpha = if (status is InferenceEngine.Status.Loading) pulse else 1f
    Box(
        Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha)),
    )
}

@Composable
private fun ThinkingLevel.description(): String = when (this) {
    ThinkingLevel.OFF -> stringResource(R.string.thinking_off_desc)
    ThinkingLevel.LOW -> stringResource(R.string.thinking_low_desc)
    ThinkingLevel.MEDIUM -> stringResource(R.string.thinking_medium_desc)
    ThinkingLevel.HIGH -> stringResource(R.string.thinking_high_desc)
}

@Composable
private fun ThinkingLevelChip(level: ThinkingLevel, onSelect: (ThinkingLevel) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    Box {
        AppChip(
            text = stringResource(R.string.thinking_level_chip, level.label),
            selected = false,
            onClick = { expanded = true },
            modifier = Modifier.padding(end = 4.dp),
            leadingIcon = Icons.Rounded.Psychology,
            trailingIcon = Icons.Rounded.ExpandMore,
            compact = true,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            Text(
                text = stringResource(R.string.thinking_level_title),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            ThinkingLevel.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(option.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                option.description(),
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    },
                    leadingIcon = {
                        if (option == level) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                tint = scheme.onSurface,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyChat(onSuggestion: (String) -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HeroStar()
            Spacer(Modifier.height(24.dp))
            SectionLabel(stringResource(R.string.chat_empty_eyebrow))
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.chat_empty_title),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.chat_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(30.dp))
            val suggestions = listOf(
                stringResource(R.string.chat_chip_1),
                stringResource(R.string.chat_chip_2),
                stringResource(R.string.chat_chip_3),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { text ->
                    Surface(
                        onClick = { onSuggestion(text) },
                        shape = RoundedCornerShape(14.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = Spacing.L, vertical = Spacing.M),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowForward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(IconSize.Medium),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageRow(
    message: ChatMessage,
    streaming: Boolean,
    isLastAssistant: Boolean,
    onCopied: () -> Unit,
    onRegenerate: () -> Unit,
) {
    if (message.isUser) {
        val clean = remember(message.content) {
            decodeEntities(message.content.replace("<__media__>", "").trim())
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(
                    topStart = 22.dp,
                    topEnd = 22.dp,
                    bottomStart = 22.dp,
                    bottomEnd = 6.dp,
                ),
                modifier = Modifier.widthIn(max = 340.dp),
            ) {
                Column(Modifier.padding(horizontal = Spacing.L, vertical = Spacing.M)) {
                    if (message.attachments.isNotEmpty()) {
                        Row(
                            modifier = Modifier.padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            message.attachments.take(3).forEach { att ->
                                Box(
                                    Modifier
                                        .size(30.dp)
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = if (att.kind == "photo") Icons.Rounded.PhotoCamera else Icons.Rounded.Mic,
                                        contentDescription = if (att.kind == "photo") "photo" else "voice",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                    SelectionContainer {
                        Text(
                            text = clean,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                textDirection = textDirectionOf(clean),
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    } else {
        val clipboard = LocalClipboardManager.current
        val cleaned = remember(message.content) {
            message.content.replace(Regex("<\\|[A-Za-z0-9_]+\\|>"), "").trim()
        }
        val parts = remember(cleaned) { splitThinking(cleaned) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier
                    .padding(top = 2.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_message_mark),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(IconSize.Small),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(
                // Height animation makes the layout lag behind arriving text and
                // fights scroll preservation; streamed text should size instantly.
                modifier = Modifier.weight(1f).let {
                    if (streaming) it else it.animateContentSize()
                },
            ) {
                if (parts.thinking != null) {
                    ThinkingBlock(
                        text = parts.thinking,
                        streaming = streaming && parts.answer.isEmpty(),
                    )
                    if (parts.answer.isNotEmpty()) Spacer(Modifier.height(12.dp))
                }

                when {
                    parts.answer.isNotEmpty() -> {
                        ResponseRenderer(
                            text = parts.answer,
                            streaming = streaming,
                            onCodeCopied = onCopied,
                        )
                    }

                    streaming && parts.thinking != null -> Unit

                    streaming -> {
                        ResponseRenderer(text = "", streaming = true, onCodeCopied = onCopied)
                    }

                    else -> {
                        Text(
                            text = "…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (isLastAssistant && !streaming && parts.answer.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.S),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.XS),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SmallAction(
                            icon = Icons.Rounded.Refresh,
                            description = stringResource(R.string.cd_regenerate),
                            onClick = onRegenerate,
                        )
                        SmallAction(
                            icon = Icons.Rounded.ContentCopy,
                            description = stringResource(R.string.cd_copy_message),
                            onClick = {
                                clipboard.setText(AnnotatedString(parts.answer))
                                onCopied()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    SmallActionButton(icon = icon, description = description, onClick = onClick)
}

@Composable
private fun ThinkingBlock(text: String, streaming: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.animateContentSize()) {
            Row(
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (streaming) {
                    ThinkingDots(color = scheme.onSurfaceVariant)
                } else {
                    Icon(
                        Icons.Rounded.Psychology,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(
                        if (streaming) R.string.thinking_in_progress else R.string.thinking_tap_to_view
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (expanded && text.isNotBlank()) {
                HorizontalDivider(color = scheme.outlineVariant)
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall.copy(
                        textDirection = textDirectionOf(text),
                    ),
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun ThinkingDots(color: Color) {
    val transition = rememberInfiniteTransition(label = "thinking")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 450,
                        delayMillis = index * 160,
                        easing = FastOutSlowInEasing,
                    ),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(
                Modifier
                    .size(5.dp)
                    .background(color.copy(alpha = alpha), CircleShape),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatInputBar(
    value: String,
    attachments: List<Attachment>,
    generating: Boolean,
    enabled: Boolean,
    photoEnabled: Boolean,
    voiceEnabled: Boolean,
    isRecording: Boolean,
    recordingSeconds: Int,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
    onToggleRecord: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    languageWarning: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    var menuExpanded by remember { mutableStateOf(false) }
    // Keep the menu reachable while recording so the clip can always be stopped.
    val canAttach = (photoEnabled || voiceEnabled) && (!generating || isRecording)

    Surface(color = Color.Transparent) {
        Column(
            modifier = Modifier.navigationBarsPadding(),
        ) {
            if (languageWarning) {
                StatusNotice(
                    title = stringResource(R.string.language_warning_title),
                    detail = stringResource(R.string.language_warning_detail),
                    error = true,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = Spacing.XS),
                )
            }
            if (attachments.isNotEmpty() || isRecording) {
                AttachmentChips(
                    attachments = attachments,
                    isRecording = isRecording,
                    recordingSeconds = recordingSeconds,
                    onRemove = onRemoveAttachment,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = Spacing.S)
                    .clip(RoundedCornerShape(26.dp))
                    .background(scheme.surfaceVariant.copy(alpha = 0.55f))
                    .border(
                        width = 1.dp,
                        color = scheme.outlineVariant.copy(alpha = 0.75f),
                        shape = RoundedCornerShape(26.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (photoEnabled || voiceEnabled) {
                    Box {
                        FilledIconButton(
                            onClick = { menuExpanded = true },
                            enabled = canAttach,
                            modifier = Modifier.size(46.dp),
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = Color.Transparent,
                                contentColor = scheme.onSurfaceVariant,
                                disabledContainerColor = Color.Transparent,
                                disabledContentColor = scheme.outline,
                            ),
                        ) {
                            Icon(
                                Icons.Rounded.AddPhotoAlternate,
                                contentDescription = stringResource(R.string.cd_attach),
                                modifier = Modifier.size(IconSize.Large),
                            )
                        }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        if (photoEnabled) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attach_take_photo)) },
                                leadingIcon = { Icon(Icons.Rounded.PhotoCamera, contentDescription = null, modifier = Modifier.size(IconSize.Medium)) },
                                onClick = { menuExpanded = false; onTakePhoto() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attach_gallery)) },
                                leadingIcon = { Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(IconSize.Medium)) },
                                onClick = { menuExpanded = false; onPickPhoto() },
                            )
                        }
                        if (voiceEnabled) {
                            DropdownMenuItem(
                                text = { Text(stringResource(if (isRecording) R.string.voice_stop else R.string.voice_record)) },
                                leadingIcon = { Icon(Icons.Rounded.Mic, contentDescription = null, modifier = Modifier.size(IconSize.Medium)) },
                                onClick = { menuExpanded = false; onToggleRecord() },
                            )
                        }
                    }
                }
            }
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            stringResource(R.string.chat_input_hint),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    maxLines = 6,
                    enabled = enabled,
                    shape = RoundedCornerShape(24.dp),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        disabledBorderColor = Color.Transparent,
                        cursorColor = scheme.onSurface,
                        focusedTextColor = scheme.onSurface,
                        unfocusedTextColor = scheme.onSurface,
                        disabledTextColor = scheme.onSurfaceVariant,
                        focusedPlaceholderColor = scheme.onSurfaceVariant,
                        unfocusedPlaceholderColor = scheme.onSurfaceVariant,
                        disabledPlaceholderColor = scheme.onSurfaceVariant,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                val canSend = generating || (enabled && (value.isNotBlank() || attachments.isNotEmpty()))
                FilledIconButton(
                    onClick = { if (generating) onStop() else onSend() },
                    enabled = canSend,
                    modifier = Modifier.size(46.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = scheme.primary,
                        contentColor = scheme.onPrimary,
                        disabledContainerColor = scheme.surfaceVariant,
                        disabledContentColor = scheme.onSurfaceVariant,
                    ),
                ) {
                    if (generating) {
                        Icon(
                            Icons.Rounded.Stop,
                            contentDescription = stringResource(R.string.cd_stop),
                            modifier = Modifier.size(IconSize.Large),
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Rounded.Send,
                            contentDescription = stringResource(R.string.cd_send),
                            modifier = Modifier.size(IconSize.Medium),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionSheet(
    sessions: List<ChatSession>,
    currentId: String?,
    onSelect: (String) -> Unit,
    onDelete: (ChatSession) -> Unit,
    onNewChat: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = scheme.surface,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 28.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel(stringResource(R.string.sessions_title))
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onNewChat) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(IconSize.Medium),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.sessions_new))
                }
            }
            HorizontalDivider(color = scheme.outlineVariant)
            if (sessions.isEmpty()) {
                Text(
                    text = stringResource(R.string.sessions_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(sessions, key = { it.id }) { session ->
                        Column {
                            SessionRow(
                                session = session,
                                active = session.id == currentId,
                                onClick = { onSelect(session.id) },
                                onDelete = { onDelete(session) },
                            )
                            if (session.id != sessions.lastOrNull()?.id) {
                                HorizontalDivider(
                                    color = scheme.outlineVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.padding(horizontal = Spacing.L),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: ChatSession,
    active: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    Surface(
        onClick = onClick,
        color = if (active) scheme.surfaceVariant else Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (active) {
                Box(
                    Modifier
                        .padding(end = 10.dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(scheme.onSurface),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        R.string.session_meta,
                        formatUpdated(session.updatedAt),
                        session.userMessageCount,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
                if (session.preview.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = session.preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = stringResource(R.string.cd_delete_session),
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(IconSize.Medium),
                )
            }
        }
    }
}

private fun formatUpdated(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    return when {
        diff < 60_000L -> "Just now"
        diff < 3_600_000L -> "${diff / 60_000L}m ago"
        diff < 86_400_000L -> "${diff / 3_600_000L}h ago"
        diff < 7 * 86_400_000L -> "${diff / 86_400_000L}d ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ts))
    }
}

@Composable
private fun AttachmentChips(
    attachments: List<Attachment>,
    isRecording: Boolean,
    recordingSeconds: Int,
    onRemove: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 14.dp, end = 14.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { att ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = scheme.surfaceVariant,
                border = BorderStroke(1.dp, scheme.outlineVariant),
            ) {
                Row(
                    modifier = Modifier.padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (att) {
                        is Attachment.Photo -> {
                            val thumb = remember(att.uri) {
                                runCatching { BitmapFactory.decodeFile(att.uri) }.getOrNull()
                            }
                            if (thumb != null) {
                                Image(
                                    bitmap = thumb.asImageBitmap(),
                                    contentDescription = att.label,
                                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                            } else {
                                Icon(
                                    Icons.Rounded.PhotoCamera,
                                    contentDescription = null,
                                    tint = scheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp).size(22.dp),
                                )
                            }
                        }
                        is Attachment.Voice -> {
                            Icon(
                                Icons.Rounded.Mic,
                                contentDescription = null,
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp).size(20.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = att.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 150.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                    IconButton(onClick = { onRemove(att.id) }, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = null,
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
        if (isRecording) {
            Surface(
                shape = CircleShape,
                color = scheme.errorContainer,
                border = BorderStroke(1.dp, scheme.outline),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(scheme.error),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Recording ${recordingSeconds}s",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

private fun formatVoiceDuration(ms: Long): String =
    if (ms < 60_000) "Voice ${ms / 1000}s"
    else "Voice %d:%02d".format(ms / 60_000, (ms / 1000) % 60)

private fun scaleDown(bitmap: Bitmap, maxSide: Int = 1280): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= maxSide && h <= maxSide) return bitmap
    val ratio = maxSide.toFloat() / maxOf(w, h)
    return Bitmap.createScaledBitmap(
        bitmap,
        (w * ratio).toInt().coerceAtLeast(1),
        (h * ratio).toInt().coerceAtLeast(1),
        true,
    )
}

private fun LazyListState.isNearBottom(): Boolean {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return true
    return last.index >= info.totalItemsCount - 3 &&
        last.offset + last.size <= info.viewportEndOffset + 64
}
