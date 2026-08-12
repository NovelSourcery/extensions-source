plugins {
    alias(kei.plugins.multisrc)
}

keiyoushi {
    baseVersionCode = 2
    libVersion = "1.4"
}

dependencies {
    api(project(":lib:cookieinterceptor"))
}
