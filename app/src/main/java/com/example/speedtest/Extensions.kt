package com.example.speedtest

fun Float.roundUpTwoPlaces(): String {
    return String.format("%.2f", this)
}

fun Double.roundUpTwoPlaces(): String {
    return String.format("%.2f", this)
}

fun String?.checkNotEmpty(): Boolean {
    return this != null && !this.equals("", ignoreCase = true)
            && !this.equals("null", ignoreCase = true) && !this.equals("NaN", ignoreCase = true)
}