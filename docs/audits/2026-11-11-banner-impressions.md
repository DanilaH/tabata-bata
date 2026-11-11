# Реклама — проверка 11 ноября 2026

Пакет ru.tabatabata, кандидат 0.10.1 / versionCode 20. Код приложения: `2ac3ae5696240fcb6485f81998ff4a1ed58f5785`; a558cef добавляет проверку конфигурации и документацию без изменения приложения. Владелец исправил production Secrets 11 ноября: повторная проверка различия PASS, новая подписанная APK подготовлена. Полная готовность рекламы к релизу не подтверждена: demo-сбои ниже остаются.

## Подтверждённая ошибка production-конфигурации

GitHub Secrets `YANDEX_BANNER_ID` и `YANDEX_INTERSTITIAL_ID` содержат одинаковое значение. Оба проходят формат `R-M-<число>-<число>`, но `ids_are_distinct` возвращает false. Проверка не печатала значения, не запускала приложение и не делала рекламных запросов. [Прогон 42986778419](https://github.com/DanilaH/tabata-bata/actions/runs/42986778419), job 130409721551.

Один ID передаётся двум различным SDK-форматам. Требуются два действительных блока приложения из РСЯ: «Баннер» для `YANDEX_BANNER_ID`, «Межстраничная реклама» для `YANDEX_INTERSTITIAL_ID`. По самому значению нельзя определить формат блока или вычислить ID другого формата. Пока кабинет не проверен, неизвестно, какой Secret неверен. Подменять последний суффикс предположением нельзя.

Добавлен `scripts/validate-production-config.py`: configured release отклоняется до установки Android SDK/сборки при пустом AppMetrica key, неверном формате, demo ID, пробелах или совпадении рекламных ID. Вывод содержит только имена проверок и boolean. Четыре regression-теста проверяют разные production ID, совпадение, demo/пустые/повреждённые ID и пустой analytics key. Барьер не проверяет принадлежность, формат или активность блоков в кабинете.

В доступном браузере РСЯ не авторизована. После сообщения владельца об исправлении Secrets повторная статическая проверка 42986778419 (job 130416197452) PASS: оба production-формата, различие и отсутствие пробелов. Новый configured-release на main/a558cef, job 130418216268, тоже PASS вместе с новым барьером и build/lint. Формат каждого блока в кабинете и начисления этим не подтверждены. Значения ID не печатались и в репозиторий не записывались.

## Изменения интеграции

- BannerLease перенесён из общего View.tag в именованный `R.id.tabata_banner_lease`. При удалении View lease становится неактивным, свой ключ очищается, listener отключается, SDK view уничтожается. Поздние callbacks игнорируются.
- SDK инициализируется в Application.onCreate при наличии рекламных ID. После готовности SDK размер sticky-баннера вычисляется заново: предварительный размер не остаётся закешированным для загружаемого view.
- Demo-проверки покрывают `demo-banner-yandex` и явный HTML `demo-banner-adfox-html`. Yandex-тест дополнительно ждёт автообновления SDK и реального нового impression без дополнительного app-initiated request. Собственный таймер refresh не добавлен.
- SDK stories без отдельной кнопки закрытия тестируются системной «Назад», только когда в дереве обнаружены video controls. Приложение не добавляет свою кнопку поверх рекламы; настоящий SDK close callback обязателен.

Частота и места рекламы, safeDrawing, резервирование высоты, SDK-версии, HTTP/TLS-политика, таймер и режимы не менялись. Показы в автоматике только на demo ID. Реальные блоки используются только для сборки.

## Проверки

| Проверка кода 2ac3ae5 | Результат |
| --- | --- |
| [Основной CI 42985798336](https://github.com/DanilaH/tabata-bata/actions/runs/42985798336) | Все 5 jobs PASS: 53 unit, lint/debug/release; по 37 UI-тестов Android 15/16; совместимость/обновление Android 8/15; системные debug/minified smoke. |
| [Timer audit 42985798306](https://github.com/DanilaH/tabata-bata/actions/runs/42985798306) | PASS. |
| [Configured/demo 42985798256](https://github.com/DanilaH/tabata-bata/actions/runs/42985798256), configured-release | build/lint PASS по прежнему условию «непустые». НЕ подтверждает правильность рекламных ID. Новый барьер должен отклонить эту конфигурацию. |
| Тот же прогон, demo-ads (html), job 130406535278 | PASS: SDK callbacks баннера/межстранички, переход после результата, minified offline smoke. |
| Тот же прогон, demo-ads (yandex), job 130406535301 | FAIL: два баннерных сценария без SDK impression; actual result-to-menu с межстраничкой и закрытием PASS. |
| Новая конфигурационная regression-проверка | Локально PASS, 4 теста. |

В прогонах 42981106905 и 42982207272 реальные banner impressions наблюдались, в последнем прошло также автообновление SDK. Повтор на том же коде снова воспроизвёл отсутствие callback. Перенос tag и ранний init нельзя объявлять доказанным устранением всех отказов.

На промежуточном test-only коммите 9442738 один UI-тест истории упал в Espresso/Compose measure при rotation; полный набор финального кода 2ac3ae5 прошёл обе версии Android. Тест не удалён и не ослаблен.

## Нерешённый demo creative и границы вывода

В проблемном HTML creative `demo-banner-yandex` view целиком видим, hardware acceleration включена, MRAID сообщает default/isViewable=true; HTTPS-ресурсы загружены. Читающая CDP-диагностика увидела 204 тестового счётчика, но SDK onImpression не пришёл. Нативные варианты этого demo и явный HTML Adfox отдают callback. Отказ воспроизводился и в отдельной ComponentActivity. Причина внутри SDK/creative не установлена.

Эксперименты в `ad-diagnostics-20261111` не входят в пользовательскую APK. Отключение Review dependency, изменение versionName/code, cleartext и переустановка listener не дали устойчивого доказанного решения. Ожидание положительного размера SDK view до load создаёт цикл 0×0 и отвергнуто. Заголовки/cookies/тела запросов не собирались, клики и искусственная отправка счётчиков не выполнялись.

`Telemetry.ad_impression` — диагностика приложения, она не управляет оплатой в РСЯ. Отсутствие события не доказывает отсутствие выручки. Ошибка production-конфигурации требует исправления, но долю SDK-отказов именно по этой причине надо подтвердить после смены ID по error codes и отчёту РСЯ.

Синхронизация Ads SDK и AppMetrica, верификация приложения в РСЯ, реальные fill rate/CPM/выручка в кабинетах не проверены. Ручной reportAdRevenue не добавлен: сначала надо проверить уже используемый путь синхронизации и исключить двойной учёт. Форма RuStore на авторизованном телефоне остаётся отдельной непроверенной частью 0.10.

## Для завершения

Исправление Secrets и повторная сборка выполнены. Выдана `tabata-bata-0.10.1-ad-fix.apk`, код приложения 2ac3ae5, повтор configured-release 42985798256/job 130416673890, artifact 13840298698 от 08:45:49 UTC (старый артефакт того же имени от 08:23 не использовать). В DEX новой APK найдены ровно два разных production ID, demo ID отсутствуют. Подпись v2/v3 прежним сертификатом и zipalign 16KB PASS. Размер 5 845 480 байт, SHA256 `4c5967918a77a443d1ae15343df7375dd92cb5d1fd7c4cc6200c024a756a4522`. Публикация не выполнена.

Дополнительный прогон a558cef: build и UI Android 15/16 PASS, compatibility/upgrade Android 8 PASS. Compatibility Android 15 сначала упал на large-font landscape timeout и последующих touch injection; job повторно запущен, итог ещё ожидается. HTML demo снова PASS; Yandex demo job 130413707004 снова FAIL: два banner impression timeout и один `Actual UI demo did not close`. Последний close-таймаут показывает, что системная «Назад» тоже не дала устойчивого результата для всех creatives. Это не связано со сменой production Secrets: demo jobs их не получают.

Остаётся проверить нормальный цикл на телефоне и ошибки/показы/доход в РСЯ. Аналитику сравнивать по app_version=0.10.1: старые установки остаются со старыми ID. Проверить в кабинете соответствие каждого ID формату блока. Довести нестабильный Yandex demo до объяснения/устойчивой проверки; красный тест остаётся незакрытым пунктом. Наличие оплачиваемого инвентаря demo-тестом не подтверждается.

Источники: [Application init](https://ads.yandex.com/helpcenter/en/dev/android/quick-start), [учёт показа](https://ads.yandex.com/helpcenter/en/easy/integration/android/advanced-settings/ilrd), [синхронизация AppMetrica](https://yandex.com/support/appmetrica-io/en/common/ads-sdk-integration), [demo IDs](https://ads.yandex.com/helpcenter/en/dev/android/demo-blocks), [баннер](https://ads.yandex.com/helpcenter/en/monetization/ad-unit/banner), [межстраничный блок](https://ads.yandex.com/helpcenter/en/monetization/ad-unit/interstitial).
