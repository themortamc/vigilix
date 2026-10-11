package com.vigilix.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface ScamReportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(report: ScamReport)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(reports: List<ScamReport>)

    @Query("SELECT * FROM scam_reports WHERE id = :id")
    suspend fun getById(id: String): ScamReport?

    @Query("SELECT * FROM scam_reports WHERE indicator = :indicator AND type = :type")
    suspend fun getByIndicator(indicator: String, type: String): ScamReport?

    @Query("SELECT * FROM scam_reports WHERE type = :type ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByType(type: String, limit: Int): List<ScamReport>

    @Query("SELECT * FROM scam_reports WHERE status = :status")
    suspend fun getByStatus(status: String): List<ScamReport>

    @Query("SELECT * FROM scam_reports ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<ScamReport>

    @Query("DELETE FROM scam_reports WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM scam_reports WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}

@Dao
interface ScamNumberDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(number: ScamNumber)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(numbers: List<ScamNumber>)

    @Query("SELECT 1 FROM scam_numbers WHERE number = :number LIMIT 1")
    suspend fun exists(number: String): Boolean

    @Query("SELECT * FROM scam_numbers LIMIT :limit")
    suspend fun getAll(limit: Int): List<ScamNumber>

    @Query("DELETE FROM scam_numbers")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM scam_numbers")
    suspend fun count(): Int
}

@Dao
interface PhishingUrlDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(url: PhishingUrl)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(urls: List<PhishingUrl>)

    @Query("SELECT 1 FROM phishing_urls WHERE url = :url LIMIT 1")
    suspend fun exists(url: String): Boolean

    @Query("SELECT * FROM phishing_urls LIMIT :limit")
    suspend fun getAll(limit: Int): List<PhishingUrl>

    @Query("DELETE FROM phishing_urls")
    suspend fun clear()
}

@Dao
interface MaliciousApkDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(apk: MaliciousApk)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(apks: List<MaliciousApk>)

    @Query("SELECT 1 FROM malicious_apks WHERE hash = :hash LIMIT 1")
    suspend fun exists(hash: String): Boolean

    @Query("SELECT * FROM malicious_apks LIMIT :limit")
    suspend fun getAll(limit: Int): List<MaliciousApk>

    @Query("DELETE FROM malicious_apks")
    suspend fun clear()
}

@Dao
interface C2IpDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(ip: C2Ip)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(ips: List<C2Ip>)

    @Query("SELECT 1 FROM c2_ips WHERE ip = :ip LIMIT 1")
    suspend fun exists(ip: String): Boolean

    @Query("SELECT * FROM c2_ips LIMIT :limit")
    suspend fun getAll(limit: Int): List<C2Ip>

    @Query("DELETE FROM c2_ips")
    suspend fun clear()
}