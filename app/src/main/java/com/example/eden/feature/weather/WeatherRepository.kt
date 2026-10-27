package com.example.eden.feature.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object WeatherRepository {
    private const val WEATHER_URL = "https://api.open-meteo.com/v1/forecast"
    private const val CACHE_EXPIRY_MS = 60 * 60 * 1000L
    private const val SNAPSHOT_CACHE_TTL_MS = 3 * 60 * 60 * 1000L

    data class WeatherResult(
        val city: String?,
        val text: String,
        val temp: Int,
        val icon: Int,
        /** 相对前一日记录的温度变化（正数为升温、负数为降温）；无有效对比时为 null */
        val tempChange: Int? = null
    )

    suspend fun getWeather(context: Context): WeatherResult? {
        // 检查缓存
        val prefs = context.getSharedPreferences("weather_prefs", Context.MODE_PRIVATE)
        val cacheTime = prefs.getLong("weather_cache_time", 0)
        if (System.currentTimeMillis() - cacheTime < CACHE_EXPIRY_MS) {
            val cachedCity = prefs.getString("weather_city", null)
            val cachedText = prefs.getString("weather_text", null)
            val cachedTemp = prefs.getInt("weather_temp", -99)
            if (cachedCity != null && cachedText != null && cachedTemp != -99) {
                val cachedChange = prefs.getInt("weather_temp_change", Int.MIN_VALUE)
                    .takeIf { it != Int.MIN_VALUE }
                return WeatherResult(cachedCity.ifEmpty { null }, cachedText, cachedTemp, 0, cachedChange)
            }
        }

        return withContext(Dispatchers.IO) {
            try {
                // 1. 获取粗略位置（城市级足够）
                val location = getLocationSync(context)
                if (location == null) {
                    Log.e("WeatherRepository", "定位失败")
                    return@withContext null
                }
                val lat = location.latitude
                val lon = location.longitude

                // 2. 请求 Open-Meteo（无需 Key）
                val weatherJson = requestWeather(lat, lon)
                if (weatherJson == null) {
                    Log.e("WeatherRepository", "Open-Meteo 请求失败")
                    return@withContext null
                }

                val currentWeather = weatherJson.optJSONObject("current_weather")
                val temp = currentWeather?.optDouble("temperature", 0.0)?.toInt() ?: 0
                val weatherCode = currentWeather?.optInt("weathercode", 0) ?: 0
                val text = getWeatherText(weatherCode)

                // 3. 反向地理编码取真实城市名（失败则留空，问候文案里就不提城市）
                val city = reverseGeocode(context, lat, lon)

                // 4. 与「昨天记录的温度」比较，得到降温/升温趋势
                val tempChange = computeTempChange(prefs, temp)

                prefs.edit()
                    .putString("weather_city", city ?: "")
                    .putString("weather_text", text)
                    .putInt("weather_temp", temp)
                    .putInt("weather_temp_change", tempChange ?: Int.MIN_VALUE)
                    .putLong("weather_cache_time", System.currentTimeMillis())
                    .apply()

                WeatherResult(city, text, temp, 0, tempChange)
            } catch (e: Exception) {
                Log.e("WeatherRepository", "天气获取异常", e)
                null
            }
        }
    }

    /**
     * 同步只读缓存：供 WorldState.snapshot 使用，绝不触发 GPS / 网络。
     * 缓存超过 3 小时返回 null，快照里只是省略天气行，降级安全。
     */
    fun cached(context: Context): WeatherResult? {
        val prefs = context.getSharedPreferences("weather_prefs", Context.MODE_PRIVATE)
        val at = prefs.getLong("weather_cache_time", 0L)
        if (at == 0L || System.currentTimeMillis() - at > SNAPSHOT_CACHE_TTL_MS) return null
        val text = prefs.getString("weather_text", null) ?: return null
        val temp = prefs.getInt("weather_temp", -99)
        if (temp == -99) return null
        val change = prefs.getInt("weather_temp_change", Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE }
        return WeatherResult(
            city = prefs.getString("weather_city", null)?.ifEmpty { null },
            text = text, temp = temp, icon = 0, tempChange = change
        )
    }

    /**
     * 反向地理编码：经纬度 → 城市名。
     *
     * 优先用系统 [Geocoder]（走设备自带/系统服务，多数国产 ROM 可用），失败再退回
     * BigDataCloud 的免 Key 接口。两者都拿不到时返回 null，调用方会退化为不含城市的文案。
     */
    private fun reverseGeocode(context: Context, lat: Double, lon: Double): String? {
        try {
            @Suppress("DEPRECATION")
            val addresses = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)
            val addr = addresses?.firstOrNull()
            val name = addr?.locality ?: addr?.subAdminArea ?: addr?.adminArea
            if (!name.isNullOrBlank()) return name.trimEnd('市')
        } catch (e: Exception) {
            Log.w("WeatherRepository", "系统 Geocoder 反查城市失败", e)
        }

        return try {
            val url = URL(
                "https://api.bigdatacloud.net/data/reverse-geocode-client" +
                    "?latitude=$lat&longitude=$lon&localityLanguage=zh"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            val obj = JSONObject(response)
            val name = obj.optString("city").ifBlank { obj.optString("locality") }
                .ifBlank { obj.optString("principalSubdivision") }
            name.takeIf { it.isNotBlank() }?.trimEnd('市')
        } catch (e: Exception) {
            Log.w("WeatherRepository", "BigDataCloud 反查城市失败", e)
            null
        }
    }

    /**
     * 与「昨天记录的温度」比较，返回今日相对昨天的温度变化；无昨天的记录则返回 null。
     *
     * 问候文案据此判断「降温 / 升温」。只在恰好隔一天时才算数——间隔过久（几天没开 App）
     * 视为无效，避免拿几天前的温度得出错误结论。顺带把今天的读数落盘供明天比较。
     */
    private fun computeTempChange(prefs: SharedPreferences, todayTemp: Int): Int? {
        val lastTemp = prefs.getInt("weather_temp_value", Int.MIN_VALUE)
        val lastDay = prefs.getLong("weather_temp_day", Long.MIN_VALUE)

        prefs.edit()
            .putInt("weather_temp_value", todayTemp)
            .putLong("weather_temp_day", dayStartMillis(0))
            .apply()

        if (lastTemp == Int.MIN_VALUE) return null
        if (lastDay != dayStartMillis(-1)) return null
        return todayTemp - lastTemp
    }

    /** 今天（offsetDays=0）或昨天（-1）零点的时间戳 */
    private fun dayStartMillis(offsetDays: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, offsetDays)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /**
     * 同步获取粗略位置（城市级足够），最多等待 10 秒。
     * 只用 NETWORK_PROVIDER：GPS 属于精确定位，只申请 COARSE 权限时拿不到结果。
     */
    @SuppressLint("MissingPermission")
    private fun getLocationSync(context: Context): Location? {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("WeatherRepository", "没有定位权限")
            return null
        }

        // 先尝试缓存
        val lastKnown = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        if (lastKnown != null && System.currentTimeMillis() - lastKnown.time < 10 * 60 * 1000) {
            return lastKnown
        }

        // 请求单次更新（阻塞等待）
        val latch = CountDownLatch(1)
        var result: Location? = null
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                result = location
                latch.countDown()
            }
            @Suppress("DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                @Suppress("DEPRECATION")
                locationManager.requestSingleUpdate(
                    LocationManager.NETWORK_PROVIDER,
                    listener,
                    Looper.getMainLooper()
                )
                latch.await(10, TimeUnit.SECONDS)
            }
        } catch (e: Exception) {
            Log.e("WeatherRepository", "定位异常", e)
        } finally {
            locationManager.removeUpdates(listener)
        }

        return result
    }

    /**
     * 请求 Open-Meteo 天气数据
     */
    private fun requestWeather(lat: Double, lon: Double): JSONObject? {
        return try {
            val url = URL("$WEATHER_URL?latitude=$lat&longitude=$lon&current_weather=true")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val response = reader.readText()
            reader.close()
            JSONObject(response)
        } catch (e: Exception) {
            Log.e("WeatherRepository", "天气请求失败", e)
            null
        }
    }

    /**
     * WMO 天气码 → 中文描述
     */
    private fun getWeatherText(code: Int): String {
        return when (code) {
            0, 1 -> "晴"
            2 -> "多云"
            3 -> "阴"
            45, 48 -> "雾"
            51, 53, 55 -> "小雨"
            61, 63, 65 -> "雨"
            71, 73, 75 -> "雪"
            80, 81, 82 -> "阵雨"
            95, 96, 99 -> "雷雨"
            else -> "未知"
        }
    }
}