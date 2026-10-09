
import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Yaoi.me"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    listOf(
        "all", "en", "ja", "zh", "vi", "id", "es", "pl", "tr", "fr",
        "ko", "it", "ru", "hu", "uk", "cs", "pt", "th", "ar",
        "es-419", "mn", "de", "fa", "pt-BR", "sk", "ca", "bg",
        "ro", "he", "km", "bn", "hi", "kk", "hr", "my", "sr",
        "el", "nl", "ms", "no",
    ).forEach {
        source {
            lang = it
            baseUrl = "https://yaoi.me"
        }
    }

    deeplink {
        host("yaoi.me")
        path("/series/..*")
    }
}
