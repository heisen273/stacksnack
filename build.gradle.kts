plugins {
    id("org.jetbrains.intellij") version "1.17.3"
    kotlin("jvm") version "1.9.24"
    java
}

group = "com.stackframehider"
version = "1.0.8"

repositories {
    mavenCentral()
}

// Set Java compatibility for both source and target
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

intellij {

    // Use local installations

    // PyCharm
    localPath.set("/Applications/PyCharm.app/Contents")
    type.set("PY")
//    version.set("PC-2023.1") // or match your local PyCharm version
//    type.set("PC")           // use "IC" for IntelliJ Community

    // GoLand//
     localPath.set("/Applications/GoLand.app/Contents")
     type.set("GO")

    // Don't specify plugins for now - let's use what's available
//     plugins.set(listOf("python"))
}

dependencies {
    implementation(kotlin("stdlib"))
}

// Configure Kotlin compilation
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Configure Java compilation
tasks.withType<JavaCompile> {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

tasks.patchPluginXml {
    sinceBuild.set("231")
    untilBuild.set("252.*")
}