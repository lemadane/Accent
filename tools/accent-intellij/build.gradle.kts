plugins {
    id("java")
    kotlin("jvm") version "1.9.22"
    id("org.jetbrains.intellij.platform") version "2.0.0"
}

group = "io.lemadane.accent"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":tools:accent-language-tools"))
    intellijPlatform {
        intellijIdeaCommunity("2023.3.4")
        bundledPlugin("com.intellij.java")
        instrumentationTools()
    }
}
