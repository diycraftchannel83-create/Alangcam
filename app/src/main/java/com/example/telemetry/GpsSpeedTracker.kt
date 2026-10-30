package com.example.telemetry

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.GnssStatus
import android.os.Handler
import android.os.Looper
import com.example.data.model.GpsTelemetry
import com.example.data.model.LatLngPoint
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class GpsSpeedTracker(private val context: Context) {

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val _telemetry = MutableStateFlow(GpsTelemetry())
    val telemetry: StateFlow<GpsTelemetry> = _telemetry.asStateFlow()

    private var lastLocation: Location? = null
    private var totalDistanceMeters: Double = 0.0
    private var maxRecordedSpeedKmh: Float = 0f
    // Running sum/count keeps the average over the complete trip without a rolling 300-sample window.
    private var speedSampleSumKmh: Double = 0.0
    private var speedSampleCount: Long = 0L
    private var gnssSatellitesUsed = 0
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val routeTrail = mutableListOf<LatLngPoint>()

    private var isTracking = false
    private var isSimulating = false
    private var simulationJob: Job? = null
    private val trackerScope = CoroutineScope(Dispatchers.Default)

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            // Count satellites actually used in the current position fix, not an accuracy-based estimate.
            gnssSatellitesUsed = (0 until status.satelliteCount).count { status.usedInFix(it) }
            if (!isSimulating) {
                _telemetry.value = _telemetry.value.copy(
                    satellitesCount = gnssSatellitesUsed,
                    isGpsFixed = gnssSatellitesUsed > 0
                )
            }
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (isSimulating) return
            result.lastLocation?.let { loc ->
                processLocation(loc)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startTracking() {
        if (isTracking) return
        isTracking = true

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMinUpdateDistanceMeters(0.5f)
            .build()

        try {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            locationManager.registerGnssStatusCallback(gnssStatusCallback, Handler(Looper.getMainLooper()))
        } catch (_: SecurityException) {
            // Handled via Compose permission flow; fallback to initial state
        }
    }

    fun stopTracking() {
        isTracking = false
        stopSimulation()
        try {
            fusedClient.removeLocationUpdates(locationCallback)
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
        } catch (_: Exception) {
        }
    }

    fun resetTrip() {
        // Clear every accumulator and previous fix so the first new GPS point becomes the new origin.
        lastLocation = null
        totalDistanceMeters = 0.0
        maxRecordedSpeedKmh = 0f
        speedSampleSumKmh = 0.0
        speedSampleCount = 0L
        routeTrail.clear()
        _telemetry.value = _telemetry.value.copy(
            speedKmh = 0f,
            maxSpeedKmh = 0f,
            avgSpeedKmh = 0f,
            distanceKm = 0f,
            locationHistory = emptyList(),
            gear = "--",
            rpm = 0,
            satellitesCount = gnssSatellitesUsed
        )
    }

    private fun processLocation(loc: Location) {
        // GPS speed in m/s converted to km/h, with delta distance/time fallback
        var rawSpeedKmh = if (loc.hasSpeed() && loc.speed > 0.1f) {
            loc.speed * 3.6f
        } else {
            // Fallback: calculate speed from coordinate delta and timestamp
            lastLocation?.let { prev ->
                val dist = prev.distanceTo(loc)
                val timeDeltaSec = (loc.time - prev.time) / 1000.0
                if (timeDeltaSec in 0.2..4.0 && dist > 0.5f) {
                    ((dist / timeDeltaSec) * 3.6).toFloat()
                } else {
                    0f
                }
            } ?: 0f
        }

        // Stationary noise filter: GPS drift often produces 0.5 - 1.2 km/h jitter when stopped
        if (rawSpeedKmh < 1.2f) {
            rawSpeedKmh = 0f
        }

        // Distance accumulator
        lastLocation?.let { prev ->
            val dist = prev.distanceTo(loc)
            if (dist > 1.0f && rawSpeedKmh > 0.8f) {
                totalDistanceMeters += dist
            }
        }

        // Dynamic bearing calculation
        val bearing = if (loc.hasBearing() && rawSpeedKmh > 1.2f) {
            loc.bearing
        } else if (lastLocation != null && rawSpeedKmh > 1.2f) {
            lastLocation?.bearingTo(loc) ?: _telemetry.value.bearingDegrees
        } else {
            _telemetry.value.bearingDegrees
        }

        lastLocation = loc

        if (rawSpeedKmh > maxRecordedSpeedKmh) {
            maxRecordedSpeedKmh = rawSpeedKmh
        }

        // Running average calculation
        speedSampleSumKmh += rawSpeedKmh.toDouble()
        speedSampleCount++
        val avgSpeed = if (speedSampleCount > 0L) {
            (speedSampleSumKmh / speedSampleCount).toFloat()
        } else 0f

        // Route breadcrumb update
        val point = LatLngPoint(loc.latitude, loc.longitude, rawSpeedKmh)
        if (routeTrail.isEmpty() || rawSpeedKmh > 1f) {
            routeTrail.add(point)
            if (routeTrail.size > 200) routeTrail.removeAt(0)
        }

        _telemetry.value = GpsTelemetry(
            speedKmh = rawSpeedKmh,
            maxSpeedKmh = maxRecordedSpeedKmh,
            avgSpeedKmh = avgSpeed,
            distanceKm = (totalDistanceMeters / 1000.0).toFloat(),
            latitude = loc.latitude,
            longitude = loc.longitude,
            altitudeMeters = loc.altitude,
            satellitesCount = if (gnssSatellitesUsed > 0) gnssSatellitesUsed else 8,
            bearingDegrees = bearing,
            gear = "--",
            rpm = 0,
            isGpsFixed = true,
            accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else 2.5f,
            locationHistory = routeTrail.toList()
        )
    }

    /**
     * Test & Demonstration Ride Simulation mode for indoor testing, desk testing,
     * or emulator environments where physical motorcycle movement isn't active.
     */
    fun startRideSimulation() {
        if (isSimulating) return
        isSimulating = true

        simulationJob = trackerScope.launch {
            var currentSimSpeed = 0f
            var targetSpeed = 75f
            var curLat = -7.3386
            var curLon = 108.2174
            var curAlt = 682.0
            var heading = 25f

            while (isActive && isSimulating) {
                // Smooth realistic acceleration/deceleration curve
                if (currentSimSpeed < targetSpeed) {
                    currentSimSpeed += (2.2f + (Math.random() * 2f).toFloat())
                } else {
                    currentSimSpeed -= (1.5f + (Math.random() * 1.5f).toFloat())
                }

                if (abs(currentSimSpeed - targetSpeed) < 4f) {
                    targetSpeed = (45f + (Math.random() * 85f).toFloat())
                }

                currentSimSpeed = currentSimSpeed.coerceIn(0f, 175f)

                // Advance coordinates along heading
                val rad = Math.toRadians(heading.toDouble())
                val speedMps = (currentSimSpeed / 3.6)
                val distMeters = speedMps * 0.5
                totalDistanceMeters += distMeters

                curLat += (distMeters / 111320.0) * cos(rad)
                curLon += (distMeters / (111320.0 * cos(Math.toRadians(curLat)))) * sin(rad)
                curAlt += (Math.random() - 0.48) * 0.8
                heading = (heading + (Math.random() * 4 - 2).toFloat() + 360f) % 360f

                if (currentSimSpeed > maxRecordedSpeedKmh) {
                    maxRecordedSpeedKmh = currentSimSpeed
                }
                speedSampleSumKmh += currentSimSpeed.toDouble()
                speedSampleCount++
                val pt = LatLngPoint(curLat, curLon, currentSimSpeed)
                routeTrail.add(pt)
                if (routeTrail.size > 200) routeTrail.removeAt(0)

                _telemetry.value = GpsTelemetry(
                    speedKmh = currentSimSpeed,
                    maxSpeedKmh = maxRecordedSpeedKmh,
                    avgSpeedKmh = (speedSampleSumKmh / speedSampleCount).toFloat(),
                    distanceKm = (totalDistanceMeters / 1000.0).toFloat(),
                    latitude = curLat,
                    longitude = curLon,
                    altitudeMeters = curAlt,
                    satellitesCount = 14,
                    bearingDegrees = heading,
                    gear = when {
                        currentSimSpeed < 10f -> "N"
                        currentSimSpeed < 30f -> "1"
                        currentSimSpeed < 55f -> "2"
                        currentSimSpeed < 80f -> "3"
                        currentSimSpeed < 110f -> "4"
                        currentSimSpeed < 135f -> "5"
                        else -> "6"
                    },
                    rpm = (2500 + (currentSimSpeed * 65f).toInt() % 7000),
                    isGpsFixed = true,
                    accuracyMeters = 1.8f,
                    locationHistory = routeTrail.toList()
                )

                delay(500)
            }
        }
    }

    fun stopSimulation() {
        isSimulating = false
        simulationJob?.cancel()
        simulationJob = null
    }

    fun isSimulationActive(): Boolean = isSimulating
}
