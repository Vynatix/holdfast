plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.gradle.kotlin)
    implementation(libs.gradle.android)
    implementation(libs.gradle.android.kmp.library)
    implementation(libs.gradle.compose)
    implementation(libs.gradle.compose.compiler)
    implementation(libs.gradle.detekt)
    implementation(libs.gradle.ktlint)
    implementation(libs.gradle.dokka)
    implementation(libs.gradle.binary.compatibility.validator)
}
