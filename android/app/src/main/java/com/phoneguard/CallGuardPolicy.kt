package com.phoneguard

/**
 * 来电是否拦截的纯规则。不依赖 Android 框架，便于单测。
 *
 * 规则：
 * 1. 防护没开、号码为空、归属地未知、还没填家乡 → 放行
 * 2. 黑名单 → 拦截
 * 3. 白名单或通讯录 → 放行
 * 4. 读不到通讯录 → 放行（避免把外地亲友拦掉）
 * 5. 家乡相同（省或市）→ 放行
 * 6. 其余外地号码，在开启「拦截外地」时拦截
 */
object CallGuardPolicy {

    enum class Decision {
        ALLOW,
        BLOCK,
    }

    data class Facts(
        val normalizedNumber: String,
        val protectionEnabled: Boolean,
        val autoBlock: Boolean,
        val localRegion: String,
        val numberProvince: String,
        val numberCity: String,
        val locationKnown: Boolean,
        val inContacts: Boolean,
        val contactsReadable: Boolean,
        val inWhitelist: Boolean,
        val inBlacklist: Boolean,
    )

    fun decide(facts: Facts): Decision {
        if (!facts.protectionEnabled) return Decision.ALLOW
        if (facts.normalizedNumber.isEmpty()) return Decision.ALLOW
        if (facts.inBlacklist) return Decision.BLOCK
        if (facts.inWhitelist || facts.inContacts) return Decision.ALLOW
        if (!facts.autoBlock) return Decision.ALLOW
        if (!facts.contactsReadable) return Decision.ALLOW
        if (normalizeRegion(facts.localRegion).isEmpty()) return Decision.ALLOW
        if (!facts.locationKnown) return Decision.ALLOW
        if (isLocalRegion(facts.localRegion, facts.numberProvince, facts.numberCity)) {
            return Decision.ALLOW
        }
        return Decision.BLOCK
    }

    fun matchesList(list: Set<String>, rawNumber: String): Boolean {
        val normalized = normalizeNumber(rawNumber)
        if (normalized.isEmpty()) return false
        return list.any { normalizeNumber(it) == normalized }
    }

    fun normalizeNumber(raw: String): String {
        var digits = raw.filter { it.isDigit() }
        if (digits.startsWith("0086")) {
            digits = digits.removePrefix("0086")
        }
        if (digits.startsWith("86") && digits.length > 11) {
            val rest = digits.removePrefix("86")
            if (rest.length >= 10) {
                digits = rest
            }
        }
        if (digits.length == 12 && digits.startsWith("01")) {
            digits = digits.drop(1)
        }
        return digits
    }

    fun redact(raw: String): String {
        val digits = normalizeNumber(raw)
        if (digits.length <= 4) return "尾号不明"
        return "尾号${digits.takeLast(4)}"
    }

    fun isLocalRegion(local: String, province: String, city: String): Boolean {
        val key = normalizeRegion(local)
        if (key.isEmpty()) return false
        val normalizedProvince = normalizeRegion(province)
        val normalizedCity = normalizeRegion(city)
        return key == normalizedProvince || key == normalizedCity
    }

    fun normalizeRegion(name: String): String {
        var value = name.trim().replace(" ", "").replace("\u3000", "")
        val suffixes = listOf(
            "维吾尔自治区",
            "壮族自治区",
            "回族自治区",
            "特别行政区",
            "自治区",
            "省",
            "市",
        )
        for (suffix in suffixes) {
            if (value.endsWith(suffix) && value.length > suffix.length) {
                value = value.removeSuffix(suffix)
                break
            }
        }
        return value
    }
}
