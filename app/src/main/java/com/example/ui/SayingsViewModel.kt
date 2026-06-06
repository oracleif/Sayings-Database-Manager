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

package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.data.DatabaseRepository
import com.example.data.Saying
import com.example.data.SayingDatabase
import com.example.data.AnnotatedSaying
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.io.BufferedReader
import java.io.InputStreamReader

enum class ImportFormat {
    ONE_LINE_PER_SAYING,
    PARAGRAPHS_SEPARATED_BY_BLANK_LINES,
    FORTUNE_COOKIE_FORMAT,
    CSV_FORMAT,
    JSON_FORMAT
}

class SayingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: DatabaseRepository

    // List of all databases
    val allDatabases: StateFlow<List<SayingDatabase>>

    // Current selected database state
    private val _selectedDatabase = MutableStateFlow<SayingDatabase?>(null)
    val selectedDatabase: StateFlow<SayingDatabase?> = _selectedDatabase.asStateFlow()

    // Current displayed saying text, 1-based index, and source identifier text
    private val _currentSayingText = MutableStateFlow("")
    val currentSayingText: StateFlow<String> = _currentSayingText.asStateFlow()

    private val _currentSayingIndex = MutableStateFlow(0)
    val currentSayingIndex: StateFlow<Int> = _currentSayingIndex.asStateFlow()

    private val _currentSayingType = MutableStateFlow("None selected")
    val currentSayingType: StateFlow<String> = _currentSayingType.asStateFlow()

    // Search state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Saying>>(emptyList())
    val searchResults: StateFlow<List<Saying>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    // Status messages for user guidance
    private val _uiMessage = MutableStateFlow<String?>(null)
    val uiMessage: StateFlow<String?> = _uiMessage.asStateFlow()

    init {
        val database = AppDatabase.getDatabase(application)
        repository = DatabaseRepository(database.sayingDao())
        allDatabases = repository.allDatabases.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        // Ensure database template is loaded sequentially, then safely collect updates
        viewModelScope.launch {
            val firstList = repository.allDatabases.first()
            if (firstList.isEmpty()) {
                createDefaultDatabase()
            }

            repository.allDatabases.collect { dbs ->
                if (dbs.isNotEmpty()) {
                    val currentSelected = _selectedDatabase.value
                    if (currentSelected == null) {
                        // Automatically load the first database on startup
                        selectDatabase(dbs.first())
                    } else {
                        // Keep current selection in sync with database list updates
                        val updated = dbs.find { it.id == currentSelected.id }
                        if (updated != null && updated != currentSelected) {
                            _selectedDatabase.value = updated
                        }
                    }
                }
            }
        }
    }

    /**
     * Compute local days since Unix Epoch (changes exactly at local midnight).
     */
    fun getLocalDaysSinceEpoch(): Long {
        val utcMillis = System.currentTimeMillis()
        val timeZone = java.util.TimeZone.getDefault()
        val localOffset = timeZone.getOffset(utcMillis)
        val localMillis = utcMillis + localOffset
        return localMillis / (24 * 60 * 60 * 1000L)
    }

    /**
     * Set clean temporary messages for the UI.
     */
    fun setUiMessage(message: String?) {
        _uiMessage.value = message
    }

    /**
     * Trigger a daily SotD selection display.
     */
    fun showSayingOfTheDay() {
        val db = _selectedDatabase.value ?: return
        if (db.totalSayings <= 0) {
            _currentSayingText.value = "Selected Saying Database has no entries."
            _currentSayingIndex.value = 0
            _currentSayingType.value = "SotD (Empty)"
            return
        }

        val daysSinceEpoch = getLocalDaysSinceEpoch()
        val offset = db.offset
        val n = db.totalSayings

        // Formula: SotDIndex = ((daysSinceEpoch + offset) % n) + 1
        val rawModVal = (daysSinceEpoch + offset) % n
        val modVal = if (rawModVal < 0) rawModVal + n else rawModVal
        val sotdIndex = modVal.toInt() + 1

        viewModelScope.launch {
            val saying = repository.getSayingByIndex(db.id, sotdIndex)
            if (saying != null) {
                _currentSayingText.value = saying.text
                _currentSayingIndex.value = saying.indexNumber
                _currentSayingType.value = "Saying of the Day"
            } else {
                _currentSayingText.value = "Error: Saying #$sotdIndex not found in database."
                _currentSayingIndex.value = sotdIndex
                _currentSayingType.value = "SotD (Missing)"
            }
        }
    }

    /**
     * Fetch a specific saying by user-supplied index.
     */
    fun showSayingByIndex(index: Int) {
        val db = _selectedDatabase.value ?: return
        if (index < 1 || index > db.totalSayings) {
            _uiMessage.value = "Invalid index! Enter a number between 1 and ${db.totalSayings}."
            return
        }

        viewModelScope.launch {
            val saying = repository.getSayingByIndex(db.id, index)
            if (saying != null) {
                _currentSayingText.value = saying.text
                _currentSayingIndex.value = saying.indexNumber
                _currentSayingType.value = "Index Lookup"
            } else {
                _uiMessage.value = "Index #$index details could not be found."
            }
        }
    }

    /**
     * Move to the next saying in index order (1-based, wrapping around).
     */
    fun showNextSaying() {
        val db = _selectedDatabase.value ?: return
        if (db.totalSayings <= 0) return

        val currentIndex = _currentSayingIndex.value
        val nextIndex = if (currentIndex <= 0 || currentIndex >= db.totalSayings) 1 else currentIndex + 1

        showSayingByIndexSilent(nextIndex, "Next Saying")
    }

    /**
     * Move to the previous saying in index order (1-based, wrapping around).
     */
    fun showPreviousSaying() {
        val db = _selectedDatabase.value ?: return
        if (db.totalSayings <= 0) return

        val currentIndex = _currentSayingIndex.value
        val prevIndex = if (currentIndex <= 1) db.totalSayings else currentIndex - 1

        showSayingByIndexSilent(prevIndex, "Previous Saying")
    }

    private fun showSayingByIndexSilent(index: Int, typeLabel: String) {
        val db = _selectedDatabase.value ?: return
        viewModelScope.launch {
            val saying = repository.getSayingByIndex(db.id, index)
            if (saying != null) {
                _currentSayingText.value = saying.text
                _currentSayingIndex.value = saying.indexNumber
                _currentSayingType.value = typeLabel
            }
        }
    }

    /**
     * Show a random saying from the currently active database.
     */
    fun showRandomSaying() {
        val db = _selectedDatabase.value ?: return
        if (db.totalSayings <= 0) {
            _uiMessage.value = "Active Saying Database has no entries."
            return
        }
        val randomIndex = (1..db.totalSayings).random()
        viewModelScope.launch {
            val saying = repository.getSayingByIndex(db.id, randomIndex)
            if (saying != null) {
                _currentSayingText.value = saying.text
                _currentSayingIndex.value = saying.indexNumber
                _currentSayingType.value = "Random Saying"
            } else {
                _uiMessage.value = "Random Saying #$randomIndex details could not be found."
            }
        }
    }

    /**
     * Action to select/change active database.
     */
    fun selectDatabase(database: SayingDatabase) {
        _selectedDatabase.value = database
        // Reset query and results
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        _isSearching.value = false
        // Automatically load SotD for the chosen DB
        showSayingOfTheDay()
    }

    /**
     * Select database by explicit Room database ID.
     */
    fun selectDatabaseById(id: Long) {
        viewModelScope.launch {
            val db = repository.getDatabaseById(id)
            if (db != null) {
                selectDatabase(db)
            }
        }
    }

    private var searchJob: Job? = null

    /**
     * Perform the substring searches with list output.
     */
    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        val db = _selectedDatabase.value
        if (db == null) {
            _searchResults.value = emptyList()
            return
        }

        if (query.trim().isEmpty()) {
            _searchResults.value = emptyList()
            _isSearching.value = false
            searchJob?.cancel()
            return
        }

        _isSearching.value = true
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(150)
            // Split by whitespace to support retrieving sayings containing ALL terms
            val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val results = repository.searchSayings(db.id, terms)
            _searchResults.value = results
            _isSearching.value = false
        }
    }

    /**
     * If user clicks on a search result item, load it as the main view.
     */
    fun selectSaying(saying: Saying) {
        _currentSayingText.value = saying.text
        _currentSayingIndex.value = saying.indexNumber
        _currentSayingType.value = "Search Result"
    }

    /**
     * Delete an existing Saying database.
     */
    fun deleteDatabase(database: SayingDatabase) {
        viewModelScope.launch {
            repository.deleteDatabase(database.id)
            if (_selectedDatabase.value?.id == database.id) {
                _selectedDatabase.value = null
                _currentSayingText.value = ""
                _currentSayingIndex.value = 0
                _currentSayingType.value = "None selected"
            }
            _uiMessage.value = "Database '${database.name}' deleted."
        }
    }

    /**
     * Create default database on first launch.
     */
    private suspend fun createDefaultDatabase() {
        val quotes = listOf(
            "The only way to do great work is to love what you do. - Steve Jobs",
            "Do what you can, with what you have, where you are. - Theodore Roosevelt",
            "Waste no more time arguing about what a good man should be. Be one. - Marcus Aurelius",
            "It is not death that a man should fear, but he should fear never beginning to live. - Marcus Aurelius",
            "Difficulty is what wakes up the genius. - Seneca",
            "Luck is what happens when preparation meets opportunity. - Seneca",
            "The mind is everything. What you think you become. - Buddha",
            "Choose a job you love, and you will never have to work a day in your life. - Confucius",
            "We are what we repeatedly do. Excellence, then, is not an act, but a habit. - Aristotle",
            "You miss 100% of the shots you don't take. - Wayne Gretzky",
            "The journey of a thousand miles begins with one step. - Lao Tzu",
            "Knowing yourself is the beginning of all wisdom. - Aristotle",
            "Simplicity is the ultimate sophistication. - Leonardo da Vinci",
            "Believe you can and you're halfway there. - Theodore Roosevelt",
            "In the middle of difficulty lies opportunity. - Albert Einstein"
        )
        repository.importDatabase(
            name = "Default Wisdom",
            offset = 0,
            todayTargetIndex = null,
            sayingsList = quotes
        )
    }

    /**
     * Import a customized database from input stream or direct string.
     */
    fun importDatabaseContent(
        name: String,
        content: String,
        format: ImportFormat,
        customSeparator: String,
        todayTargetIndex: Int?
    ) {
        if (name.trim().isEmpty()) {
            _uiMessage.value = "Please provide an import database name."
            return
        }
        if (content.trim().isEmpty()) {
            _uiMessage.value = "Database content cannot be empty."
            return
        }

        viewModelScope.launch {
            try {
                // Parse saying strings
                val parsedList = parseSayings(content, format, customSeparator)
                if (parsedList.isEmpty()) {
                    _uiMessage.value = "Could not verify any valid sayings inside content."
                    return@launch
                }

                val totalCount = parsedList.size
                var calculatedOffset = 0

                if (todayTargetIndex != null) {
                    if (todayTargetIndex < 1 || todayTargetIndex > totalCount) {
                        _uiMessage.value = "Target index is invalid (must be between 1 and $totalCount)."
                        return@launch
                    }
                    val daysSinceEpoch = getLocalDaysSinceEpoch()
                    // Math: offset = (((targetIndex - 1 - daysSinceEpoch) % totalCount) + totalCount) % totalCount
                    val rawOffset = (todayTargetIndex - 1 - daysSinceEpoch) % totalCount
                    calculatedOffset = if (rawOffset < 0) (rawOffset + totalCount).toInt() else rawOffset.toInt()
                }

                // Call Repo creation
                val dbId = repository.importDatabase(
                    name = name.trim(),
                    offset = calculatedOffset,
                    todayTargetIndex = todayTargetIndex,
                    sayingsList = parsedList
                )

                _uiMessage.value = "Successfully imported '$name' with $totalCount sayings!"
                
                // Select the freshly imported DB
                val updatedDb = repository.getDatabaseById(dbId)
                if (updatedDb != null) {
                    selectDatabase(updatedDb)
                }
            } catch (e: Exception) {
                _uiMessage.value = "Failed to parse/import database: ${e.localizedMessage}"
            }
        }
    }

    /**
     * Import pre-parsed sayings list.
     */
    fun importParsedSayings(
        name: String,
        sayingsList: List<String>,
        todayTargetIndex: Int?
    ) {
        if (name.trim().isEmpty()) {
            _uiMessage.value = "Please provide an import database name."
            return
        }
        if (sayingsList.isEmpty()) {
            _uiMessage.value = "Sayings list cannot be empty."
            return
        }

        viewModelScope.launch {
            try {
                val totalCount = sayingsList.size
                var calculatedOffset = 0

                if (todayTargetIndex != null) {
                    if (todayTargetIndex < 1 || todayTargetIndex > totalCount) {
                        _uiMessage.value = "Target index is invalid (must be between 1 and $totalCount)."
                        return@launch
                    }
                    val daysSinceEpoch = getLocalDaysSinceEpoch()
                    val rawOffset = (todayTargetIndex - 1 - daysSinceEpoch) % totalCount
                    calculatedOffset = if (rawOffset < 0) (rawOffset + totalCount).toInt() else rawOffset.toInt()
                }

                val dbId = repository.importDatabase(
                    name = name.trim(),
                    offset = calculatedOffset,
                    todayTargetIndex = todayTargetIndex,
                    sayingsList = sayingsList
                )

                _uiMessage.value = "Successfully imported '$name' with $totalCount sayings!"
                
                val updatedDb = repository.getDatabaseById(dbId)
                if (updatedDb != null) {
                    selectDatabase(updatedDb)
                }
            } catch (e: Exception) {
                _uiMessage.value = "Failed to import database: ${e.localizedMessage}"
            }
        }
    }

    /**
     * Character-by-character CSV Parser mapping CSV contents into rows of strings.
     */
    fun parseCsv(content: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var currentRow = mutableListOf<String>()
        val currentField = StringBuilder()
        var insideQuotes = false
        var i = 0
        val length = content.length

        while (i < length) {
            val c = content[i]
            if (insideQuotes) {
                if (c == '"') {
                    if (i + 1 < length && content[i + 1] == '"') {
                        currentField.append('"')
                        i++ // skip next quote
                    } else {
                        insideQuotes = false
                    }
                } else {
                    currentField.append(c)
                }
            } else {
                when (c) {
                    '"' -> {
                        insideQuotes = true
                    }
                    ',' -> {
                        currentRow.add(currentField.toString())
                        currentField.setLength(0)
                    }
                    '\n' -> {
                        currentRow.add(currentField.toString())
                        currentField.setLength(0)
                        rows.add(currentRow)
                        currentRow = mutableListOf()
                    }
                    '\r' -> {
                        if (i + 1 < length && content[i + 1] == '\n') {
                            // wait for \n
                        } else {
                            currentRow.add(currentField.toString())
                            currentField.setLength(0)
                            rows.add(currentRow)
                            currentRow = mutableListOf()
                        }
                    }
                    else -> {
                        currentField.append(c)
                    }
                }
            }
            i++
        }
        if (currentField.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(currentField.toString())
            rows.add(currentRow)
        }
        return rows.filter { it.isNotEmpty() }
    }

    private fun extractRecords(value: Any?, results: MutableList<Map<String, String>>) {
        if (value == null || value == org.json.JSONObject.NULL) return
        when (value) {
            is org.json.JSONArray -> {
                for (idx in 0 until value.length()) {
                    extractRecords(value.opt(idx), results)
                }
            }
            is org.json.JSONObject -> {
                // Check if this object contains any nested JSONArrays that themselves contain JSONObjects
                // If so, we drill down and extract them instead of the parent object itself.
                // This is useful for {"data": [{"text": "val1"}, {"text": "val2"}]}
                val nestedResults = mutableListOf<Map<String, String>>()
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val item = value.opt(key)
                    if (item is org.json.JSONArray) {
                        for (i in 0 until item.length()) {
                            val arrayItem = item.opt(i)
                            if (arrayItem is org.json.JSONObject) {
                                val map = mutableMapOf<String, String>()
                                val arrayItemKeys = arrayItem.keys()
                                while (arrayItemKeys.hasNext()) {
                                    val k = arrayItemKeys.next()
                                    map[k] = arrayItem.optString(k, "")
                                }
                                nestedResults.add(map)
                            }
                        }
                    }
                }
                
                if (nestedResults.isNotEmpty()) {
                    results.addAll(nestedResults)
                } else {
                    // Otherwise, treat this JSONObject itself as a single record
                    val map = mutableMapOf<String, String>()
                    val objectKeys = value.keys()
                    while (objectKeys.hasNext()) {
                        val key = objectKeys.next()
                        map[key] = value.optString(key, "")
                    }
                    results.add(map)
                }
            }
            else -> {
                // Primitive value (String, Number, Boolean, etc.) mapped to a consistent structure
                val valStr = value.toString().trim()
                if (valStr.isNotEmpty()) {
                    results.add(mapOf("value" to valStr))
                }
            }
        }
    }

    /**
     * Parse JSON contents. Supports any structure (JSON streams, objects, arrays, NDJSON) parsed continuously.
     */
    fun parseJson(content: String): List<Map<String, String>> {
        val results = mutableListOf<Map<String, String>>()
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return results

        try {
            val tokener = org.json.JSONTokener(trimmed)
            while (tokener.more()) {
                val value = try {
                    tokener.nextValue()
                } catch (e: Exception) {
                    // Stop or break on formatting errors or EOF
                    break
                }
                if (value == null || value == org.json.JSONObject.NULL) {
                    continue
                }
                extractRecords(value, results)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return results
    }

    /**
     * General saying file layout format engine.
     */
    private fun parseSayings(content: String, format: ImportFormat, customSeparator: String): List<String> {
        val result = mutableListOf<String>()
        when (format) {
            ImportFormat.ONE_LINE_PER_SAYING -> {
                content.lineSequence().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        result.add(trimmed)
                    }
                }
            }
            ImportFormat.PARAGRAPHS_SEPARATED_BY_BLANK_LINES -> {
                // Split by double newline characters or more
                val blocks = content.split(Regex("\\R{2,}"))
                for (block in blocks) {
                    val trimmed = block.trim()
                    if (trimmed.isNotEmpty()) {
                        result.add(trimmed)
                    }
                }
            }
            ImportFormat.FORTUNE_COOKIE_FORMAT -> {
                val lines = content.lines()
                val currentSaying = StringBuilder()
                val separator = customSeparator.trim()
                
                for (line in lines) {
                    val trimmedLine = line.trim()
                    if (trimmedLine == separator) {
                        val sayingText = currentSaying.toString().trim()
                        if (sayingText.isNotEmpty()) {
                            result.add(sayingText)
                        }
                        currentSaying.setLength(0)
                    } else {
                        if (currentSaying.isNotEmpty()) {
                            currentSaying.append("\n")
                        }
                        currentSaying.append(line)
                    }
                }
                val sayingText = currentSaying.toString().trim()
                if (sayingText.isNotEmpty()) {
                    result.add(sayingText)
                }
            }
            ImportFormat.CSV_FORMAT, ImportFormat.JSON_FORMAT -> {
                // Handled directly via mapping dialog
            }
        }
        return result
    }

    /**
     * Create a Pin Home launcher shortcut for the given database.
     */
    fun createDatabaseLauncherShortcut(context: Context, database: SayingDatabase) {
        val hContext = context.applicationContext
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val shortcutManager = hContext.getSystemService(android.content.pm.ShortcutManager::class.java)
                if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported) {
                    val launchIntent = Intent(hContext, MainActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        putExtra("DATABASE_ID", database.id)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }

                    val shortcut = android.content.pm.ShortcutInfo.Builder(hContext, "db_shortcut_${database.id}")
                        .setShortLabel(database.name)
                        .setLongLabel("Sayings: ${database.name}")
                        .setIcon(android.graphics.drawable.Icon.createWithResource(hContext, R.mipmap.ic_launcher))
                        .setIntent(launchIntent)
                        .build()

                    val success = shortcutManager.requestPinShortcut(shortcut, null)
                    if (success) {
                        _uiMessage.value = "Requested pin shortcut for '${database.name}'."
                    } else {
                        _uiMessage.value = "Failed to request Homescreen Shortcut."
                    }
                } else {
                    _uiMessage.value = "Your current home screen launcher does not support direct pinning."
                }
            } else {
                if (ShortcutManagerCompat.isRequestPinShortcutSupported(hContext)) {
                    val launchIntent = Intent(hContext, MainActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        putExtra("DATABASE_ID", database.id)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }

                    val shortcutBuilder = ShortcutInfoCompat.Builder(hContext, "db_shortcut_${database.id}")
                        .setShortLabel(database.name)
                        .setLongLabel("Sayings: ${database.name}")
                        .setIcon(IconCompat.createWithResource(hContext, R.mipmap.ic_launcher))
                        .setIntent(launchIntent)
                        .build()

                    val success = ShortcutManagerCompat.requestPinShortcut(hContext, shortcutBuilder, null)
                    if (success) {
                        _uiMessage.value = "Requested pin shortcut for '${database.name}'."
                    } else {
                        _uiMessage.value = "Failed to request Homescreen Shortcut."
                    }
                } else {
                    _uiMessage.value = "Your current home screen launcher does not support direct pinning."
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            _uiMessage.value = "Failed to create shortcut: ${e.localizedMessage ?: "Unknown platform error"}"
        }
    }
}
