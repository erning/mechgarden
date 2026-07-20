// Proteus — MechGarden's adaptive 1v1 robot aiming at the expert tier.
// Builds against the local engine and deploys a single robot jar. The selectable
// shell is zen.Proteus; the implementation and helpers live under zen.proteus.
// kotlin-stdlib is provided by the engine, so it is not bundled.

import java.util.Properties

val engineDir = rootProject.projectDir.resolve("robocode")
val robotsDir = engineDir.resolve("robots")
val robocodeJar = engineDir.resolve("libs/robocode.jar")
val robotProperties =
    Properties().apply {
        file("src/main/resources/zen/Proteus.properties").inputStream().use(::load)
    }
val robotJarFileName =
    "${robotProperties.getProperty("robot.classname")} ${robotProperties.getProperty("robot.version")}"
        .replace(' ', '_') + ".jar"
val robotJarPattern = "${robotProperties.getProperty("robot.classname")}_*.jar"
val legacyJarFileName = "${project.name}.jar"
val deleteOldBuiltJars by tasks.registering(Delete::class) {
    delete(
        fileTree(layout.buildDirectory.dir("libs")) {
            include(robotJarPattern, legacyJarFileName)
            exclude(robotJarFileName)
        },
    )
}
dependencies {
    compileOnly(files(robocodeJar))
    testImplementation(kotlin("test"))
    testCompileOnly(files(robocodeJar))
    testRuntimeOnly(files(robocodeJar))
}

tasks.named<Jar>("jar") {
    dependsOn(deleteOldBuiltJars)
    archiveFileName.set(robotJarFileName)
}

val deploy by tasks.registering(Copy::class) {
    group = "robocode"
    description = "Build Proteus as a jar and deploy it into robocode/robots."
    from(tasks.named("jar"))
    into(robotsDir)
    doFirst {
        // Keep the last deployed robot intact when compilation or packaging fails.
        delete(
            fileTree(robotsDir) {
                include(robotJarPattern, legacyJarFileName)
            },
            robotsDir.resolve("robot.database"),
        )
    }
    doLast {
        logger.lifecycle("Deployed $robotJarFileName to $robotsDir")
    }
}

tasks.register<Delete>("undeploy") {
    group = "robocode"
    description = "Remove all deployed Proteus jars from robocode/robots."
    delete(
        fileTree(robotsDir) {
            include(robotJarPattern, legacyJarFileName)
        },
        robotsDir.resolve("robot.database"),
    )
}
