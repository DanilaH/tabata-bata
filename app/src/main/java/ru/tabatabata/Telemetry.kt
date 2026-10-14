package ru.tabatabata

import android.app.Application
import io.appmetrica.analytics.AppMetrica
import io.appmetrica.analytics.AppMetricaConfig
import kotlinx.coroutines.*

class TabataApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Telemetry.activate(this)
    }
}

object Telemetry {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var enabled = false

    fun activate(app: Application) {
        if (BuildConfig.APPMETRICA_KEY.isBlank()) return
        runCatching {
            AppMetrica.activate(
                app,
                AppMetricaConfig.newConfigBuilder(BuildConfig.APPMETRICA_KEY)
                    .withLocationTracking(false)
                    .build(),
            )
            AppMetrica.enableActivityAutoTracking(app)
            enabled = true
        }
    }

    fun event(name: String, params: Map<String, Any> = emptyMap()) {
        if (BuildConfig.DEBUG)
            android.util.Log.d("TabataEvents", name + " " + org.json.JSONObject(params).toString())
        if (!enabled) return
        scope.launch {
            runCatching {
                AppMetrica.reportEvent(
                    name,
                    params +
                        mapOf(
                            "schema" to 1,
                            "build" to if (BuildConfig.DEBUG) "debug" else "release",
                        ),
                )
            }
        }
    }
}
