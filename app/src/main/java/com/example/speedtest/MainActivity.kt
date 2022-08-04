package com.example.speedtest

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.speedtest.LocationService.Companion.KEY_ACTION_UPDATE_DRIVE
import com.example.speedtest.LocationService.Companion.KEY_AVERAGE_SPEED
import com.example.speedtest.LocationService.Companion.KEY_HAS_ACCURACY
import com.example.speedtest.LocationService.Companion.KEY_MAX_SPEED
import com.example.speedtest.LocationService.Companion.KEY_RECEIVER_DATA_BUNDLE
import com.example.speedtest.LocationService.Companion.KEY_TOTAL_DISTANCE
import com.example.speedtest.LocationService.Companion.KEY_TOTAL_TIME
import com.example.speedtest.LocationService.LocalBinder
import com.example.speedtest.databinding.ActivityMainBinding
import java.util.*
import java.util.concurrent.TimeUnit


class MainActivity : AppCompatActivity() {

    //private lateinit var locationDao: LocationDao
    private lateinit var binding: ActivityMainBinding
    private lateinit var locationManager: LocationManager
    private var myService: LocationService? = null
    private var timer: Timer? = null

    var status = false

    private var broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            val resultData = intent.extras?.getBundle(KEY_RECEIVER_DATA_BUNDLE)
            resultData?.let {
                if (it.getBoolean(KEY_HAS_ACCURACY) && it.getDouble(KEY_AVERAGE_SPEED) != 0.0
                ) {
                    binding.tvSpeed.text =
                        "Average speed: ${it.getDouble(KEY_AVERAGE_SPEED).roundUpTwoPlaces()}"
                    binding.tvDistance.text =
                        "Total Kms: ${(it.getFloat(KEY_TOTAL_DISTANCE) / 1000).roundUpTwoPlaces()}"
                    binding.tvMaxSpeed.text =
                        "Max speed: ${it.getDouble(KEY_MAX_SPEED).roundUpTwoPlaces()}"
                    val diff = it.getLong(KEY_TOTAL_TIME)
                    val timeString = String.format(
                        "%02d:%02d:%02d",
                        TimeUnit.MILLISECONDS.toHours(diff),
                        TimeUnit.MILLISECONDS.toMinutes(diff) -
                                TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(diff)), // The change is in this line
                        TimeUnit.MILLISECONDS.toSeconds(diff) -
                                TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(diff))
                    )
                    binding.tvTime.text = "Total time: $timeString"

                    //Add data to local database
                    /*lifecycleScope.launch(Dispatchers.IO) {
                        locationDao.insert(
                            LocationData(
                                locationID = System.currentTimeMillis().toInt(),
                                distance = (it.getFloat(KEY_TOTAL_DISTANCE) / 1000).roundUpTwoPlaces(),
                                avgSpeed = it.getDouble(KEY_AVERAGE_SPEED).roundUpTwoPlaces(),
                                latitude = it.getDouble(KEY_LATITUDE),
                                longitude = it.getDouble(KEY_LONGITUDE),
                                totalDuration = timeString,
                                maxSpeed = it.getDouble(KEY_MAX_SPEED).roundUpTwoPlaces()
                            )
                        )
                    }*/
                }
            }
        }
    }

    private val sc: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as LocalBinder
            myService = binder.getService()
            status = true
        }

        override fun onServiceDisconnected(name: ComponentName) {
            status = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        //locationDao = AppDatabase.getDatabase(this).locationDao()

        binding.start.setOnClickListener {
            startCalculation()
        }

        binding.stop.setOnClickListener {
            if (status) unbindService()
            binding.start.visibility = View.VISIBLE
            binding.stop.visibility = View.GONE
        }
    }

    private fun bindService() {
        if (status) return
        val i = Intent(applicationContext, LocationService::class.java)
        bindService(i, sc, BIND_AUTO_CREATE)
        status = true
        startTime = System.currentTimeMillis()
        resetData()
        startTimer()
    }

    private fun resetData() {
        binding.tvTime.text = "Total time: 00:00:00"
        binding.tvDistance.text = "Total Kms: 0.00"
        binding.tvSpeed.text = "Average speed: 0.00"
        binding.tvMaxSpeed.text = "Max speed: 0.00"
    }

    private fun startTimer() {
        timer = Timer()
        timer?.schedule(object : TimerTask() {
            override fun run() {
                val diff = System.currentTimeMillis() - startTime
                val timeString = String.format(
                    "%02d:%02d:%02d",
                    TimeUnit.MILLISECONDS.toHours(diff),
                    TimeUnit.MILLISECONDS.toMinutes(diff) -
                            TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(diff)), // The change is in this line
                    TimeUnit.MILLISECONDS.toSeconds(diff) -
                            TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(diff))
                )
                binding.tvTime.text = "Total time: $timeString"
            }
        }, 1000, 1000)
    }

    private fun unbindService() {
        if (!status) return
        val i = Intent(applicationContext, LocationService::class.java)
        unbindService(sc)
        status = false
        stopTimer()
    }

    private fun stopTimer() {
        timer?.let { it.cancel() }
        timer = null
    }

    private fun startCalculation() {
        // Check runtime permission
        if (!checkPermission()) {
            requestPermission()
            return
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            showGPSDisabledAlertToUser()
            return
        }
        if (!status) //Here, the Location Service gets bound and the GPS Speedometer gets Active.
            bindService()
        binding.start.visibility = View.GONE
        binding.stop.visibility = View.VISIBLE
    }

    private fun checkPermission(): Boolean {
        val result = ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        val result1 = ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        return result == PackageManager.PERMISSION_GRANTED && result1 == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            1
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                startCalculation()
        }
    }

    private fun showGPSDisabledAlertToUser() {
        val alertDialogBuilder: AlertDialog.Builder = AlertDialog.Builder(this)
        alertDialogBuilder.setMessage("Enable GPS to use application")
            .setCancelable(false).setPositiveButton("Enable GPS") { dialog, id ->
                val callGPSSettingIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                startActivity(callGPSSettingIntent)
            }
        alertDialogBuilder.setNegativeButton("Cancel") { dialog, id -> dialog.cancel() }
        val alert: AlertDialog = alertDialogBuilder.create()
        alert.show()
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(broadcastReceiver, IntentFilter(KEY_ACTION_UPDATE_DRIVE))
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindService()
    }

    companion object {
        var startTime: Long = 0
    }
}