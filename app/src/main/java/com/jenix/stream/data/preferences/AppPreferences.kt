package com.jenix.stream.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.jenix.stream.data.model.StreamConfig
import com.jenix.stream.data.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "jenix_prefs")

class AppPreferences(private val context: Context) {

    companion object {
        // Stream settings
        val RTSP_URL = stringPreferencesKey("rtsp_url")
        val YT_URL = stringPreferencesKey("yt_url")
        val YT_KEY = stringPreferencesKey("yt_key")
        val FB_URL = stringPreferencesKey("fb_url")
        val YT_ENABLED = booleanPreferencesKey("yt_enabled")
        val FB_ENABLED = booleanPreferencesKey("fb_enabled")
        val VCODEC = stringPreferencesKey("vcodec")
        val ACODEC = stringPreferencesKey("acodec")
        val BITRATE = stringPreferencesKey("bitrate")
        val RTSP_TRANSPORT = stringPreferencesKey("rtsp_transport")
        val PRESET = stringPreferencesKey("preset")
        val RESOLUTION = stringPreferencesKey("resolution")
        // User profile
        val USER_EMAIL = stringPreferencesKey("user_email")
        val USER_NAME = stringPreferencesKey("user_name")
        val USER_MOBILE = stringPreferencesKey("user_mobile")
        val USER_CITY = stringPreferencesKey("user_city")
        val USER_CREATED_AT = longPreferencesKey("user_created_at")
        val IS_LOGGED_IN = booleanPreferencesKey("is_logged_in")
    }

    val streamConfig: Flow<StreamConfig> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            StreamConfig(
                rtspUrl = prefs[RTSP_URL] ?: "",
                ytUrl = prefs[YT_URL] ?: "rtmp://a.rtmp.youtube.com/live2",
                ytKey = prefs[YT_KEY] ?: "",
                fbUrl = prefs[FB_URL] ?: "",
                ytEnabled = prefs[YT_ENABLED] ?: true,
                fbEnabled = prefs[FB_ENABLED] ?: false,
                vcodec = prefs[VCODEC] ?: "copy",
                acodec = prefs[ACODEC] ?: "aac",
                bitrate = prefs[BITRATE] ?: "1000k",
                rtspTransport = prefs[RTSP_TRANSPORT] ?: "tcp",
                preset = prefs[PRESET] ?: "veryfast",
                resolution = prefs[RESOLUTION] ?: "source"
            )
        }

    val userProfile: Flow<UserProfile?> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            val email = prefs[USER_EMAIL] ?: return@map null
            if (email.isEmpty()) return@map null
            UserProfile(
                email = email,
                name = prefs[USER_NAME] ?: "",
                mobile = prefs[USER_MOBILE] ?: "",
                city = prefs[USER_CITY] ?: "",
                createdAt = prefs[USER_CREATED_AT] ?: System.currentTimeMillis()
            )
        }

    val isLoggedIn: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[IS_LOGGED_IN] ?: false }

    suspend fun saveStreamConfig(config: StreamConfig) {
        context.dataStore.edit { prefs ->
            prefs[RTSP_URL] = config.rtspUrl
            prefs[YT_URL] = config.ytUrl
            prefs[YT_KEY] = config.ytKey
            prefs[FB_URL] = config.fbUrl
            prefs[YT_ENABLED] = config.ytEnabled
            prefs[FB_ENABLED] = config.fbEnabled
            prefs[VCODEC] = config.vcodec
            prefs[ACODEC] = config.acodec
            prefs[BITRATE] = config.bitrate
            prefs[RTSP_TRANSPORT] = config.rtspTransport
            prefs[PRESET] = config.preset
            prefs[RESOLUTION] = config.resolution
        }
    }

    suspend fun saveUserProfile(profile: UserProfile) {
        context.dataStore.edit { prefs ->
            prefs[USER_EMAIL] = profile.email
            prefs[USER_NAME] = profile.name
            prefs[USER_MOBILE] = profile.mobile
            prefs[USER_CITY] = profile.city
            prefs[USER_CREATED_AT] = profile.createdAt
            prefs[IS_LOGGED_IN] = true
        }
    }

    suspend fun logout() {
        context.dataStore.edit { it[IS_LOGGED_IN] = false }
    }

    suspend fun updateField(key: Preferences.Key<String>, value: String) {
        context.dataStore.edit { it[key] = value }
    }

    suspend fun updateField(key: Preferences.Key<Boolean>, value: Boolean) {
        context.dataStore.edit { it[key] = value }
    }
}
