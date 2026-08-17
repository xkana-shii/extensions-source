import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Lezhin"
    versionCode = 2
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://www.lezhinus.com"
    }

    source {
        lang = "ko"
        baseUrl = "https://www.lezhin.com"
    }

    deeplink {
        path("/../comic/..*")
    }
}
