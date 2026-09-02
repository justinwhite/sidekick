package com.cloudcrm.app

import android.app.Application
import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth

/**
 * Application class for initializing Firebase and shared app configuration.
 */
class CloudCrmApplication : Application() {

    companion object {
        lateinit var instance: CloudCrmApplication
            private set
        
        private const val PREFS_NAME = "crm_config"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val TAG = "CloudCrmApplication"

        fun getApiKey(context: Context): String {
            return try {
                val prefs = getEncryptedPrefs(context)
                val storedKey = prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
                if (storedKey.isNotBlank()) storedKey else BuildConfig.GEMINI_API_KEY
            } catch (e: Exception) {
                Log.e(TAG, "Error reading EncryptedSharedPreferences", e)
                BuildConfig.GEMINI_API_KEY
            }
        }

        fun setApiKey(context: Context, apiKey: String) {
            try {
                val prefs = getEncryptedPrefs(context)
                prefs.edit().putString(KEY_GEMINI_API_KEY, apiKey).apply()
            } catch (e: Exception) {
                Log.e(TAG, "Error writing EncryptedSharedPreferences", e)
            }
        }

        private fun getEncryptedPrefs(context: Context): android.content.SharedPreferences {
            val masterKey = androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()

            return androidx.security.crypto.EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        fun getUserId(): String {
            return try {
                val auth = FirebaseAuth.getInstance()
                auth.currentUser?.uid ?: "local_fallback"
            } catch (e: Exception) {
                "local_fallback"
            }
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
