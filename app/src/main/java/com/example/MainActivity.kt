/*
 * Copyright (C) 2026 oracleif@gmail.com
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.example

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.ImportFormat
import com.example.ui.SayingsViewModel
import com.example.data.AnnotatedSaying
import com.example.ui.theme.MyApplicationTheme
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : ComponentActivity() {

    private val viewModel: SayingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle path from shortcut launch
        handleShortcutIntent(intent)

        setContent {
            MyApplicationTheme {
                SayingsAppScreen(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShortcutIntent(intent)
    }

    private fun handleShortcutIntent(intent: Intent?) {
        intent?.let {
            try {
                if (it.hasExtra("DATABASE_ID")) {
                    val dbId = it.getLongExtra("DATABASE_ID", -1L)
                    if (dbId != -1L) {
                        viewModel.selectDatabaseById(dbId)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SayingsAppScreen(viewModel: SayingsViewModel) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val clipboardManager = LocalClipboardManager.current

    // UI state states observed from state flows
    val allDatabases by viewModel.allDatabases.collectAsStateWithLifecycle()
    val selectedDb by viewModel.selectedDatabase.collectAsStateWithLifecycle()
    val currentText by viewModel.currentSayingText.collectAsStateWithLifecycle()
    val currentIndex by viewModel.currentSayingIndex.collectAsStateWithLifecycle()
    val currentType by viewModel.currentSayingType.collectAsStateWithLifecycle()

    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val uiMessage by viewModel.uiMessage.collectAsStateWithLifecycle()

    // Dialog trigger states
    var showDbSelectorDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showIndexLookupDialog by remember { mutableStateOf(false) }

    var showMappingDialog by remember { mutableStateOf(false) }
    var allCsvParsedRows by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    var allJsonParsedRows by remember { mutableStateOf<List<Map<String, String>>>(emptyList()) }
    var mappingFileType by remember { mutableStateOf("") } // CSV or JSON
    var hasHeaders by remember { mutableStateOf(true) }
    var dbName by remember { mutableStateOf("") }
    var targetSotdIndexString by remember { mutableStateOf("") }

    // Adjustable Font scale (Legibility Booster)
    var sayingFontSize by remember { mutableStateOf(22f) }
    var isSearchActiveSession by remember { mutableStateOf(false) }

    // Display temporary UI Toasts instantly when a message arrives
    LaunchedEffect(uiMessage) {
        uiMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.setUiMessage(null)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = selectedDb?.name ?: "No Active Database",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        selectedDb?.let { db ->
                            Text(
                                text = "${db.totalSayings} sayings",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showDbSelectorDialog = true },
                        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(imageVector = Icons.Default.Menu, contentDescription = "Change Database")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                )
            )
        }
    ) { innerPadding ->

        // Simple sub-composables as extension methods on ColumnScope to automatically resolve ColumnScope.weight()
        @Composable
        fun ColumnScope.SayingDisplayCard(weight: Float) {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(weight)
                    .clickable(
                        enabled = isSearchActiveSession,
                        onClick = {
                            isSearchActiveSession = false
                            focusManager.clearFocus()
                        }
                    )
                    .pointerInput(selectedDb, isSearchActiveSession) {
                        if (selectedDb != null && !isSearchActiveSession) {
                            var totalDragAmount = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { totalDragAmount = 0f },
                                onDragEnd = {
                                    if (totalDragAmount < -150f) {
                                        // Swipe right to left -> Next Saying
                                        viewModel.showNextSaying()
                                    } else if (totalDragAmount > 150f) {
                                        // Swipe left to right -> Previous Saying
                                        viewModel.showPreviousSaying()
                                    }
                                    totalDragAmount = 0f
                                },
                                onDragCancel = { totalDragAmount = 0f },
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    totalDragAmount += dragAmount
                                }
                            )
                        }
                    },
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (selectedDb != null && currentText.isNotEmpty()) {
                        // Header info
                        Text(
                            text = "$currentType • Index #$currentIndex of ${selectedDb?.totalSayings}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        // Main Saying display
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = currentText,
                                fontSize = sayingFontSize.sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = FontFamily.Serif,
                                lineHeight = (sayingFontSize * 1.45f).sp,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }

                        // Bottom action buttons inside the saying card
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(currentText))
                                    viewModel.setUiMessage("Saying copied to clipboard!")
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Copy saying",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Copy Plain Text", style = MaterialTheme.typography.labelSmall)
                            }

                            // Dynamic Font Scale Slider in-card
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 12.dp)
                            ) {
                                Text("A-", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Slider(
                                    value = sayingFontSize,
                                    onValueChange = { sayingFontSize = it },
                                    valueRange = 14f..38f,
                                    modifier = Modifier.width(100.dp)
                                )
                                Text("A+", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        // Empty Sayings placeholder state
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Welcome to Sayings Database",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Please click 'Change Database' at the top right to select or import your favorite sayings collection.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                        }
                    }
                }
            }
        }

        @Composable
        fun ButtonsBlock() {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // First row: Daily + Random
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            viewModel.showSayingOfTheDay()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(12.dp),
                        enabled = selectedDb != null
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Saying of Day", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }

                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            viewModel.showRandomSaying()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        ),
                        shape = RoundedCornerShape(12.dp),
                        enabled = selectedDb != null
                    ) {
                        Icon(imageVector = Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Random Saying", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                // Second row: Lookup + Pin Shortcut
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            if (selectedDb != null) {
                                showIndexLookupDialog = true
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary
                        ),
                        shape = RoundedCornerShape(12.dp),
                        enabled = selectedDb != null
                    ) {
                        Icon(imageVector = Icons.Default.Build, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Lookup Index", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }

                    Button(
                        onClick = {
                            selectedDb?.let { db ->
                                viewModel.createDatabaseLauncherShortcut(context, db)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        shape = RoundedCornerShape(12.dp),
                        enabled = selectedDb != null
                    ) {
                        Icon(imageVector = Icons.Default.Home, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pin Shortcut", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        @Composable
        fun ColumnScope.SearchBlock(weight: Float) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(weight),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Substring Keyword Search",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = {
                            viewModel.onSearchQueryChanged(it)
                            if (it.isNotEmpty()) {
                                isSearchActiveSession = true
                            }
                        },
                        placeholder = { Text("Search terms (contains ALL, case-insensitive)...", style = MaterialTheme.typography.bodyMedium) },
                        leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = {
                                    viewModel.onSearchQueryChanged("")
                                }) {
                                    Icon(imageVector = Icons.Default.Close, contentDescription = "Clear search")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) {
                                    isSearchActiveSession = true
                                }
                            },
                        enabled = selectedDb != null
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (searchQuery.isNotEmpty()) {
                        Text(
                            text = "${searchResults.size} matches found",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        if (searchResults.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No matching sayings found.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(searchResults) { result ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .clickable {
                                                focusManager.clearFocus()
                                                viewModel.selectSaying(result)
                                                isSearchActiveSession = false
                                            }
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "#${result.indexNumber}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.padding(end = 12.dp)
                                        )
                                        Text(
                                            text = result.text,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        // Instruction block when query is empty (only shown if search is not active)
                        if (!isSearchActiveSession) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Type search terms to filter current database.\ne.g., 'fort' will find 'fortification' or 'Roquefort'.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Stable child 0: SayingDisplayCard
            SayingDisplayCard(weight = if (isSearchActiveSession) 1.5f else 3.3f)

            // Stable child 1: ButtonsBlock container (collapses when search is active, keeps same index)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isSearchActiveSession) {
                            Modifier.height(0.dp)
                        } else {
                            Modifier.wrapContentHeight()
                        }
                    )
            ) {
                if (!isSearchActiveSession) {
                    ButtonsBlock()
                }
            }

            // Stable child 2: SearchBlock
            SearchBlock(weight = if (isSearchActiveSession) 2.5f else 1.2f)
        }
    }

    // Changing Saying Databases selection popup modal
    if (showDbSelectorDialog) {
        Dialog(onDismissRequest = { showDbSelectorDialog = false }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Saying Databases",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = { showDbSelectorDialog = false }) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Close window")
                        }
                    }

                    // Display database selector rows
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(allDatabases) { db ->
                            val isActive = selectedDb?.id == db.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        color = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .clickable {
                                        viewModel.selectDatabase(db)
                                        showDbSelectorDialog = false
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = db.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "${db.totalSayings} sayings • Offset: ${db.offset}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                // Shortcut button to request Home launcher shortcut
                                IconButton(
                                    onClick = { viewModel.createDatabaseLauncherShortcut(context, db) },
                                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.secondary)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Pin Shortcut to HomeScreen",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                // Delete db button (prevent deleting the only populated fallback db out of security)
                                if (allDatabases.size > 1) {
                                    IconButton(
                                        onClick = { viewModel.deleteDatabase(db) },
                                        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete Database",
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Divider()

                    // Trigger block to open import dialog
                    Button(
                        onClick = {
                            showDbSelectorDialog = false
                            showImportDialog = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Import New Database (.txt)")
                    }
                }
            }
        }
    }

    // Specific Index Saying Lookup Dialog
    if (showIndexLookupDialog) {
        var inputIndex by remember { mutableStateOf("") }
        Dialog(onDismissRequest = { showIndexLookupDialog = false }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth(0.9f)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Lookup Saying by Index",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "Enter a number between 1 and ${selectedDb?.totalSayings ?: 1}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = inputIndex,
                        onValueChange = { inputIndex = it },
                        label = { Text("Index Number") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showIndexLookupDialog = false }) {
                            Text("Cancel")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val idx = inputIndex.toIntOrNull()
                                if (idx != null) {
                                    viewModel.showSayingByIndex(idx)
                                    showIndexLookupDialog = false
                                } else {
                                    viewModel.setUiMessage("Please enter a valid number index!")
                                }
                            },
                            enabled = inputIndex.trim().isNotEmpty()
                        ) {
                            Text("Show Saying")
                        }
                    }
                }
            }
        }
    }

    // Import Database setup modal options
    if (showImportDialog) {
        var rawContent by remember { mutableStateOf("") }
        var selectedFormat by remember { mutableStateOf(ImportFormat.ONE_LINE_PER_SAYING) }
        var fortuneSeparator by remember { mutableStateOf("%") }

        // Local TXT, CSV, or JSON file content select loader
        val txtFilePickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri ->
            uri?.let {
                try {
                    context.contentResolver.openInputStream(it)?.use { stream ->
                        val reader = BufferedReader(InputStreamReader(stream))
                        rawContent = reader.readText()
                    }
                    if (selectedFormat == ImportFormat.CSV_FORMAT) {
                        val parsedCsv = viewModel.parseCsv(rawContent)
                        if (parsedCsv.isNotEmpty()) {
                            allCsvParsedRows = parsedCsv
                            mappingFileType = "CSV"
                            val firstRow = parsedCsv.first()
                            val looksLikeNoHeaders = firstRow.any { it.length > 50 || it.contains(". ") || it.contains("! ") }
                            hasHeaders = !looksLikeNoHeaders
                            showMappingDialog = true
                        } else {
                            viewModel.setUiMessage("No content found/parsed as CSV.")
                        }
                    } else if (selectedFormat == ImportFormat.JSON_FORMAT) {
                        val parsedJson = viewModel.parseJson(rawContent)
                        if (parsedJson.isNotEmpty()) {
                            allJsonParsedRows = parsedJson
                            mappingFileType = "JSON"
                            showMappingDialog = true
                        } else {
                            viewModel.setUiMessage("Could not parse content as JSON Array of objects.")
                        }
                    } else {
                        viewModel.setUiMessage("Import text loaded from file successfully!")
                    }
                } catch (e: Exception) {
                    viewModel.setUiMessage("Error reading file: ${e.localizedMessage}")
                }
            }
        }

        Dialog(
            onDismissRequest = { showImportDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .fillMaxHeight(0.85f)
                    .imePadding(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                LazyColumn(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Import DB Setup",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(onClick = { showImportDialog = false }) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = "Close window")
                            }
                        }
                    }

                    item {
                        OutlinedTextField(
                            value = dbName,
                            onValueChange = { dbName = it },
                            label = { Text("Database Name") },
                            placeholder = { Text("e.g., Stoic Philosophy") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    item {
                        Text(
                            text = "Import Strategy & Format",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedFormat = ImportFormat.ONE_LINE_PER_SAYING }
                            ) {
                                RadioButton(
                                    selected = selectedFormat == ImportFormat.ONE_LINE_PER_SAYING,
                                    onClick = { selectedFormat = ImportFormat.ONE_LINE_PER_SAYING }
                                )
                                Text("One line per Saying (TXT)", style = MaterialTheme.typography.bodyMedium)
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedFormat = ImportFormat.PARAGRAPHS_SEPARATED_BY_BLANK_LINES }
                            ) {
                                RadioButton(
                                    selected = selectedFormat == ImportFormat.PARAGRAPHS_SEPARATED_BY_BLANK_LINES,
                                    onClick = { selectedFormat = ImportFormat.PARAGRAPHS_SEPARATED_BY_BLANK_LINES }
                                )
                                Text("Paragraphs separated by blank lines (TXT)", style = MaterialTheme.typography.bodyMedium)
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedFormat = ImportFormat.FORTUNE_COOKIE_FORMAT }
                            ) {
                                RadioButton(
                                    selected = selectedFormat == ImportFormat.FORTUNE_COOKIE_FORMAT,
                                    onClick = { selectedFormat = ImportFormat.FORTUNE_COOKIE_FORMAT }
                                )
                                Text("Fortune cookie style (TXT)", style = MaterialTheme.typography.bodyMedium)
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedFormat = ImportFormat.CSV_FORMAT }
                            ) {
                                RadioButton(
                                    selected = selectedFormat == ImportFormat.CSV_FORMAT,
                                    onClick = { selectedFormat = ImportFormat.CSV_FORMAT }
                                )
                                Text("CSV (Comma Separated Values)", style = MaterialTheme.typography.bodyMedium)
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedFormat = ImportFormat.JSON_FORMAT }
                            ) {
                                RadioButton(
                                    selected = selectedFormat == ImportFormat.JSON_FORMAT,
                                    onClick = { selectedFormat = ImportFormat.JSON_FORMAT }
                                )
                                Text("JSON (Array of Objects)", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    if (selectedFormat == ImportFormat.FORTUNE_COOKIE_FORMAT) {
                        item {
                            OutlinedTextField(
                                value = fortuneSeparator,
                                onValueChange = { fortuneSeparator = it },
                                label = { Text("Fortune Separator character string") },
                                placeholder = { Text("default: %") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    item {
                        Divider()
                    }

                    item {
                        Column {
                            Text(
                                text = "Daily State Tuning (Optional Offset)",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Supply today's intended slot index. This mathematically configures the daily offset so that the app locks on that specific index today.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    item {
                        OutlinedTextField(
                            value = targetSotdIndexString,
                            onValueChange = { targetSotdIndexString = it },
                            label = { Text("Today's desired Saying index slot") },
                            placeholder = { Text("1-based index (e.g., 5)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    item {
                        Divider()
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Sayings Content Source",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )

                            val pickerMimeType = when (selectedFormat) {
                                ImportFormat.CSV_FORMAT -> "*/*"
                                ImportFormat.JSON_FORMAT -> "*/*"
                                else -> "text/plain"
                            }
                            val buttonText = when (selectedFormat) {
                                ImportFormat.CSV_FORMAT -> "Select CSV File"
                                ImportFormat.JSON_FORMAT -> "Select JSON File"
                                else -> "Select text File"
                            }

                            Button(
                                onClick = { txtFilePickerLauncher.launch(pickerMimeType) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(buttonText, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    item {
                        OutlinedTextField(
                            value = rawContent,
                            onValueChange = { rawContent = it },
                            placeholder = { Text("Paste sayings text content directly here, or use 'Select File' to load...", style = MaterialTheme.typography.bodyMedium) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            maxLines = 1000
                        )
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { showImportDialog = false }) {
                                Text("Cancel")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            val isCsvOrJson = selectedFormat == ImportFormat.CSV_FORMAT || selectedFormat == ImportFormat.JSON_FORMAT
                            Button(
                                onClick = {
                                    if (isCsvOrJson) {
                                        if (selectedFormat == ImportFormat.CSV_FORMAT) {
                                            val parsedCsv = viewModel.parseCsv(rawContent)
                                            if (parsedCsv.isNotEmpty()) {
                                                allCsvParsedRows = parsedCsv
                                                mappingFileType = "CSV"
                                                val firstRow = parsedCsv.first()
                                                val looksLikeNoHeaders = firstRow.any { it.length > 50 || it.contains(". ") || it.contains("! ") }
                                                hasHeaders = !looksLikeNoHeaders
                                                showMappingDialog = true
                                            } else {
                                                viewModel.setUiMessage("No content found/parsed as CSV.")
                                            }
                                        } else {
                                            val parsedJson = viewModel.parseJson(rawContent)
                                            if (parsedJson.isNotEmpty()) {
                                                allJsonParsedRows = parsedJson
                                                mappingFileType = "JSON"
                                                showMappingDialog = true
                                            } else {
                                                viewModel.setUiMessage("Could not parse content as JSON Array of objects.")
                                            }
                                        }
                                    } else {
                                        val targetIndex = targetSotdIndexString.toIntOrNull()
                                        viewModel.importDatabaseContent(
                                            name = dbName,
                                            content = rawContent,
                                            format = selectedFormat,
                                            customSeparator = fortuneSeparator,
                                            todayTargetIndex = targetIndex
                                        )
                                        showImportDialog = false
                                    }
                                },
                                enabled = dbName.trim().isNotEmpty() && rawContent.trim().isNotEmpty()
                            ) {
                                Text(if (isCsvOrJson) "Verify & Map" else "Save & load Database")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showMappingDialog) {
        var selectedSayingField by remember { mutableStateOf("") }
        var selectedAnnotationField by remember { mutableStateOf("None") }

        // Determine dynamic fields based on CSV headers choice or JSON keys
        val dynamicFields = remember(allCsvParsedRows, allJsonParsedRows, hasHeaders, mappingFileType) {
            if (mappingFileType == "CSV") {
                if (allCsvParsedRows.isEmpty()) emptyList()
                else {
                    if (hasHeaders) {
                        allCsvParsedRows.first()
                    } else {
                        val maxCols = allCsvParsedRows.maxOf { it.size }
                        (1..maxCols).map { "Column $it" }
                    }
                }
            } else {
                if (allJsonParsedRows.isEmpty()) emptyList()
                else {
                    allJsonParsedRows.first().keys.toList()
                }
            }
        }

        // Adjust selections if lists change
        LaunchedEffect(dynamicFields) {
            if (dynamicFields.isNotEmpty()) {
                if (selectedSayingField !in dynamicFields) {
                    selectedSayingField = dynamicFields.first()
                }
                if (selectedAnnotationField != "None" && selectedAnnotationField !in dynamicFields) {
                    selectedAnnotationField = "None"
                }
            } else {
                selectedSayingField = ""
                selectedAnnotationField = "None"
            }
        }

        // Dynamic preview rows
        val dynamicPreviewRows = remember(allCsvParsedRows, allJsonParsedRows, hasHeaders, dynamicFields, mappingFileType) {
            if (mappingFileType == "CSV") {
                if (allCsvParsedRows.isEmpty()) emptyList()
                else {
                    val dataStartIndex = if (hasHeaders) 1 else 0
                    val sampleRows = mutableListOf<Map<String, String>>()
                    for (idx in dataStartIndex until (dataStartIndex + 2)) {
                        if (idx < allCsvParsedRows.size) {
                            val row = allCsvParsedRows[idx]
                            val map = mutableMapOf<String, String>()
                            for (colIdx in dynamicFields.indices) {
                                val colVal = if (colIdx < row.size) row[colIdx] else ""
                                map[dynamicFields[colIdx]] = colVal
                            }
                            sampleRows.add(map)
                        }
                    }
                    sampleRows
                }
            } else {
                if (allJsonParsedRows.isEmpty()) emptyList()
                else {
                    allJsonParsedRows.take(2)
                }
            }
        }

        Dialog(
            onDismissRequest = { showMappingDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .fillMaxHeight(0.85f),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxSize()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Map $mappingFileType Fields",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = { showMappingDialog = false }) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Close mapping window")
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (mappingFileType == "CSV") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                    .clickable { hasHeaders = !hasHeaders }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = hasHeaders,
                                    onCheckedChange = { hasHeaders = it }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text("First row contains column headers", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Text("Disable to treat the first row as standard data sayings", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        Text(
                            text = "Detected Field Preview (Up to 2 sample rows)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        if (dynamicPreviewRows.isEmpty()) {
                            Text("No preview rows available.", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            dynamicPreviewRows.forEachIndexed { rowIdx, rowMap ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        text = "Sample Row #${rowIdx + 1}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    dynamicFields.forEach { field ->
                                        val valStr = rowMap[field] ?: ""
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 3.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "$field:",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.weight(0.4f)
                                            )
                                            Text(
                                                text = valStr,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.weight(0.6f),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Text(
                            text = "Database Field Configuration Mapping",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        var expandSayingField by remember { mutableStateOf(false) }
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = selectedSayingField,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Select Saying Field (Required)") },
                                trailingIcon = {
                                    IconButton(onClick = { expandSayingField = !expandSayingField }) {
                                        Icon(
                                            imageVector = if (expandSayingField) Icons.Default.KeyboardArrowUp else Icons.Default.ArrowDropDown,
                                            contentDescription = "Expand saying field dropdown"
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { expandSayingField = !expandSayingField }
                            )
                            DropdownMenu(
                                expanded = expandSayingField,
                                onDismissRequest = { expandSayingField = false },
                                modifier = Modifier.fillMaxWidth(0.85f)
                            ) {
                                dynamicFields.forEach { field ->
                                    DropdownMenuItem(
                                        text = { Text(field) },
                                        onClick = {
                                            selectedSayingField = field
                                            expandSayingField = false
                                        }
                                    )
                                }
                            }
                        }

                        var expandAnnotationField by remember { mutableStateOf(false) }
                        val annotationOptions = remember(dynamicFields) { listOf("None") + dynamicFields }
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = selectedAnnotationField,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Select Annotation Field (Optional)") },
                                trailingIcon = {
                                    IconButton(onClick = { expandAnnotationField = !expandAnnotationField }) {
                                        Icon(
                                            imageVector = if (expandAnnotationField) Icons.Default.KeyboardArrowUp else Icons.Default.ArrowDropDown,
                                            contentDescription = "Expand annotation field dropdown"
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { expandAnnotationField = !expandAnnotationField }
                            )
                            DropdownMenu(
                                expanded = expandAnnotationField,
                                onDismissRequest = { expandAnnotationField = false },
                                modifier = Modifier.fillMaxWidth(0.85f)
                            ) {
                                annotationOptions.forEach { opt ->
                                    DropdownMenuItem(
                                        text = { Text(opt) },
                                        onClick = {
                                            selectedAnnotationField = opt
                                            expandAnnotationField = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showMappingDialog = false }) {
                            Text("Back")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val parsedSayings = mutableListOf<String>()
                                if (mappingFileType == "CSV") {
                                    val sayingIndex = dynamicFields.indexOf(selectedSayingField)
                                    val annotationIndex = if (selectedAnnotationField == "None") -1 else dynamicFields.indexOf(selectedAnnotationField)
                                    val dataRows = if (hasHeaders) allCsvParsedRows.drop(1) else allCsvParsedRows

                                    for (row in dataRows) {
                                        val sayingText = if (sayingIndex >= 0 && sayingIndex < row.size) row[sayingIndex] else ""
                                        val annotationText = if (annotationIndex >= 0 && annotationIndex < row.size) row[annotationIndex] else null

                                        if (sayingText.trim().isNotEmpty()) {
                                            val annotated = AnnotatedSaying(
                                                content = sayingText.trim(),
                                                annotation = if (annotationText.isNullOrBlank()) null else annotationText.trim()
                                            )
                                            parsedSayings.add(annotated.displayFormatted)
                                        }
                                    }
                                } else if (mappingFileType == "JSON") {
                                    for (rowMap in allJsonParsedRows) {
                                        val sayingText = rowMap[selectedSayingField] ?: ""
                                        val annotationText = if (selectedAnnotationField == "None") null else rowMap[selectedAnnotationField]

                                        if (sayingText.trim().isNotEmpty()) {
                                            val annotated = AnnotatedSaying(
                                                content = sayingText.trim(),
                                                annotation = if (annotationText.isNullOrBlank()) null else annotationText.trim()
                                            )
                                            parsedSayings.add(annotated.displayFormatted)
                                        }
                                    }
                                }

                                if (parsedSayings.isNotEmpty()) {
                                    val targetIndex = targetSotdIndexString.toIntOrNull()
                                    viewModel.importParsedSayings(
                                        name = dbName,
                                        sayingsList = parsedSayings,
                                        todayTargetIndex = targetIndex
                                    )
                                    showMappingDialog = false
                                    showImportDialog = false
                                } else {
                                    viewModel.setUiMessage("No valid sayings mapped from content.")
                                }
                            },
                            enabled = selectedSayingField.isNotEmpty()
                        ) {
                            Text("Confirm Import")
                        }
                    }
                }
            }
        }
    }
}
