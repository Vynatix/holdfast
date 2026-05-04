import dev.detekt.gradle.Detekt

plugins {
    id("dev.detekt")
    id("org.jlleitschuh.gradle.ktlint")
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    parallel = true
    autoCorrect = false
    basePath = rootProject.layout.projectDirectory
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
}

tasks.withType<Detekt>().configureEach {
    setSource(
        files(
            "src/commonMain/kotlin",
            "src/androidMain/kotlin",
            "src/iosMain/kotlin",
            "src/iosArm64Main/kotlin",
            "src/iosSimulatorArm64Main/kotlin",
            "src/jvmMain/kotlin",
            "src/wasmJsMain/kotlin",
            "src/main/kotlin",
            "src/main/java"
        )
    )
    include("**/*.kt", "**/*.kts")
    exclude("**/build/**", "**/resources/**")
}

ktlint {
    verbose.set(true)
    android.set(false)
    outputToConsole.set(true)
    ignoreFailures.set(false)
    filter {
        exclude { it.file.path.contains("build/") }
    }
}
