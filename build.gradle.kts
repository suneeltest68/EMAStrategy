plugins {
    kotlin("jvm") version "2.1.20"
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.example.MainKt")
    applicationName = "cww"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(fileTree("libs").include("*.jar"))
    implementation("org.json:json:20211205")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("io.github.cdimascio:dotenv-kotlin:6.4.1")
    implementation("org.ta4j:ta4j-core:0.26.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

tasks.test {
    useJUnit()
}
