"""Change only a CI working copy. These variants are not production builds."""
from pathlib import Path
import sys

variant = sys.argv[1]
build = Path("app/build.gradle.kts")
if variant == "early-init":
    app = Path("app/src/main/java/ru/tabatabata/Telemetry.kt")
    app.write_text(app.read_text().replace('        Telemetry.activate(this)', '''        Telemetry.activate(this)
        if (BuildConfig.BANNER_ID.isNotBlank() || BuildConfig.INTERSTITIAL_ID.isNotBlank())
            com.yandex.mobile.ads.common.YandexAds.initialize(this) {}'''))
if variant == "cleartext":
    manifest = Path("app/src/main/AndroidManifest.xml")
    manifest.write_text(manifest.read_text().replace('<application ', '<application android:usesCleartextTraffic="true" ', 1))
if variant == "version09":
    text = build.read_text().replace('versionCode = 19', 'versionCode = 18').replace('versionName = "0.10.0"', 'versionName = "0.9.0"')
    build.write_text(text)
if variant == "without-review":
    build.write_text(build.read_text().replace('    implementation("ru.rustore.sdk:review:10.5.2")\n', ''))
    Path("app/src/main/java/ru/tabatabata/RuStoreReviews.kt").write_text('''package ru.tabatabata
import android.app.Activity
internal class RuStoreReviews(activity: Activity) : ReviewGateway {
    override val available = false
    override suspend fun prepare(): Any = error("Diagnostic stub")
    override suspend fun launch(token: Any) { error("Diagnostic stub") }
    override fun isAlreadyReviewed(error: Throwable) = false
}
''')
probe = Path("/tmp/BannerDiagnosticTest.kt").read_text()
if variant == "main-host":
    probe = probe.replace("createAndroidComposeRule<ComponentActivity>()", "createAndroidComposeRule<MainActivity>()")
Path("app/src/androidTest/java/ru/tabatabata/BannerDiagnosticTest.kt").write_text(probe)
