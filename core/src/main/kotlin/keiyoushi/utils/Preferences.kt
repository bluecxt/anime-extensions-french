package keiyoushi.utils

import android.app.Application
import android.content.SharedPreferences
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import keiyoushi.core.R
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Returns the [SharedPreferences] associated with current source id
 */
inline fun AnimeHttpSource.getPreferences(
    migration: SharedPreferences.() -> Unit = { },
): SharedPreferences = getPreferences(id).also(migration)

/**
 * Lazily returns the [SharedPreferences] associated with current source id
 */
inline fun AnimeHttpSource.getPreferencesLazy(
    crossinline migration: SharedPreferences.() -> Unit = { },
) = lazy { getPreferences(migration) }

/**
 * Returns the [SharedPreferences] associated with passed source id
 */
@Suppress("NOTHING_TO_INLINE")
inline fun getPreferences(sourceId: Long): SharedPreferences = Injekt.get<Application>().getSharedPreferences("source_$sourceId", 0x0000)

// From https://al-e-shevelev.medium.com/mutable-lazy-in-kotlin-14233bed116d
class LazyMutable<T>(
    val initializer: () -> T,
) : ReadWriteProperty<Any?, T> {
    private object UninitializedValue

    @Volatile private var propValue: Any? = UninitializedValue

    @Suppress("UNCHECKED_CAST")
    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        val localValue = propValue

        if (localValue != UninitializedValue) {
            return localValue as T
        }

        return synchronized(this) {
            val localValue2 = propValue

            if (localValue2 != UninitializedValue) {
                localValue2 as T
            } else {
                val initializedValue = initializer()
                propValue = initializedValue
                initializedValue
            }
        }
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        synchronized(this) {
            propValue = value
        }
    }
}

/**
 * A [ReadWriteProperty] backed by [SharedPreferences].
 *
 * Encapsulates dedicated read/write closures captured at initialization to bypass
 * runtime type inspection on property access.
 */
class PreferenceDelegate<T>(
    private val getter: () -> T,
    private val setter: (T) -> Unit,
) : ReadWriteProperty<Any?, T> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): T = getter()
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = setter(value)
}

/**
 * Creates a type-safe [SharedPreferences] property delegate.
 *
 * Uses reified type parameters to inline type checks at compile time, eliminating
 * runtime overhead and resolving Type Erasure ambiguities for nullable defaults.
 * Assigning `null` removes the key from preferences.
 *
 * Supported types: [String], [Int], [Long], [Float], [Boolean], [Set]<[String]>, and their nullable variants.
 *
 * ```kotlin
 * var serverUrl: String by preferences.delegate("server_url", "[https://example.com](https://example.com)")
 * var customToken: String? by preferences.delegate("auth_token", null as String?)
 * ```
 *
 * @param key Preference key identifier.
 * @param default Fallback value returned if the key is not set.
 * @throws IllegalArgumentException If [T] is not supported by [SharedPreferences].
 */
@Suppress("UNCHECKED_CAST")
inline fun <reified T> SharedPreferences.delegate(
    key: String,
    default: T,
): ReadWriteProperty<Any?, T> = when (T::class) {
    String::class -> PreferenceDelegate(
        getter = { getString(key, default as String?) as T },
        setter = { value -> edit().apply { if (value == null) remove(key) else putString(key, value as String) }.apply() },
    )

    Int::class -> PreferenceDelegate(
        getter = { getInt(key, default as Int) as T },
        setter = { value -> edit().putInt(key, value as Int).apply() },
    )

    Long::class -> PreferenceDelegate(
        getter = { getLong(key, default as Long) as T },
        setter = { value -> edit().putLong(key, value as Long).apply() },
    )

    Float::class -> PreferenceDelegate(
        getter = { getFloat(key, default as Float) as T },
        setter = { value -> edit().putFloat(key, value as Float).apply() },
    )

    Boolean::class -> PreferenceDelegate(
        getter = { getBoolean(key, default as Boolean) as T },
        setter = { value -> edit().putBoolean(key, value as Boolean).apply() },
    )

    Set::class -> PreferenceDelegate(
        getter = { getStringSet(key, default as Set<String>?) as T },
        setter = { value -> edit().apply { if (value == null) remove(key) else putStringSet(key, value as Set<String>) }.apply() },
    )

    else -> throw IllegalArgumentException("Unsupported type: ${T::class.java.name}")
}

/**
 * Get an [EditTextPreference] preference
 *
 * @param key Preference key
 * @param default Default value for preference
 * @param title Preference title
 * @param summary Preference summary
 * @param getSummary Lambda to get summary based on text value
 * @param dialogMessage Preference dialog message
 * @param inputType Keyboard input type
 * @param validate Validate preference value before applying
 * @param validationMessage Validation message if text isn't valid, based on text value
 * @param restartRequired Show restart required toast on preference change
 * @param onChange Run block on changed listener for validation, must return *true/false*
 * to determine if the preference change should be accepted
 * @param onComplete Run block on completion with text value as parameter
 */
fun PreferenceScreen.getEditTextPreference(
    key: String,
    default: String,
    title: String,
    summary: String,
    getSummary: (String) -> String = { summary },
    dialogMessage: String? = null,
    inputType: Int? = null,
    validate: ((String) -> Boolean)? = null,
    validationMessage: ((String) -> String)? = null,
    sanitize: ((String) -> String)? = null,
    restartRequired: Boolean = false,
    enabled: Boolean = true,
    onChange: (Preference, String) -> Boolean = { _, _ -> true },
    onComplete: (String) -> Unit = {},
): EditTextPreference = EditTextPreference(context).apply {
    this.key = key
    this.title = title
    this.summary = summary
    this.setDefaultValue(default)
    this.dialogTitle = title
    this.dialogMessage = dialogMessage
    this.setEnabled(enabled)

    setOnBindEditTextListener { editText ->
        if (inputType != null) {
            editText.inputType = inputType
        }

        (editText.tag as? TextWatcher)?.let(editText::removeTextChangedListener)

        if (validate != null) {
            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                override fun afterTextChanged(editable: Editable?) {
                    if (editable == null) return

                    val rawText = editable.toString()

                    val textToValidate = sanitize?.invoke(rawText) ?: rawText
                    val isValid = textToValidate.isEmpty() || validate(textToValidate)

                    editText.error = if (!isValid) validationMessage?.invoke(textToValidate) else null
                    editText.rootView.findViewById<Button>(android.R.id.button1)
                        ?.isEnabled = editText.error == null
                }
            }
            editText.addTextChangedListener(watcher)
            editText.tag = watcher
        } else {
            editText.tag = null
        }
    }

    setOnPreferenceChangeListener { pref, newValue ->
        val rawValue = newValue as String
        val cleanValue = sanitize?.invoke(rawValue) ?: rawValue

        val isValid = onChange(pref, cleanValue)
        if (!isValid) return@setOnPreferenceChangeListener false

        if (restartRequired) {
            Toast.makeText(context, context.getString(R.string.pref_restart_message), Toast.LENGTH_LONG).show()
        }

        this.summary = getSummary(cleanValue)
        onComplete(cleanValue)

        if (cleanValue != rawValue) {
            this.text = cleanValue
            false
        } else {
            true
        }
    }
}

/**
 * Add an [EditTextPreference] preference to the screen
 */
fun PreferenceScreen.addEditTextPreference(
    key: String,
    default: String,
    title: String,
    summary: String,
    getSummary: (String) -> String = { summary },
    dialogMessage: String? = null,
    inputType: Int? = null,
    validate: ((String) -> Boolean)? = null,
    validationMessage: ((String) -> String)? = null,
    sanitize: ((String) -> String)? = null,
    restartRequired: Boolean = false,
    enabled: Boolean = true,
    onChange: (Preference, String) -> Boolean = { _, _ -> true },
    onComplete: (String) -> Unit = {},
) {
    getEditTextPreference(
        key = key,
        default = default,
        title = title,
        summary = summary,
        getSummary = getSummary,
        dialogMessage = dialogMessage,
        inputType = inputType,
        validate = validate,
        validationMessage = validationMessage,
        sanitize = sanitize,
        restartRequired = restartRequired,
        enabled = enabled,
        onChange = onChange,
        onComplete = onComplete,
    ).also(::addPreference)
}

/**
 * Get a [ListPreference] preference
 */
fun PreferenceScreen.getListPreference(
    key: String,
    default: String,
    title: String,
    summary: String,
    entries: Array<String>,
    entryValues: Array<String>,
    restartRequired: Boolean = false,
    enabled: Boolean = true,
    onChange: (Preference, String) -> Boolean = { _, _ -> true },
    onComplete: (String) -> Unit = {},
): ListPreference = ListPreference(context).apply {
    this.key = key
    this.title = title
    this.summary = summary
    this.entries = entries
    this.entryValues = entryValues

    setDefaultValue(default)
    setEnabled(enabled)
    setOnPreferenceChangeListener { pref, newValue ->
        val value = newValue as String
        val isValid = onChange(pref, value)
        if (isValid) {
            if (restartRequired) {
                Toast.makeText(context, context.getString(R.string.pref_restart_message), Toast.LENGTH_LONG).show()
            }
            onComplete(value)
        }
        isValid
    }
}

/**
 * Add a [ListPreference] preference to the screen
 */
fun PreferenceScreen.addListPreference(
    key: String,
    default: String,
    title: String,
    summary: String,
    entries: Array<String>,
    entryValues: Array<String>,
    restartRequired: Boolean = false,
    enabled: Boolean = true,
    onChange: (Preference, String) -> Boolean = { _, _ -> true },
    onComplete: (String) -> Unit = {},
) {
    getListPreference(
        key = key,
        default = default,
        title = title,
        summary = summary,
        entries = entries,
        entryValues = entryValues,
        restartRequired = restartRequired,
        enabled = enabled,
        onChange = onChange,
        onComplete = onComplete,
    ).also(::addPreference)
}

/**
 * Get a [MultiSelectListPreference] preference
 */
fun PreferenceScreen.getSetPreference(
    key: String,
    default: Set<String>,
    title: String,
    summary: String,
    entries: List<String>,
    entryValues: List<String>,
    restartRequired: Boolean = false,
    enabled: Boolean = true,
    onChange: (Preference, Set<String>) -> Boolean = { _, _ -> true },
    onComplete: (Set<String>) -> Unit = {},
): MultiSelectListPreference = MultiSelectListPreference(context).apply {
    this.key = key
    this.title = title
    this.summary = summary
    this.entries = entries.toTypedArray()
    this.entryValues = entryValues.toTypedArray()
    setDefaultValue(default)
    setEnabled(enabled)

    setOnPreferenceChangeListener { pref, newValues ->
        @Suppress("UNCHECKED_CAST")
        val values = newValues as Set<String>
        val isValid = onChange(pref, values)
        if (isValid) {
            if (restartRequired) {
                Toast.makeText(context, context.getString(R.string.pref_restart_message), Toast.LENGTH_LONG).show()
            }
            onComplete(values)
        }
        isValid
    }
}

/**
 * Add a [MultiSelectListPreference] preference to the screen
 */
fun PreferenceScreen.addSetPreference(
    key: String,
    default: Set<String>,
    title: String,
    summary: String,
    entries: List<String>,
    entryValues: List<String>,
    restartRequired: Boolean = false,
    enabled: Boolean = true,
    onChange: (Preference, Set<String>) -> Boolean = { _, _ -> true },
    onComplete: (Set<String>) -> Unit = {},
) {
    getSetPreference(
        key = key,
        default = default,
        title = title,
        summary = summary,
        entries = entries,
        entryValues = entryValues,
        restartRequired = restartRequired,
        enabled = enabled,
        onChange = onChange,
        onComplete = onComplete,
    ).also(::addPreference)
}

/**
 * Get a [SwitchPreferenceCompat] preference
 */
fun PreferenceScreen.getSwitchPreference(
    key: String,
    default: Boolean,
    title: String,
    summary: String,
    restartRequired: Boolean = false,
    enabled: Boolean = true,
    onChange: (Preference, Boolean) -> Boolean = { _, _ -> true },
    onComplete: (Boolean) -> Unit = {},
): SwitchPreferenceCompat = SwitchPreferenceCompat(context).apply {
    this.key = key
    this.title = title
    this.summary = summary
    setDefaultValue(default)
    setEnabled(enabled)

    setOnPreferenceChangeListener { pref, newValue ->
        val value = newValue as Boolean
        val isValid = onChange(pref, value)
        if (isValid) {
            if (restartRequired) {
                Toast.makeText(context, context.getString(R.string.pref_restart_message), Toast.LENGTH_LONG).show()
            }
            onComplete(value)
        }
        isValid
    }
}

/**
 * Add a [SwitchPreferenceCompat] preference to the screen
 */
fun PreferenceScreen.addSwitchPreference(
    key: String,
    default: Boolean,
    title: String,
    summary: String,
    restartRequired: Boolean = false,
    enabled: Boolean = true,
    onChange: (Preference, Boolean) -> Boolean = { _, _ -> true },
    onComplete: (Boolean) -> Unit = {},
) {
    getSwitchPreference(
        key = key,
        default = default,
        title = title,
        summary = summary,
        restartRequired = restartRequired,
        enabled = enabled,
        onChange = onChange,
        onComplete = onComplete,
    ).also(::addPreference)
}
