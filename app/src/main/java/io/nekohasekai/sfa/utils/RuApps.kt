package io.nekohasekai.sfa.utils

/**
 * Russian apps that should never go through the VPN. Excluded from the
 * tunnel (VpnService disallowed apps), they keep the phone's own connection
 * and don't see a VPN at all — Ozon, banks and Gosuslugi refuse to work or
 * nag when they detect one, and route-level "direct" rules can't hide it.
 */
object RuApps {
    val PACKAGES = listOf(
        // Marketplaces and delivery
        "ru.ozon.app.android", "ru.ozon.fintech.finance", "com.wildberries.ru", "com.avito.android",
        "ru.instamart", "ru.yandex.market", "ru.yandex.taxi", "ru.dublgis.dgismobile",
        // Banks and payments
        "ru.sberbankmobile", "com.idamob.tinkoff.android", "ru.vtb24.mobilebanking.android",
        "ru.alfabank.mobile.android", "ru.gazprombank.android.mobilebank.app", "ru.raiffeisennews",
        "ru.nspk.mirpay", "ru.nspk.sbpay",
        // State services and transport
        "ru.rostel", "ru.rzd.pass", "ru.mos.app",
        // VK, Mail, Yandex
        "com.vkontakte.android", "com.vk.vkvideo", "ru.mail.mailapp", "ru.ok.android", "ru.oneme.app",
        "ru.yandex.searchplugin", "ru.yandex.yandexmaps", "ru.yandex.music", "com.yandex.browser", "ru.kinopoisk",
        // Carriers
        "ru.mts.mymts", "ru.megafon.mlk", "ru.beeline.services", "ru.tele2.mytele2",
    )
}
