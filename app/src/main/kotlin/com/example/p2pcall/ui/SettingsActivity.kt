package com.example.p2pcall.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.p2pcall.R
import com.example.p2pcall.config.AppConfig
import com.example.p2pcall.databinding.ActivitySettingsBinding

/**
 * Экран настроек: ручной ввод своего TURN-сервера (coturn).
 * Значения сохраняются в SharedPreferences и подхватываются [AppConfig.iceServers].
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE)

        // Текущие значения в поля.
        binding.turnSwitch.isChecked = prefs.getBoolean("turn_enabled", false)
        binding.turnUrlInput.setText(prefs.getString("turn_url", ""))
        binding.turnUserInput.setText(prefs.getString("turn_user", ""))
        binding.turnPassInput.setText(prefs.getString("turn_pass", ""))
        binding.firebaseUrlInput.setText(prefs.getString("firebase_url", ""))
        binding.roomCodeInput.setText(prefs.getString("room_code", ""))
        binding.cloudUrlInput.setText(prefs.getString(AppConfig.CLOUD_URL_KEY, ""))
        binding.videoEconomySwitch.isChecked = prefs.getBoolean("video_economy", false)

        binding.btnSave.setOnClickListener {
            prefs.edit()
                .putBoolean("turn_enabled", binding.turnSwitch.isChecked)
                .putString("turn_url", binding.turnUrlInput.text.toString().trim())
                .putString("turn_user", binding.turnUserInput.text.toString().trim())
                .putString("turn_pass", binding.turnPassInput.text.toString().trim())
                .putString("firebase_url", binding.firebaseUrlInput.text.toString().trim())
                .putString("room_code", binding.roomCodeInput.text.toString().trim())
                .putString(AppConfig.CLOUD_URL_KEY, binding.cloudUrlInput.text.toString().trim())
                .putBoolean("video_economy", binding.videoEconomySwitch.isChecked)
                .apply()
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
