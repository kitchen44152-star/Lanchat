package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.LanChatDatabase
import com.example.mesh.location.RadarGeoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.PI

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CompassAndMigrationTest {

    @Test
    fun `radar geo math computes accurate bearings, haversine distances, and canvas angles`() {
        val lat0 = 30.0444
        val lon0 = 31.2357

        // Due North ~111m
        val northLat = lat0 + 0.001
        val distNorth = RadarGeoMath.distanceMeters(lat0, lon0, northLat, lon0)
        assertTrue("Expected ~111m North, got $distNorth", distNorth in 105.0..118.0)
        val brgNorth = RadarGeoMath.bearingDegrees(lat0, lon0, northLat, lon0)
        assertTrue("Expected bearing ~0 deg, got $brgNorth", brgNorth < 2f || brgNorth > 358f)

        // Due East
        val eastLon = lon0 + 0.001
        val brgEast = RadarGeoMath.bearingDegrees(lat0, lon0, lat0, eastLon)
        assertTrue("Expected bearing ~90 deg, got $brgEast", brgEast in 88f..92f)

        // When phone points East (90 deg), a peer due East (90 deg) should be straight ahead (-PI/2)
        val angleAhead = RadarGeoMath.relativeCanvasAngleRad(bearingDeg = 90f, deviceAzimuthDeg = 90f)
        assertEquals(-PI / 2.0, angleAhead, 0.01)

        // Distance formatting in English and Arabic
        assertEquals("45 m", RadarGeoMath.formatDistance(45.2, isArabic = false))
        assertEquals("45 م", RadarGeoMath.formatDistance(45.2, isArabic = true))
        assertEquals("1.5 km", RadarGeoMath.formatDistance(1500.0, isArabic = false))
        assertEquals("1.5 كم", RadarGeoMath.formatDistance(1500.0, isArabic = true))
    }

    @Test
    fun `room migration 1 to 2 adds geo columns and backfills localOrderTs cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.cacheDir, "migration_v1_v2_test.db").apply { delete() }

        // Create a v1 schema manually and insert v1 rows
        val rawSqlite = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        rawSqlite.execSQL(
            "CREATE TABLE IF NOT EXISTS `peers` (" +
                "`nodeId` TEXT NOT NULL PRIMARY KEY, " +
                "`displayName` TEXT NOT NULL, " +
                "`signingPubKeyB64` TEXT NOT NULL, " +
                "`dhPubKeyB64` TEXT NOT NULL, " +
                "`trustStatus` TEXT NOT NULL, " +
                "`lastSeenAt` INTEGER NOT NULL, " +
                "`bestHopCount` INTEGER NOT NULL, " +
                "`nextHopNodeId` TEXT, " +
                "`linkQuality` INTEGER NOT NULL, " +
                "`isDirectlyConnected` INTEGER NOT NULL)"
        )
        rawSqlite.execSQL(
            "CREATE TABLE IF NOT EXISTS `messages` (" +
                "`messageId` TEXT NOT NULL PRIMARY KEY, " +
                "`conversationId` TEXT NOT NULL, " +
                "`senderNodeId` TEXT NOT NULL, " +
                "`senderName` TEXT NOT NULL, " +
                "`timestamp` INTEGER NOT NULL, " +
                "`type` TEXT NOT NULL, " +
                "`bodyText` TEXT NOT NULL, " +
                "`mediaLocalPath` TEXT, " +
                "`mediaMimeType` TEXT, " +
                "`mediaDurationMs` INTEGER NOT NULL, " +
                "`mediaTotalBytes` INTEGER NOT NULL, " +
                "`waveformSamplesJson` TEXT NOT NULL, " +
                "`microThumbBase64` TEXT, " +
                "`transferId` TEXT, " +
                "`deliveryStatus` TEXT NOT NULL, " +
                "`hopCount` INTEGER NOT NULL, " +
                "`isIncoming` INTEGER NOT NULL, " +
                "`replyToMessageId` TEXT, " +
                "`replyToPreview` TEXT, " +
                "`reactionsJson` TEXT NOT NULL)"
        )
        rawSqlite.execSQL(
            "INSERT INTO peers VALUES ('n1', 'PeerOne', 'spub', 'dpub', 'UNVERIFIED_TOFU', 1000, 1, NULL, 90, 1)"
        )
        rawSqlite.execSQL(
            "INSERT INTO messages VALUES ('m1', 'peer:n1', 'n1', 'PeerOne', 1700000123456, 'TEXT', 'Hello v1', " +
                "NULL, NULL, 0, 0, '[]', NULL, NULL, 'DELIVERED', 1, 1, NULL, NULL, '{}')"
        )
        rawSqlite.version = 1
        rawSqlite.close()

        val callback = object : SupportSQLiteOpenHelper.Callback(2) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                LanChatDatabase.MIGRATION_1_2.migrate(db)
            }
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(callback)
                .build()
        )
        val migratedDb = helper.writableDatabase
        val cursor = migratedDb.query("SELECT localOrderTs, bodyText FROM messages WHERE messageId = 'm1'")
        assertTrue(cursor.moveToFirst())
        assertEquals(1700000123456L, cursor.getLong(0))
        assertEquals("Hello v1", cursor.getString(1))
        cursor.close()

        val peerCursor = migratedDb.query("SELECT latitude, longitude FROM peers WHERE nodeId = 'n1'")
        assertTrue(peerCursor.moveToFirst())
        assertTrue(peerCursor.isNull(0))
        assertTrue(peerCursor.isNull(1))
        peerCursor.close()

        migratedDb.close()
        dbFile.delete()
    }

    @Test
    fun `radar zoom scaling and signal-only sector bearings rotate dynamically with compass`() {
        val dist = 120.0
        val rZoomOut = RadarGeoMath.normalizedDistanceRadius(dist, zoomScale = 0.6f)
        val rZoomIn = RadarGeoMath.normalizedDistanceRadius(dist, zoomScale = 3.0f)
        assertTrue("Zooming in should push a 120m marker outward ($rZoomIn > $rZoomOut)", rZoomIn > rZoomOut)

        assertTrue(RadarGeoMath.visibleRangeMeters(2.0f) < RadarGeoMath.visibleRangeMeters(1.0f))

        // Signal-only peer gets a fixed true-North bearing so rotating device azimuth shifts canvas angle
        val peerBearing = RadarGeoMath.signalOnlySectorBearingDeg("node_alpha_123", index = 0, totalCount = 2)
        val angleNorth = RadarGeoMath.relativeCanvasAngleRad(peerBearing, deviceAzimuthDeg = 0f)
        val angleEast = RadarGeoMath.relativeCanvasAngleRad(peerBearing, deviceAzimuthDeg = 90f)
        assertTrue(
            "Rotating phone 90 deg must move peer canvas angle by ~PI/2",
            kotlin.math.abs(kotlin.math.abs(angleNorth - angleEast) - (PI / 2.0)) < 0.05 ||
                kotlin.math.abs(kotlin.math.abs(angleNorth - angleEast) - (3.0 * PI / 2.0)) < 0.05
        )
    }

    @Test
    fun `ima adpcm codec encodes and decodes 40ms 16khz speech frame with bounded error`() {
        val samples = ShortArray(640) { i ->
            (kotlin.math.sin(2.0 * PI * 440.0 * i / 16000.0) * 12000.0).toInt().toShort()
        }
        val encoded = com.example.media.ImaAdpcmCodec.encode(samples)
        assertEquals(640, encoded.sampleCount)
        assertEquals(320, encoded.data.size) // 4:1 compression (1280 PCM bytes -> 320 ADPCM bytes)

        val decoded = com.example.media.ImaAdpcmCodec.decode(encoded)
        assertEquals(samples.size, decoded.size)

        var totalAbsErr = 0L
        for (i in samples.indices) {
            totalAbsErr += kotlin.math.abs(samples[i] - decoded[i])
        }
        val meanAbsErr = totalAbsErr.toDouble() / samples.size
        assertTrue("Expected low ADPCM quantization error on sine wave, got $meanAbsErr", meanAbsErr < 1500.0)
    }
}
