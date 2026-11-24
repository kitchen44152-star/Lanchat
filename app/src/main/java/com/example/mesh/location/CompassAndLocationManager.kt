package com.example.mesh.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class OfflineGeoPoint(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float = 0f,
    val timestampMs: Long = System.currentTimeMillis()
)

data class CompassHeadingState(
    val azimuthDegrees: Float = 0f,
    val hasCompassSensor: Boolean = false
)

object RadarGeoMath {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val a = sin(dPhi / 2.0) * sin(dPhi / 2.0) +
            cos(phi1) * cos(phi2) * sin(dLambda / 2.0) * sin(dLambda / 2.0)
        val c = 2.0 * atan2(sqrt(a), sqrt((1.0 - a).coerceAtLeast(0.0)))
        return EARTH_RADIUS_M * c
    }

    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLambda = Math.toRadians(lon2 - lon1)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        val deg = Math.toDegrees(atan2(y, x))
        return (((deg % 360.0) + 360.0) % 360.0).toFloat()
    }

    /**
     * Converts an absolute geographic bearing [0, 360) and device azimuth [0, 360)
     * into Canvas polar angle in radians where -PI/2 is straight ahead (top of screen).
     */
    fun relativeCanvasAngleRad(bearingDeg: Float, deviceAzimuthDeg: Float): Double {
        val relativeDeg = (((bearingDeg - deviceAzimuthDeg) % 360f) + 360f) % 360f
        return Math.toRadians(relativeDeg.toDouble()) - (PI / 2.0)
    }

    /**
     * Maps distance in meters to normalized radar radius fraction in [0.20f, 0.94f]
     * scaled by current radar zoom level (base max range ~2000m at 1x zoom).
     */
    fun normalizedDistanceRadius(distanceMeters: Double, zoomScale: Float = 1f): Float {
        if (distanceMeters <= 2.0) return 0.16f
        val maxRange = (2000.0 / zoomScale.coerceIn(0.5f, 5.0f)).coerceIn(60.0, 4000.0)
        val clamped = distanceMeters.coerceAtMost(maxRange)
        val logScaled = (kotlin.math.ln(clamped.coerceAtLeast(2.0)) / kotlin.math.ln(maxRange)).toFloat()
        return (0.16f + logScaled * 0.78f).coerceIn(0.16f, 0.94f)
    }

    /**
     * Returns the visible outer-ring range in meters for the current zoom scale.
     */
    fun visibleRangeMeters(zoomScale: Float): Double {
        return (2000.0 / zoomScale.coerceIn(0.5f, 5.0f)).coerceIn(60.0, 4000.0)
    }

    /**
     * Assigns a stable, true-North-anchored azimuth bearing in [0, 360) for a signal-only peer
     * without GPS coordinates so its marker still rotates smoothly as the user turns their phone.
     */
    fun signalOnlySectorBearingDeg(nodeId: String, index: Int, totalCount: Int): Float {
        val step = 360f / totalCount.coerceAtLeast(1)
        val hashOffset = ((nodeId.hashCode().toLong() and 0x7FFFFFFFL) % 45L).toFloat()
        val raw = (index * step) + hashOffset + 20f
        return ((raw % 360f) + 360f) % 360f
    }

    fun cardinalIndex8(bearingDeg: Float): Int {
        val normalized = (((bearingDeg % 360f) + 360f) % 360f)
        return ((normalized + 22.5f) / 45f).toInt() % 8
    }

    fun formatDistance(distanceMeters: Double, isArabic: Boolean): String {
        return if (distanceMeters < 1000.0) {
            val m = distanceMeters.roundToInt().coerceAtLeast(1)
            if (isArabic) "$m م" else "$m m"
        } else {
            val km = distanceMeters / 1000.0
            val formatted = String.format(Locale.US, "%.1f", km)
            if (isArabic) "$formatted كم" else "$formatted km"
        }
    }
}

class CompassHeadingTracker(private val context: Context) {

    fun observeHeading(): Flow<CompassHeadingState> = callbackFlow {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        if (sensorManager == null || (rotationSensor == null && (accelSensor == null || magSensor == null))) {
            trySend(CompassHeadingState(azimuthDegrees = 0f, hasCompassSensor = false))
            awaitClose { }
            return@callbackFlow
        }

        trySend(CompassHeadingState(azimuthDegrees = 0f, hasCompassSensor = true))

        val rotMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        val accelReading = FloatArray(3)
        val magReading = FloatArray(3)
        var hasAccel = false
        var hasMag = false
        var smoothedAzimuth = 0f
        var initialized = false

        fun publishAzimuth(rawDeg: Float) {
            val normalized = ((rawDeg % 360f) + 360f) % 360f
            if (!initialized) {
                smoothedAzimuth = normalized
                initialized = true
                trySend(CompassHeadingState(smoothedAzimuth, true))
                return
            }
            var delta = normalized - smoothedAzimuth
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            if (kotlin.math.abs(delta) < 1.2f) return
            smoothedAzimuth = (((smoothedAzimuth + delta * 0.28f) % 360f) + 360f) % 360f
            trySend(CompassHeadingState(smoothedAzimuth, true))
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
                        SensorManager.getOrientation(rotMatrix, orientation)
                        publishAzimuth(Math.toDegrees(orientation[0].toDouble()).toFloat())
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        System.arraycopy(event.values, 0, accelReading, 0, 3)
                        hasAccel = true
                        if (hasMag && SensorManager.getRotationMatrix(rotMatrix, null, accelReading, magReading)) {
                            SensorManager.getOrientation(rotMatrix, orientation)
                            publishAzimuth(Math.toDegrees(orientation[0].toDouble()).toFloat())
                        }
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        System.arraycopy(event.values, 0, magReading, 0, 3)
                        hasMag = true
                        if (hasAccel && SensorManager.getRotationMatrix(rotMatrix, null, accelReading, magReading)) {
                            SensorManager.getOrientation(rotMatrix, orientation)
                            publishAzimuth(Math.toDegrees(orientation[0].toDouble()).toFloat())
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        if (rotationSensor != null) {
            sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            sensorManager.registerListener(listener, accelSensor, SensorManager.SENSOR_DELAY_UI)
            sensorManager.registerListener(listener, magSensor, SensorManager.SENSOR_DELAY_UI)
        }

        awaitClose {
            sensorManager.unregisterListener(listener)
        }
    }
}

enum class LocationFixStatus {
    OFF,
    SEARCHING,
    LOCKED_CACHED,
    LOCKED_GPS
}

class OfflineLocationProvider(private val context: Context) {

    private val locationManager: LocationManager? by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }

    private val _currentLocation = MutableStateFlow<OfflineGeoPoint?>(null)
    val currentLocation: StateFlow<OfflineGeoPoint?> = _currentLocation.asStateFlow()

    private val _fixStatus = MutableStateFlow(LocationFixStatus.OFF)
    val fixStatus: StateFlow<LocationFixStatus> = _fixStatus.asStateFlow()

    @Volatile
    private var isListening = false
    private var activeCancelSignal: android.os.CancellationSignal? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            publishLocation(location, isLiveFix = true)
        }

        @Deprecated("Deprecated in Android API")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    fun startIfPermitted() {
        if (isListening || !hasLocationPermission()) return
        val mgr = locationManager ?: return
        isListening = true
        _fixStatus.value = LocationFixStatus.SEARCHING

        runCatching {
            val candidateProviders = buildList {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    add(LocationManager.FUSED_PROVIDER)
                }
                add(LocationManager.GPS_PROVIDER)
                add(LocationManager.NETWORK_PROVIDER)
                add(LocationManager.PASSIVE_PROVIDER)
            }
            var bestLast: Location? = null
            for (provider in candidateProviders) {
                if (mgr.allProviders.contains(provider)) {
                    val last = runCatching { mgr.getLastKnownLocation(provider) }.getOrNull()
                    if (last != null && (bestLast == null || last.time > bestLast.time)) {
                        bestLast = last
                    }
                }
            }
            bestLast?.let { publishLocation(it, isLiveFix = false) }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val cancel = android.os.CancellationSignal()
                activeCancelSignal = cancel
                val mainExecutor = ContextCompat.getMainExecutor(context)
                for (provider in candidateProviders.filter { it != LocationManager.PASSIVE_PROVIDER }) {
                    if (mgr.allProviders.contains(provider) && runCatching { mgr.isProviderEnabled(provider) }.getOrDefault(false)) {
                        runCatching {
                            mgr.getCurrentLocation(provider, cancel, mainExecutor) { loc ->
                                if (loc != null && isListening) {
                                    publishLocation(loc, isLiveFix = true)
                                }
                            }
                        }
                    }
                }
            }

            if (mgr.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                mgr.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    2_500L,
                    2f,
                    locationListener,
                    Looper.getMainLooper()
                )
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                mgr.allProviders.contains(LocationManager.FUSED_PROVIDER)
            ) {
                runCatching {
                    mgr.requestLocationUpdates(
                        LocationManager.FUSED_PROVIDER,
                        3_000L,
                        2f,
                        locationListener,
                        Looper.getMainLooper()
                    )
                }
            }
            if (mgr.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                mgr.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    5_000L,
                    5f,
                    locationListener,
                    Looper.getMainLooper()
                )
            }
        }.onFailure {
            isListening = false
            _fixStatus.value = LocationFixStatus.OFF
        }
    }

    fun stop() {
        runCatching { activeCancelSignal?.cancel() }
        activeCancelSignal = null
        if (!isListening) {
            _currentLocation.value = null
            _fixStatus.value = LocationFixStatus.OFF
            return
        }
        isListening = false
        runCatching {
            locationManager?.removeUpdates(locationListener)
        }
        _currentLocation.value = null
        _fixStatus.value = LocationFixStatus.OFF
    }

    private fun publishLocation(location: Location, isLiveFix: Boolean) {
        val current = _currentLocation.value
        val next = OfflineGeoPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy,
            timestampMs = System.currentTimeMillis()
        )
        // Never overwrite a fresh live fix with a stale cached fix
        if (current != null && !isLiveFix && _fixStatus.value == LocationFixStatus.LOCKED_GPS) {
            return
        }
        _currentLocation.value = next
        _fixStatus.value = if (isLiveFix || location.provider == LocationManager.GPS_PROVIDER) {
            LocationFixStatus.LOCKED_GPS
        } else {
            LocationFixStatus.LOCKED_CACHED
        }
    }
}
