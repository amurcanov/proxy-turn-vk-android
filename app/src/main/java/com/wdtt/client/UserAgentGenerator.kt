package com.wdtt.client

import android.os.Build
import kotlin.random.Random

/**
 * Генерирует реалистичный User-Agent на основе информации об устройстве.
 * Включает случайные вариации версий браузеров и мелкие опечатки для уникальности.
 */
object UserAgentGenerator {

    private val chromeVersions = listOf(120, 121, 122, 123, 124, 125, 126, 127, 128, 129, 130, 131)
    private val firefoxVersions = listOf(120, 121, 122, 123, 124, 125, 126, 127, 128, 129, 130)
    private val edgeVersions = listOf(120, 121, 122, 123, 124, 125, 126, 127, 128, 129, 130)
    private val yaBrowserVersions = listOf("24.1", "24.2", "24.3", "24.4", "24.5", "24.6", "24.7", "24.8", "24.9", "24.10", "24.11", "24.12")
    private val operaVersions = listOf(106, 107, 108, 109, 110, 111, 112, 113, 114, 115)

    /**
     * Генерирует User-Agent для текущего устройства.
     * @param seed опциональный seed для детерминированной генерации (на основе device ID)
     */
    fun generate(seed: Long? = null): String {
        val rng = seed?.let { Random(it) } ?: Random.Default

        val androidVersion = Build.VERSION.RELEASE
        val deviceModel = Build.MODEL ?: "Unknown"

        val androidPlatform = "Linux; Android $androidVersion; $deviceModel"

        val chromeVersion = chromeVersions[rng.nextInt(chromeVersions.size)]
        val patchVersion = rng.nextInt(0, 99)
        val fullChromeVersion = "$chromeVersion.0.$patchVersion.0"

        val browserType = rng.nextInt(100)

        return when {
            // 60% — обычный Chrome на Android
            browserType < 60 -> {
                "Mozilla/5.0 ($androidPlatform) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$fullChromeVersion Mobile Safari/537.36"
            }
            // 15% — Chrome на десктопе (имитация)
            browserType < 75 -> {
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$fullChromeVersion Safari/537.36"
            }
            // 8% — Yandex Browser
            browserType < 83 -> {
                val yaVer = yaBrowserVersions[rng.nextInt(yaBrowserVersions.size)]
                val yaPatch = rng.nextInt(0, 9)
                "Mozilla/5.0 ($androidPlatform) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVersion.0.$patchVersion.0 YaBrowser/$yaVer.$yaPatch Yowser/2.5 Mobile Safari/537.36"
            }
            // 7% — Firefox
            browserType < 90 -> {
                val ffVersion = firefoxVersions[rng.nextInt(firefoxVersions.size)]
                "Mozilla/5.0 ($androidPlatform; rv:$ffVersion.0) Gecko/20100101 Firefox/$ffVersion.0"
            }
            // 5% — Edge
            browserType < 95 -> {
                val edgeVersion = edgeVersions[rng.nextInt(edgeVersions.size)]
                "Mozilla/5.0 ($androidPlatform) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$fullChromeVersion Mobile Safari/537.36 EdgA/$edgeVersion.0.${rng.nextInt(0, 99)}.${rng.nextInt(0, 99)}"
            }
            // 5% — Opera
            else -> {
                val operaVersion = operaVersions[rng.nextInt(operaVersions.size)]
                "Mozilla/5.0 ($androidPlatform) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$fullChromeVersion Mobile Safari/537.36 OPR/$operaVersion.0.${rng.nextInt(0, 99)}"
            }
        }
    }

    /**
     * Генерирует детерминированный UA на основе device ID.
     * Один и тот же device ID всегда даёт одинаковый UA.
     */
    fun generateForDevice(deviceId: String): String {
        val seed = deviceId.hashCode().toLong() and 0xFFFFFFFFL
        return generate(seed)
    }
}
