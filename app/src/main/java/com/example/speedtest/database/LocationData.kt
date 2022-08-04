package com.example.speedtest.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "Location")
data class LocationData(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "distance") val distance: String?,
    @ColumnInfo(name = "avg_speed") val avgSpeed: String?,
    @ColumnInfo(name = "latitude") val latitude: Double?,
    @ColumnInfo(name = "longitude") val longitude: Double?,
    @ColumnInfo(name = "max_speed") val maxSpeed: String?,
    @ColumnInfo(name = "total_duration") val totalDuration: String?
)