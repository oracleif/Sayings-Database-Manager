package com.example.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DatabaseRepository(private val sayingDao: SayingDao) {
    
    val allDatabases: Flow<List<SayingDatabase>> = sayingDao.getAllDatabasesFlow()

    fun getDatabaseByIdFlow(id: Long): Flow<SayingDatabase?> {
        return sayingDao.getDatabaseByIdFlow(id)
    }

    suspend fun getDatabaseById(id: Long): SayingDatabase? = withContext(Dispatchers.IO) {
        sayingDao.getDatabaseById(id)
    }

    suspend fun getSayingByIndex(databaseId: Long, indexNumber: Int): Saying? = withContext(Dispatchers.IO) {
        sayingDao.getSayingByIndex(databaseId, indexNumber)
    }

    suspend fun deleteDatabase(databaseId: Long) = withContext(Dispatchers.IO) {
        sayingDao.deleteDatabaseById(databaseId)
    }

    suspend fun importDatabase(
        name: String,
        offset: Int,
        todayTargetIndex: Int?,
        sayingsList: List<String>
    ): Long = withContext(Dispatchers.IO) {
        sayingDao.createDatabaseWithSayings(name, offset, todayTargetIndex, sayingsList)
    }

    /**
     * Case-insensitive search inside a database.
     * Finds Sayings containing ALL the specified search sub-strings.
     */
    suspend fun searchSayings(databaseId: Long, searchTerms: List<String>): List<Saying> = withContext(Dispatchers.IO) {
        if (searchTerms.isEmpty()) return@withContext emptyList()
        val allSayings = sayingDao.getSayingsForDatabase(databaseId)
        
        allSayings.filter { saying ->
            searchTerms.all { term ->
                saying.text.contains(term, ignoreCase = true)
            }
        }
    }
}
