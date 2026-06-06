package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SayingDao {
    @Query("SELECT * FROM saying_databases ORDER BY id DESC")
    fun getAllDatabasesFlow(): Flow<List<SayingDatabase>>

    @Query("SELECT * FROM saying_databases WHERE id = :id LIMIT 1")
    suspend fun getDatabaseById(id: Long): SayingDatabase?

    @Query("SELECT * FROM saying_databases WHERE id = :id LIMIT 1")
    fun getDatabaseByIdFlow(id: Long): Flow<SayingDatabase?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDatabase(database: SayingDatabase): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSayings(sayings: List<Saying>)

    @Query("SELECT * FROM sayings WHERE databaseId = :databaseId ORDER BY indexNumber ASC")
    suspend fun getSayingsForDatabase(databaseId: Long): List<Saying>

    @Query("SELECT * FROM sayings WHERE databaseId = :databaseId AND indexNumber = :indexNumber LIMIT 1")
    suspend fun getSayingByIndex(databaseId: Long, indexNumber: Int): Saying?

    @Query("DELETE FROM saying_databases WHERE id = :id")
    suspend fun deleteDatabaseById(id: Long)

    @Transaction
    suspend fun createDatabaseWithSayings(dbName: String, offset: Int, todayTargetIndex: Int?, sayingTexts: List<String>): Long {
        val dbEntity = SayingDatabase(
            name = dbName,
            offset = offset,
            todayTargetIndex = todayTargetIndex,
            totalSayings = sayingTexts.size
        )
        val databaseId = insertDatabase(dbEntity)
        
        // Chunk inserts to prevent reaching SQLite parameter bounds (limit of 999 or 32766 parameters depending on version)
        val sayingsList = sayingTexts.mapIndexed { idx, text ->
            Saying(
                databaseId = databaseId,
                indexNumber = idx + 1, // 1-based index
                text = text
            )
        }
        
        sayingsList.chunked(200).forEach { chunk ->
            insertSayings(chunk)
        }
        
        return databaseId
    }
}
