package dev.libretv.android

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "probe_records")
data class ProbeRecord(@PrimaryKey val id: Int, val value: String)

@Dao
interface ProbeDao {
    @Insert
    fun insert(record: ProbeRecord)

    @Query("SELECT * FROM probe_records WHERE id = :id")
    fun read(id: Int): ProbeRecord?
}

@Database(entities = [ProbeRecord::class], version = 1, exportSchema = false)
abstract class ProbeDatabase : RoomDatabase() {
    abstract fun records(): ProbeDao
}
