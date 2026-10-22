plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    // "KI-nachbessern": official Anthropic SDK (Claude API)
    api("com.anthropic:anthropic-java:2.34.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    // renders the PDF test corpus (same role as android.graphics.pdf.PdfRenderer in the app)
    testImplementation("org.apache.pdfbox:pdfbox:3.0.3")
}

tasks.test {
    useJUnit()
    (project.findProperty("omr.debugDir") as String?)?.let { systemProperty("omr.debugDir", it) }
    (project.findProperty("omr.corpus") as String?)?.let { systemProperty("omr.corpus", it); outputs.upToDateWhen { false } }
    (project.findProperty("formats.dir") as String?)?.let { systemProperty("formats.dir", it); outputs.upToDateWhen { false } }
    (project.findProperty("formats.diff") as String?)?.let { systemProperty("formats.diff", it) }
    (project.findProperty("photo.noise") as String?)?.let { systemProperty("photo.noise", it) }
    (project.findProperty("photo.angle") as String?)?.let { systemProperty("photo.angle", it) }
    (project.findProperty("engrave.out") as String?)?.let { systemProperty("engrave.out", it); outputs.upToDateWhen { false } }
    (project.findProperty("omr.only") as String?)?.let { systemProperty("omr.only", it) }
    maxHeapSize = "3g"
    (project.findProperty("omr.trace") as String?)?.let { systemProperty("omr.trace", it) }
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
