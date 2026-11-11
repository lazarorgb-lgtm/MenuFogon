plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.pdfbox)
}

application {
    mainClass.set("com.menufogon.tools.GenerateFixturesKt")
}

// ./gradlew :tools:run  -> reads samples/*.pdf and writes core/src/test/resources/fixtures/*.json
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    args = listOf("samples", "core/src/test/resources/fixtures")
}
