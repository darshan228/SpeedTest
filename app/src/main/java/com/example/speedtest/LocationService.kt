package com.example.speedtest

import android.Manifest
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.speedtest.database.AppDatabase
import com.example.speedtest.database.LocationDao
import com.example.speedtest.database.LocationData
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.GoogleApiClient
import com.google.android.gms.location.LocationListener
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.*
import java.util.concurrent.TimeUnit


class LocationService : Service(), LocationListener,
    GoogleApiClient.ConnectionCallbacks,
    GoogleApiClient.OnConnectionFailedListener {


    private lateinit var context: LocationService
    private lateinit var localBroadcastManager: LocalBroadcastManager
    private lateinit var googleApiClient: GoogleApiClient
    private lateinit var locationRequest: LocationRequest
    private var currentLocation: Location? = null
    private val mBinder: IBinder = LocalBinder()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private lateinit var locationDao: LocationDao

    private var lastUpdateTime: String = ""
    private var lastLocation: Location? = null
    private var currentDistance: Float = 0.0f
    private var currentSpeed: Double = 0.0
    private var previousSpeed: Double = 0.0
    private var maxSpeed: Double = 0.0
    private var previousAverageSpeed: Double = 0.0
    private var previousDistance: Float = 0.0f

    private val stopDriveServiceReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val resultData = intent.extras?.getBundle(KEY_RECEIVER_DATA_BUNDLE)
            resultData?.let {
                if (it.containsKey(KEY_STOP_DRIVE_SERVICE)) {
                    val action = resultData.getString(KEY_STOP_DRIVE_SERVICE)
                    if (action.checkNotEmpty() && action.equals(
                            KEY_STOP_DRIVE_SERVICE,
                            ignoreCase = true
                        )
                    ) {
                        stopMyService()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        context = this
        localBroadcastManager = LocalBroadcastManager.getInstance(this)
        locationDao = AppDatabase.getDatabase(this).locationDao()
        //Create Google Api client
        createGoogleApiClient()
        //Create location request
        createLocationRequest()
    }

    @Synchronized
    private fun createGoogleApiClient() {
        googleApiClient = GoogleApiClient.Builder(this)
            .addApi(LocationServices.API)
            .addConnectionCallbacks(this)
            .addOnConnectionFailedListener(this)
            .build()
    }

    private fun createLocationRequest() {
        locationRequest = LocationRequest().apply {
            interval = INTERVAL
            smallestDisplacement = MIN_DISTANCE_CHANGE
            fastestInterval = FASTEST_INTERVAL
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        createGoogleApiClient()
        createLocationRequest()
        googleApiClient.connect()
        return mBinder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopLocationUpdates()
        if (googleApiClient.isConnected) googleApiClient.disconnect()
        return super.onUnbind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        googleApiClient.connect()
        return START_STICKY
    }

    override fun onConnected(bundle: Bundle?) {
        if (currentLocation == null) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            currentLocation = LocationServices.FusedLocationApi.getLastLocation(
                googleApiClient
            )
            lastUpdateTime = DateFormat.getTimeInstance().format(Date())
        }
        startLocationUpdates()
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        LocationServices.FusedLocationApi.requestLocationUpdates(
            googleApiClient,
            locationRequest,
            this
        )
    }

    private fun stopLocationUpdates() {
        LocationServices.FusedLocationApi.removeLocationUpdates(googleApiClient, this)
    }

    override fun onConnectionSuspended(i: Int) {}
    override fun onConnectionFailed(p0: ConnectionResult) {}
    override fun onLocationChanged(location: Location) {
        currentLocation = location
        lastUpdateTime = DateFormat.getTimeInstance().format(Date())
        if (location != null && location.hasAccuracy() && location.speed != 0f) {
            if (lastLocation != null) {
                currentDistance = lastLocation!!.distanceTo(location)
            }
            //val speedMS = location.speed
            currentSpeed = (location.speed * 3600 / 1000).toDouble() //kmh
            // val speedMPH = (location.speed * 2.2369).toInt() //mph

            maxSpeed = if (maxSpeed > currentSpeed) maxSpeed else currentSpeed
            var pace = 0.0
            if (lastLocation != null) {
                currentDistance = lastLocation!!.distanceTo(location) // inMeters
                previousDistance += currentDistance
                previousAverageSpeed = (currentSpeed + previousAverageSpeed) / 2
                val kmstravelled =
                    (previousDistance / 1000).roundUpTwoPlaces().toDouble()
                pace = if (kmstravelled > 0) totalTimeDuration / kmstravelled / 60 else 0.0

                insetData(location, previousDistance)
                sendDriveBroadcast(
                    maxSpeed, previousAverageSpeed, previousDistance, location, totalTimeDuration
                )
                lastLocation = currentLocation
                previousSpeed = currentSpeed
            } else {
                previousAverageSpeed = (currentSpeed + previousAverageSpeed) / 2
                val kmstravelled =
                    (previousDistance / 1000).roundUpTwoPlaces().toDouble()
                pace = if (kmstravelled > 0) totalTimeDuration / kmstravelled / 60 else 0.0
                insetData(location, previousDistance)
                sendDriveBroadcast(
                    maxSpeed, previousAverageSpeed, previousDistance, location, totalTimeDuration
                )
                lastLocation = currentLocation
                previousSpeed = currentSpeed
            }
            sendDriveBroadcast(
                maxSpeed, previousAverageSpeed, previousDistance, location, totalTimeDuration
            )
        } else {
            sendDriveBroadcast(
                maxSpeed, previousAverageSpeed, previousDistance, location, totalTimeDuration
            )
        }
    }

    private fun insetData(location: Location, distance: Float) {
        val timeString = String.format(
            "%02d:%02d:%02d",
            TimeUnit.MILLISECONDS.toHours(totalTimeDuration),
            TimeUnit.MILLISECONDS.toMinutes(totalTimeDuration) -
                    TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(totalTimeDuration)), // The change is in this line
            TimeUnit.MILLISECONDS.toSeconds(totalTimeDuration) -
                    TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(totalTimeDuration))
        )
        scope.launch {
            locationDao.insert(
                LocationData(
                    distance = distance.roundUpTwoPlaces(),
                    avgSpeed = previousAverageSpeed.roundUpTwoPlaces(),
                    latitude = location.latitude,
                    longitude = location.longitude,
                    totalDuration = timeString,
                    maxSpeed = maxSpeed.roundUpTwoPlaces()
                )
            )
        }
    }

    private val totalTimeDuration: Long
        get() = System.currentTimeMillis() - MainActivity.startTime

    private fun sendDriveBroadcast(
        maxSpeed: Double,
        averageSpeed: Double,
        totalDistance: Float,
        location: Location,
        totalTime: Long
    ) {
        try {
            val bundle = Bundle()
            bundle.putDouble(KEY_MAX_SPEED, maxSpeed)
            bundle.putDouble(KEY_AVERAGE_SPEED, averageSpeed)
            bundle.putBoolean(KEY_HAS_ACCURACY, location.hasAccuracy())
            bundle.putFloat(KEY_SPEED, location.speed)
            bundle.putFloat(KEY_TOTAL_DISTANCE, totalDistance)
            bundle.putLong(KEY_TOTAL_TIME, totalTime)
            bundle.putDouble(KEY_LATITUDE, location.latitude)
            bundle.putDouble(KEY_LONGITUDE, location.longitude)
            val i = Intent()
            i.action = KEY_ACTION_UPDATE_DRIVE
            i.putExtra(KEY_RECEIVER_DATA_BUNDLE, bundle)
            localBroadcastManager.sendBroadcast(i)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun registerReceiver() {
        val intentToReceiveFilter = IntentFilter()
        intentToReceiveFilter.addAction(KEY_ACTION_STOP_DRIVE_SERVICE)
        LocalBroadcastManager.getInstance(context)
            .registerReceiver(stopDriveServiceReceiver, intentToReceiveFilter)
    }

    private fun unregisterReceiver() {
        LocalBroadcastManager.getInstance(context).unregisterReceiver(stopDriveServiceReceiver)
    }

    fun stopMyService() {
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    inner class LocalBinder : Binder() {
        fun getService(): LocationService {
            return this@LocationService
        }
    }

    companion object {
        private const val TAG = "LocationService"
        private const val INTERVAL = (1000 * 2).toLong()
        private const val MIN_DISTANCE_CHANGE: Float = 5.toFloat()
        private const val FASTEST_INTERVAL = INTERVAL / 2

        private const val KEY_ACTION_STOP_DRIVE_SERVICE = "ACTION_STOP_DRIVE_SERVICE"
        private const val KEY_STOP_DRIVE_SERVICE = "STOP_DRIVE_SERVICE"
        const val KEY_ACTION_UPDATE_DRIVE = "ACTION_UPDATE_DRIVE"
        const val KEY_RECEIVER_DATA_BUNDLE = "RECEIVER_DATA_BUNDLE"

        const val KEY_SPEED = "SPEED"
        const val KEY_LATITUDE = "LATITUDE"
        const val KEY_LONGITUDE = "LONGITUDE"
        const val KEY_MAX_SPEED = "MAX_SPEED"
        const val KEY_TOTAL_TIME = "TOTAL_TIME"
        const val KEY_AVERAGE_SPEED = "AVERAGE_SPEED"
        const val KEY_HAS_ACCURACY = "HAS_ACCURACY"
        const val KEY_TOTAL_DISTANCE = "TOTAL_DISTANCE"
    }
}