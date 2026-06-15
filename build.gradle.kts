// Root build for MechGarden.
//
// The Kotlin JVM plugin is applied to every subproject; per-module build files
// only declare what is specific to them (dependencies, deploy tasks).

plugins {
    // Version matches the kotlin-stdlib bundled with the Robocode engine
    // (robocode/libs/kotlin-stdlib-2.3.20.jar) so compiled robots stay
    // binary-compatible with the runtime the engine provides.
    kotlin("jvm") version "2.3.20" apply false
    // Code formatting via ktlint. Run `just fmt` / `./gradlew spotlessApply`.
    id("com.diffplug.spotless") version "7.0.2"
}

// Root: format the root Gradle scripts.
repositories {
    mavenCentral()
}

spotless {
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "com.diffplug.spotless")

    repositories {
        mavenCentral()
    }

    // Format this module's Kotlin sources and build script with ktlint.
    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            ktlint()
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint()
        }
    }

    // Emit Java 8 bytecode. Robocode analyses robot classes with BCEL, which
    // does not understand newer class file versions and would silently treat the
    // robot as "not a robot". Keep Kotlin and Java targets consistent.
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        }
    }
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
