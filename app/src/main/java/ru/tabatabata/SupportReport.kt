package ru.tabatabata

internal data class SupportDevice(val version: String, val versionCode: Int, val android: String,
    val api: Int, val manufacturer: String, val model: String, val language: String) {
    fun technicalText(): String = """
        Tabata Bata $version ($versionCode)
        Android $android · API $api
        $manufacturer $model
        Locale: $language
    """.trimIndent()
}

/** Explicit allowlist: no ids, logs, timers, plans, history or saved settings. */
internal fun supportReport(description: String, device: SupportDevice): String =
    description.take(2000).trim() + "\n\n---\n" + device.technicalText()

internal const val STORE_PAGE = "https://www.rustore.ru/catalog/app/ru.tabatabata"
