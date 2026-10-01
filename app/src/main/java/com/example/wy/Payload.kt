package com.example.wy

import android.content.Context
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase

// data model for room
@Entity(tableName = "payloads")
data class Payload (
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type : String,
    val packageName : String,
    val timeHold : Long,
    val synced : Boolean = false
)

@Dao
interface PayloadHelper{
    @Insert
    suspend fun insert(payload: Payload)

    @Query("SELECT * FROM payloads WHERE synced = 0")
    suspend fun getUnsynced(): List<Payload>

  // if sent all data to backend, update synced to true
    @Query("UPDATE payloads SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids:List< Long>)
// cleanup data at midnight
    @Query("DELETE FROM payloads WHERE synced = 1 AND  timeHold < :before")
    suspend fun cleanUp(before: Long)
  @Query("DELETE FROM payloads")
  suspend fun deleteAll()
    @Query("DELETE FROM payloads WHERE synced = 1")
    suspend fun deleteSyncedEvents()
}
@Database(entities = [Payload::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun payloadHelper(): PayloadHelper
    companion object{
        @Volatile
        private var INSTANCE: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase {
           return INSTANCE ?: synchronized(this) {
              INSTANCE?: Room.databaseBuilder(
                  context.applicationContext,
                  AppDatabase::class.java,
                  "app_tracker.db",
              ).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                  .build()
                  .also { INSTANCE=it }

           }
        }
    }
}