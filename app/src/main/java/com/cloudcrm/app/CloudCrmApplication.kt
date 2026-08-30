package com.cloudcrm.app

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.FirebaseApp

/**
 * Application class for initializing Firebase and shared app configuration.
 */
class CloudCrmApplication : Application() {

    companion object {
        lateinit var instance: CloudCrmApplication
            private set
        
        private const val PREFS_NAME = "crm_config"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"

        fun getApiKey(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val storedKey = prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
            if (storedKey.isNotBlank()) return storedKey
            return BuildConfig.GEMINI_API_KEY
        }

        fun setApiKey(context: Context, apiKey: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_GEMINI_API_KEY, apiKey).apply()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            FirebaseApp.initializeApp(this)
        } catch (e: Exception) {
            // Handled for unit tests or environments without google-services.json
        }
    }
}
