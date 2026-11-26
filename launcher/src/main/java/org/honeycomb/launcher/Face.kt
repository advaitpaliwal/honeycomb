package org.honeycomb.launcher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.time.Clock
import java.util.Date
import java.util.Locale

/** Where the faces read the time. The system clock, except in device tests, which fix it so every screenshot shows one moment. */
object FaceClock {
    @Volatile var clock: Clock = Clock.systemDefaultZone()
    fun millis(): Long = clock.millis()
}

/** The time now, updated on each minute. */
@Composable
fun now(): Long {
    var ms by remember { mutableLongStateOf(FaceClock.millis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - FaceClock.millis() % 60_000)
            ms = FaceClock.millis()
        }
    }
    return ms
}

fun dateLine(ms: Long): String = SimpleDateFormat("EEE MMM d", Locale.US).format(Date(ms)).uppercase(Locale.US)
fun clock(ms: Long): String = SimpleDateFormat("h:mm", Locale.US).format(Date(ms))
