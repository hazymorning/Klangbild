package io.github.hazymorning.klangbild.text

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import android.text.format.DateFormat
import androidx.annotation.RequiresApi
import androidx.core.content.edit

/**
 * Android's per-app language: from API 33 the platform keeps the choice ([LocaleManager]; it also
 * shows in the system's app settings, and a change there reaches the app as a configuration
 * change). Below API 33 the app keeps it in its own preferences.
 */
class PlatformLanguageStore(private val context: Context) : LanguageStore {
    override fun get(): AppLanguage =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            platformGet()
        } else {
            AppLanguage.of(prefs().getString(KEY, null))
        }

    override fun set(language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            platformSet(language)
        } else {
            prefs().edit { if (language.tag == null) remove(KEY) else putString(KEY, language.tag) }
        }
    }

    override fun systemLocales(): List<Locale> {
        val locales = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).systemLocales
        } else {
            Resources.getSystem().configuration.locales
        }
        return (0 until locales.size()).map { Locale.forLanguageTag(locales[it].toLanguageTag()) }
    }

    override fun clock24(locales: List<Locale>): Boolean {
        val config = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(locales.joinToString(",") { it.tag }))
        }
        return DateFormat.is24HourFormat(context.createConfigurationContext(config))
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun platformGet(): AppLanguage {
        val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
        return if (locales.isEmpty) AppLanguage.SYSTEM else AppLanguage.of(locales[0].toLanguageTag())
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun platformSet(language: AppLanguage) {
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            language.tag?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
    }

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private companion object {
        const val PREFS = "language"
        const val KEY = "app_language"
    }
}
