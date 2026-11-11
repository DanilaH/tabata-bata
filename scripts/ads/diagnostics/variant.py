"""Change only a CI working copy. These variants are not production builds."""
from pathlib import Path
import sys

variant = sys.argv[1]
build = Path("app/build.gradle.kts")
if variant == "keyed-tag":
    ads = Path("app/src/main/java/ru/tabatabata/Ads.kt")
    source = ads.read_text().replace('tag = lease', 'setTag(R.id.app_banner_lease, lease)')
    source = source.replace('(it.tag as? BannerLease)', '(it.getTag(R.id.app_banner_lease) as? BannerLease)')
    ads.write_text(source)
    Path("app/src/main/res/values/banner_ids.xml").write_text('<resources><item name="app_banner_lease" type="id"/></resources>\n')
if variant == "attached-load":
    ads = Path("app/src/main/java/ru/tabatabata/Ads.kt")
    source = ads.read_text().replace('import android.app.Activity', 'import android.app.Activity\nimport androidx.core.view.doOnAttach')
    source = source.replace('''                            Telemetry.event("ad_request", mapOf("format" to "banner", "scope" to "initial_load"))
                            loadAd(AdRequest.Builder(BuildConfig.BANNER_ID).build())''', '''                            doOnAttach {
                                post {
                                    if (lease.alive && isAttachedToWindow) {
                                        Telemetry.event("ad_request", mapOf("format" to "banner", "scope" to "initial_load"))
                                        loadAd(AdRequest.Builder(BuildConfig.BANNER_ID).build())
                                    }
                                }
                            }''')
    ads.write_text(source)
if variant == "rebind-listener":
    ads = Path("app/src/main/java/ru/tabatabata/Ads.kt")
    source = ads.read_text().replace('''                            setBannerAdEventListener(
                                object : BannerAdEventListener {''', '''                            lateinit var bannerListener: BannerAdEventListener
                            bannerListener = object : BannerAdEventListener {''')
    source = source.replace('''                                        Telemetry.event("ad_loaded", mapOf("format" to "banner"))''', '''                                        setBannerAdEventListener(bannerListener)
                                        Telemetry.event("ad_loaded", mapOf("format" to "banner"))''')
    source = source.replace('''                                }
                            )
                            Telemetry.event("ad_request"''', '''                                }
                            setBannerAdEventListener(bannerListener)
                            Telemetry.event("ad_request"''')
    ads.write_text(source)
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
