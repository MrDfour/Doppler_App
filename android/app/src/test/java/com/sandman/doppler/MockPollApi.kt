package com.sandman.doppler

import com.sandman.doppler.api.DopplerLocalApi
import com.sandman.doppler.model.DopplerAlarm
import com.sandman.doppler.model.DopplerAscending
import com.sandman.doppler.model.DopplerBrightness
import com.sandman.doppler.model.DopplerColonBlink
import com.sandman.doppler.model.DopplerColor
import com.sandman.doppler.model.DopplerDayMode
import com.sandman.doppler.model.DopplerDeviceInfo
import com.sandman.doppler.model.DopplerHighToLowTransition
import com.sandman.doppler.model.DopplerLightSensor
import com.sandman.doppler.model.DopplerLowToHighTransition
import com.sandman.doppler.model.DopplerSoundPreset
import com.sandman.doppler.model.DopplerSync
import com.sandman.doppler.model.DopplerTimeMode
import com.sandman.doppler.model.DopplerTimezone
import com.sandman.doppler.model.DopplerUseColon
import com.sandman.doppler.model.DopplerUtcTime
import com.sandman.doppler.model.DopplerVolume
import com.sandman.doppler.model.DopplerWeather
import com.sandman.doppler.model.DopplerWeatherWakeupTime
import com.sandman.doppler.model.DopplerWifiStatus

/**
 * A [DopplerLocalApi] with every one of the 25 polled endpoints overridden.
 *
 * `DopplerRepository.refresh()` bails out by throwing once 4 endpoints have errored, so a
 * partial mock makes any test that calls `refresh()` fail for reasons that have nothing to
 * do with what is under test. Subclasses override only the endpoints they care about.
 */
internal open class MockPollApi : DopplerLocalApi(
    host = "192.168.1.100",
    port = 5443,
    dsn = "Doppler-12345678"
) {
    override suspend fun getDeviceInfo() =
        DopplerDeviceInfo(
            mfgrName = "Palo Alto Innovation",
            modelNum = "SandmanDopplerTest",
            firmware = "v1.0.0"
        )

    override suspend fun getWifiStatus() = DopplerWifiStatus(uptime = 3600000L, ssid = "TestNetwork", str = 75)

    override suspend fun getUtcTime() = DopplerUtcTime(hour = 14, min = 30)
    override suspend fun getTimeMode() = DopplerTimeMode(timeMode = 24)
    override suspend fun getUseColon() = DopplerUseColon(true)
    override suspend fun getColonBlink() = DopplerColonBlink(true)
    override suspend fun getVolume() = DopplerVolume(75)
    override suspend fun getSoundPreset() = DopplerSoundPreset("Flat")
    override suspend fun getAscendingVolume() = DopplerAscending(true)
    override suspend fun getLightSensor() = DopplerLightSensor(100)
    override suspend fun getDayMode() = DopplerDayMode(true)
    override suspend fun getHighDisplayColor() = DopplerColor.CYAN
    override suspend fun getLowDisplayColor() = DopplerColor.DEEP_RED
    override suspend fun getHighDisplayBrightness() = DopplerBrightness(85)
    override suspend fun getLowDisplayBrightness() = DopplerBrightness(25)
    override suspend fun getHighButtonColor() = DopplerColor.CYAN
    override suspend fun getLowButtonColor() = DopplerColor.DEEP_RED
    override suspend fun getHighButtonBrightness() = DopplerBrightness(80)
    override suspend fun getLowButtonBrightness() = DopplerBrightness(20)
    override suspend fun getSyncButtonDisplayBrightness() = DopplerSync(true)
    override suspend fun getSyncHighLowColor() = DopplerSync(false)
    override suspend fun getSyncButtonDisplayColor() = DopplerSync(true)
    override suspend fun getHighToLowTransition() = DopplerHighToLowTransition(35)
    override suspend fun getLowToHighTransition() = DopplerLowToHighTransition(45)

    override suspend fun getAlarms(): List<DopplerAlarm> = emptyList()

    override suspend fun getAlarmSounds(): List<String> = emptyList()

    override suspend fun getWeather() = DopplerWeather(
        wsonoff = true,
        location = "27.1258,-104.9118",
        wsmode = 2
    )

    override suspend fun getWeatherWakeupTime() = DopplerWeatherWakeupTime(weatherwakeuptime = "10:00")

    override suspend fun getTimezone() = DopplerTimezone(timezone = "America/Chihuahua")
}