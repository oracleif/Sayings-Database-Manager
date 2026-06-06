package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "saying_databases")
data class SayingDatabase(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val offset: Int,
    val todayTargetIndex: Int? = null,
    val totalSayings: Int
)

@Entity(
    tableName = "sayings",
    foreignKeys = [
        ForeignKey(
            entity = SayingDatabase::class,
            parentColumns = ["id"],
            childColumns = ["databaseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["databaseId"]),
        Index(value = ["databaseId", "indexNumber"])
    ]
)
data class Saying(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val databaseId: Long,
    val indexNumber: Int, // 1-based index within this database
    val text: String
)
