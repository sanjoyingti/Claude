package com.arin.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arin.chatlist.ChatsDrawer
import com.arin.design.ArinMark
import kotlinx.coroutines.launch

private val starters = listOf(
    "Explain something simply",
    "Help me write a message",
    "Plan my day",
    "Debug some code",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onOpenSettings: () -> Unit) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var input by rememberSaveable { mutableStateOf("") }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.attach(it) }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { vm.attach(it) }

    LaunchedEffect(vm.sharedText) {
        val shared = vm.sharedText ?: return@LaunchedEffect
        input = if (input.isBlank()) shared else input + "\n" + shared
        vm.sharedText = null
    }
    LaunchedEffect(vm.notice) {
        val n = vm.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(n)
        vm.notice = null
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ChatsDrawer(
                vm,
                onPick = { scope.launch { drawer.close() } },
                onSettings = { scope.launch { drawer.close() }; onOpenSettings() },
            )
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopBar(
                    vm,
                    onMenu = { scope.launch { drawer.open() } },
                    onNew = { vm.newChat(); input = "" },
                )
            },
            bottomBar = {
                Composer(
                    vm,
                    text = input,
                    onText = { input = it },
                    onSend = { vm.send(input); input = "" },
                    onPhotos = {
                        pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onFiles = { pickFiles.launch(arrayOf("*/*")) },
                    onCancelEdit = { vm.cancelEdit(); input = "" },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (vm.messages.isEmpty() && !vm.streaming) {
                    Welcome(onPick = { input = it })
                } else {
                    Conversation(vm, onEdit = { i -> vm.startEdit(i)?.let { input = it } })
                }
            }
        }
    }
}

@Composable
private fun TopBar(vm: ChatViewModel, onMenu: () -> Unit, onNew: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMenu) { Icon(Icons.Filled.Menu, contentDescription = "Chats") }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Surface(
                onClick = { menu = true },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(
                            vm.title.takeIf { vm.messages.isNotEmpty() } ?: "Arin",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            vm.modelLabel(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Choose model", modifier = Modifier.size(20.dp))
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                Text(
                    vm.provider.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                vm.models.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m.label + if (m.id == vm.model) "  ✓" else "") },
                        onClick = { vm.chooseModel(m); menu = false },
                    )
                }
            }
        }
        IconButton(onClick = onNew) { Icon(Icons.Filled.Create, contentDescription = "New chat") }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Welcome(onPick: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ArinMark(size = 56.dp)
        Spacer(Modifier.height(18.dp))
        Text("Hi, I'm Arin", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "Ask me anything, or attach a photo, PDF or file to talk about it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            starters.forEach { s -> AssistChip(onClick = { onPick("$s: ") }, label = { Text(s) }) }
        }
    }
}

@Composable
private fun Conversation(vm: ChatViewModel, onEdit: (Int) -> Unit) {
    val list = rememberLazyListState()
    val count = vm.messages.size + if (vm.streaming) 1 else 0
    // Follow the reply as it grows, but only if the person hasn't scrolled up to read.
    LaunchedEffect(count, vm.liveText.length / 200) {
        if (count > 0 && (vm.liveText.isEmpty() || !list.canScrollForward || list.isScrollInProgress.not())) {
            list.animateScrollToItem(count - 1)
        }
    }
    LazyColumn(
        state = list,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(vm.messages, key = { _, m -> m.id }) { i, m ->
            if (m.fromUser) UserMessage(m, onEdit = if (vm.streaming) null else ({ onEdit(i) }))
            else ArinMessage(m, vm.showThinking, isLast = i == vm.messages.lastIndex && !vm.streaming, onRetry = vm::retry)
        }
        if (vm.streaming) {
            item(key = "live") { LiveReply(vm.liveText, vm.liveThinking, vm.showThinking) }
        }
    }
}

@Composable
private fun Composer(
    vm: ChatViewModel,
    text: String,
    onText: (String) -> Unit,
    onSend: () -> Unit,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
    onCancelEdit: () -> Unit,
) {
    var attachMenu by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
    ) {
        if (vm.editing != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Editing your message. Sending replaces it and the replies after it.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.TextButton(onClick = onCancelEdit) { Text("Cancel") }
            }
        }
        vm.pending.forEach { f -> FileChip(f, onRemove = { vm.removePending(f) }) }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 0.dp,
        ) {
            Row(Modifier.padding(6.dp), verticalAlignment = Alignment.Bottom) {
                Box {
                    IconButton(onClick = { attachMenu = true }) { Icon(Icons.Filled.Add, contentDescription = "Attach") }
                    DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                        DropdownMenuItem(text = { Text("Photos") }, onClick = { attachMenu = false; onPhotos() })
                        DropdownMenuItem(text = { Text("Files and PDFs") }, onClick = { attachMenu = false; onFiles() })
                    }
                }
                TextField(
                    value = text,
                    onValueChange = onText,
                    placeholder = { Text("Message Arin") },
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.weight(1f),
                )
                if (vm.streaming) {
                    FilledIconButton(
                        onClick = vm::stop,
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.onSurface),
                    ) {
                        Box(Modifier.size(12.dp).padding(0.dp).then(Modifier), contentAlignment = Alignment.Center) {
                            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(2.dp), modifier = Modifier.size(12.dp)) {}
                        }
                    }
                } else {
                    val canSend = text.isNotBlank() || vm.pending.isNotEmpty()
                    FilledIconButton(onClick = onSend, enabled = canSend, shape = CircleShape) {
                        Icon(Icons.Filled.Send, contentDescription = "Send", modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.width(2.dp))
            }
        }
    }
}
