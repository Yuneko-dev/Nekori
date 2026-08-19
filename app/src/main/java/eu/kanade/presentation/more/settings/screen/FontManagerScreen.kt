package eu.kanade.presentation.more.settings.screen

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.data.font.FontDownloadState
import eu.kanade.tachiyomi.data.font.FontInfo
import eu.kanade.tachiyomi.data.font.FontManager
import eu.kanade.tachiyomi.data.font.GoogleFontInfo
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.core.viewmodel.StateViewModel
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.core.common.i18n.stringResource as contextStringResource

class FontManagerScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val screenModel = viewModel<FontManagerViewModel>()
        val state by screenModel.state.collectAsState()

        val snackbarHostState = remember { SnackbarHostState() }

        var showAddFontSheet by remember { mutableStateOf(false) }
        var showGoogleFontsDialog by remember { mutableStateOf(false) }
        var fontToDelete by remember { mutableStateOf<FontInfo?>(null) }

        val fontPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            uri?.let {
                screenModel.importFont(it)
            }
        }

        LaunchedEffect(state.message) {
            state.message?.let { message ->
                snackbarHostState.showSnackbar(message)
                screenModel.clearMessage()
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(TDMR.strings.settings_font_manager_title)) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(MR.strings.action_webview_back),
                            )
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { showAddFontSheet = true },
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(TDMR.strings.settings_font_manager_add_font),
                    )
                }
            },
        ) { paddingValues ->
            if (state.isLoading) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(stringResource(TDMR.strings.settings_font_manager_loading))
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 16.dp,
                        end = 16.dp,
                        bottom = 88.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // System Fonts Section
                    item {
                        Text(
                            text = stringResource(TDMR.strings.settings_font_manager_system_fonts),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }

                    items(state.systemFonts) { font ->
                        FontItem(
                            fontInfo = font,
                            isSelected = font.path == state.selectedFontPath,
                            onClick = { screenModel.selectFont(font) },
                            onDelete = null,
                        )
                    }

                    // Custom Fonts Section
                    if (state.customFonts.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(TDMR.strings.settings_font_manager_custom_fonts),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }

                        items(state.customFonts) { font ->
                            FontItem(
                                fontInfo = font,
                                isSelected = font.path == state.selectedFontPath,
                                onClick = { screenModel.selectFont(font) },
                                onDelete = { fontToDelete = font },
                            )
                        }
                    }

                    // Download Progress
                    state.downloadProgress?.let { progress ->
                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                ) {
                                    Text(
                                        text = stringResource(TDMR.strings.settings_font_manager_downloading),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    LinearProgressIndicator(
                                        progress = { progress / 100f },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Add Font Bottom Sheet
        if (showAddFontSheet) {
            ModalBottomSheet(
                onDismissRequest = { showAddFontSheet = false },
                sheetState = rememberModalBottomSheetState(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Text(
                        text = stringResource(TDMR.strings.settings_font_manager_add_font),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Import from device
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showAddFontSheet = false
                                fontPickerLauncher.launch(
                                    arrayOf("font/*", "application/x-font-ttf", "application/x-font-opentype"),
                                )
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = stringResource(TDMR.strings.settings_font_manager_import_from_device),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = stringResource(TDMR.strings.settings_font_manager_select_ttf_otf),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Download from Google Fonts
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showAddFontSheet = false
                                showGoogleFontsDialog = true
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = stringResource(TDMR.strings.settings_font_manager_download_google_fonts),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = stringResource(TDMR.strings.settings_font_manager_browse_free_fonts),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }

        // Google Fonts Dialog
        if (showGoogleFontsDialog) {
            GoogleFontsDialog(
                googleFonts = state.googleFonts,
                isSearching = state.isSearchingGoogleFonts,
                onSearch = { screenModel.searchGoogleFonts(it) },
                onDownload = {
                    screenModel.downloadGoogleFont(it.family)
                    showGoogleFontsDialog = false
                },
                onDismiss = { showGoogleFontsDialog = false },
            )
        }

        // Delete Confirmation Dialog
        fontToDelete?.let { font ->
            AlertDialog(
                onDismissRequest = { fontToDelete = null },
                title = { Text(stringResource(TDMR.strings.settings_font_manager_delete_font_title)) },
                text = {
                    Text(
                        stringResource(TDMR.strings.settings_font_manager_delete_font_confirm, font.name),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            screenModel.deleteFont(font)
                            fontToDelete = null
                        },
                    ) {
                        Text(stringResource(MR.strings.action_delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { fontToDelete = null }) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun FontItem(
    fontInfo: FontInfo,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val fontManager = remember { Injekt.get<FontManager>() }
    val previewFontFamily by produceState<FontFamily?>(null, fontInfo.path) {
        value = withContext(Dispatchers.IO) {
            fontManager.getTypeface(fontInfo)?.let(::FontFamily)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fontInfo.name,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = previewFontFamily,
                    ),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (fontInfo.isCustom) {
                        stringResource(TDMR.strings.settings_font_manager_custom_font_label)
                    } else {
                        stringResource(TDMR.strings.settings_font_manager_system_font_label)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = stringResource(TDMR.strings.settings_font_manager_selected),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            if (onDelete != null) {
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(MR.strings.action_delete),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun GoogleFontsDialog(
    googleFonts: List<GoogleFontInfo>,
    isSearching: Boolean,
    onSearch: (String) -> Unit,
    onDownload: (GoogleFontInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(TDMR.strings.settings_font_manager_google_fonts_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        onSearch(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(TDMR.strings.settings_font_manager_search_fonts_placeholder)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(searchQuery) }),
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (isSearching) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.height(300.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(googleFonts) { font ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onDownload(font) },
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = font.family,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        Text(
                                            text = font.category,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Icon(
                                        Icons.Default.Download,
                                        contentDescription = stringResource(MR.strings.action_download),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_close))
            }
        },
    )
}

class FontManagerViewModel(
    private val fontManager: FontManager = Injekt.get(),
    private val readerPreferences: ReaderPreferences = Injekt.get(),
    private val context: Application = Injekt.get(),
) : StateViewModel<FontManagerViewModel.State>(State()) {

    data class State(
        val isLoading: Boolean = true,
        val systemFonts: List<FontInfo> = emptyList(),
        val customFonts: List<FontInfo> = emptyList(),
        val selectedFontPath: String = "",
        val googleFonts: List<GoogleFontInfo> = emptyList(),
        val isSearchingGoogleFonts: Boolean = false,
        val downloadProgress: Int? = null,
        val message: String? = null,
    )

    init {
        loadFonts()
        loadGoogleFonts("")
    }

    private fun loadFonts() {
        mutableState.update { it.copy(isLoading = true) }

        kotlinx.coroutines.MainScope().launch {
            val systemFonts = fontManager.getSystemFonts()
            val customFonts = fontManager.getInstalledFonts()
            val currentFont = readerPreferences.novelFontFamily.get()

            mutableState.update {
                it.copy(
                    isLoading = false,
                    systemFonts = systemFonts,
                    customFonts = customFonts,
                    selectedFontPath = currentFont,
                )
            }
        }
    }

    fun selectFont(font: FontInfo) {
        readerPreferences.novelFontFamily.set(font.path)
        mutableState.update { it.copy(selectedFontPath = font.path) }
    }

    fun importFont(uri: android.net.Uri) {
        kotlinx.coroutines.MainScope().launch {
            val result = fontManager.importFont(uri)
            result.fold(
                onSuccess = { font ->
                    loadFonts()
                    mutableState.update {
                        it.copy(
                            message = context.contextStringResource(
                                TDMR.strings.settings_font_manager_import_success,
                                font.name,
                            ),
                        )
                    }
                },
                onFailure = { error ->
                    mutableState.update {
                        it.copy(
                            message = context.contextStringResource(
                                TDMR.strings.settings_font_manager_import_failed,
                                error.message.orEmpty(),
                            ),
                        )
                    }
                },
            )
        }
    }

    fun deleteFont(font: FontInfo) {
        kotlinx.coroutines.MainScope().launch {
            val success = fontManager.deleteFont(font)
            if (success) {
                loadFonts()
                // Reset to default font if deleted font was selected
                if (font.path == state.value.selectedFontPath) {
                    readerPreferences.novelFontFamily.set("sans-serif")
                    mutableState.update { it.copy(selectedFontPath = "sans-serif") }
                }
                mutableState.update {
                    it.copy(
                        message = context.contextStringResource(TDMR.strings.settings_font_manager_deleted, font.name),
                    )
                }
            } else {
                mutableState.update {
                    it.copy(message = context.contextStringResource(TDMR.strings.settings_font_manager_delete_failed))
                }
            }
        }
    }

    fun searchGoogleFonts(query: String) {
        mutableState.update { it.copy(isSearchingGoogleFonts = true) }

        kotlinx.coroutines.MainScope().launch {
            val fonts = fontManager.searchGoogleFonts(query)
            mutableState.update {
                it.copy(
                    isSearchingGoogleFonts = false,
                    googleFonts = fonts,
                )
            }
        }
    }

    private fun loadGoogleFonts(query: String) {
        searchGoogleFonts(query)
    }

    fun downloadGoogleFont(fontFamily: String) {
        kotlinx.coroutines.MainScope().launch {
            fontManager.downloadGoogleFont(fontFamily).collect { downloadState ->
                when (downloadState) {
                    is FontDownloadState.Downloading -> {
                        mutableState.update { it.copy(downloadProgress = downloadState.progress) }
                    }
                    is FontDownloadState.Success -> {
                        mutableState.update {
                            it.copy(
                                downloadProgress = null,
                                message = context.contextStringResource(
                                    TDMR.strings.settings_font_manager_downloaded,
                                    fontFamily,
                                ),
                            )
                        }
                        loadFonts()
                    }
                    is FontDownloadState.Error -> {
                        mutableState.update {
                            it.copy(
                                downloadProgress = null,
                                message = context.contextStringResource(
                                    TDMR.strings.settings_font_manager_download_failed,
                                    downloadState.message,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    fun clearMessage() {
        mutableState.update { it.copy(message = null) }
    }
}
