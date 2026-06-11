import dev.detekt.gradle.Detekt
import dev.detekt.gradle.DetektCreateBaselineTask

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
    // Pre-existing findings are pinned per module; rules stay active for new code.
    // Regenerate after deliberate cleanups: ./gradlew :<module>:detektBaseline
    baseline = file("detekt-baseline.xml")
}

val detektSourceDirs =
    listOf(
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

tasks.withType<Detekt>().configureEach {
    setSource(files(detektSourceDirs))
    include("**/*.kt", "**/*.kts")
    exclude("**/build/**", "**/resources/**")
}

tasks.withType<DetektCreateBaselineTask>().configureEach {
    setSource(files(detektSourceDirs))
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
