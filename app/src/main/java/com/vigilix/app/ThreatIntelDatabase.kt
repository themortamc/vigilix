package com.vigilix.app

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        ScamReport::class,
        ScamNumber::class,
        PhishingUrl::class,
        MaliciousApk::class,
        C2Ip::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class ThreatIntelDatabase : RoomDatabase() {
    abstract fun scamReportDao(): ScamReportDao
    abstract fun scamNumberDao(): ScamNumberDao
    abstract fun phishingUrlDao(): PhishingUrlDao
    abstract fun maliciousApkDao(): MaliciousApkDao
    abstract fun c2IpDao(): C2IpDao

    companion object {
        @Volatile private var INSTANCE: ThreatIntelDatabase? = null

        fun getInstance(context: Context): ThreatIntelDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                ThreatIntelDatabase::class.java,
                "threat_intel_db"
            ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
        }
    }
}

@TypeConverters
object Converters {
    @androidx.room.TypeConverter
    fun fromScamType(value: ScamType): String = value.name

    @androidx.room.TypeConverter
    fun toScamType(value: String): ScamType = ScamType.valueOf(value)

    @androidx.room.TypeConverter
    fun fromReportStatus(value: ReportStatus): String = value.name

    @androidx.room.TypeConverter
    fun toReportStatus(value: String): ReportStatus = ReportStatus.valueOf(value)
}