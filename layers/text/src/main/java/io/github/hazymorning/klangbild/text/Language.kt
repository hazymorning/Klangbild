package io.github.hazymorning.klangbild.text

import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The language the app shows: the system's, or one chosen in the menu. */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    ENGLISH("en"),
    GERMAN("de"),
    ;

    companion object {
        /** The choice a language tag stands for ("de-AT" is German); anything else is the system's. */
        fun of(tag: String?): AppLanguage = entries.firstOrNull { it.tag != null && tag?.substringBefore('-') == it.tag } ?: SYSTEM
    }
}

/** Where the language choice is kept, and the system's own languages. */
interface LanguageStore {
    fun get(): AppLanguage
    fun set(language: AppLanguage)

    /** The system's languages, whatever the app shows. */
    fun systemLocales(): LocaleList
}

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

    override fun systemLocales(): LocaleList =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).systemLocales
        } else {
            Resources.getSystem().configuration.locales
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

/**
 * The texts the app shows now, for the language chosen and the system's languages and clock.
 * [refresh] after anything that may change them (a new choice, a configuration change, the
 * system's 12 or 24 hour setting).
 */
class Localizer(private val context: Context, private val store: LanguageStore) {
    private val _texts = MutableStateFlow(build())
    val texts: StateFlow<Texts> = _texts.asStateFlow()

    val language: AppLanguage get() = store.get()

    fun setLanguage(language: AppLanguage) {
        if (language != store.get()) store.set(language)
        refresh()
    }

    fun refresh() {
        _texts.value = build()
    }

    private fun build(): Texts = Texts.of(context, store.get(), store.systemLocales())
}
