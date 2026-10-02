plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

application {
    mainClass.set("net.kigawa.admin.server.ApplicationKt")
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    // issue #183: Keycloak client roleによるRBAC認証(Ktor Authentication/JWT)。
    // ktor-server-auth は ktor-server-auth-jwt が依存して取り込む。
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback.classic)
    implementation(libs.sshj)
    implementation(libs.mariadb.java.client)
    implementation(libs.hikaricp)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.client.mock)
    // issue #183: 認証・認可ケースの自動テスト用
    testImplementation(libs.ktor.server.test.host)
}

kotlin {
    jvmToolchain(21)
}
