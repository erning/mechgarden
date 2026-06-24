// Ronin — MechGarden's flattener wave-surfer built to beat Fencer.
// Builds against the local engine and deploys a single robot jar. The selectable
// shell is zen.Ronin; the implementation and helpers live under zen.ronin.
// kotlin-stdlib is provided by the engine, so it is not bundled.

import java.util.Properties

val engineDir = rootProject.projectDir.resolve("robocode")
val robotsDir = engineDir.resolve("robots")
val robocodeJar = engineDir.resolve("libs/robocode.jar")
val robotProperties =
    Properties().apply {
        file("src/main/resources/zen/Ronin.properties").inputStream().use(::load)
    }
val robotJarFileName =
    "${robotProperties.getProperty("robot.classname")} ${robotProperties.getProperty("robot.version")}"
        .replace(' ', '_') + ".jar"
val legacyJarFileName = "${project.name}.jar"
val deleteLegacyBuiltJar by tasks.registering(Delete::class) {
    delete(layout.buildDirectory.file("libs/$legacyJarFileName"))
}
val deleteLegacyDeployedJar by tasks.registering(Delete::class) {
    delete(robotsDir.resolve(legacyJarFileName))
}

dependencies {
    compileOnly(files(robocodeJar))
}

tasks.named<Jar>("jar") {
    dependsOn(deleteLegacyBuiltJar)
    archiveFileName.set(robotJarFileName)
}

val deploy by tasks.registering(Copy::class) {
    group = "robocode"
    description = "Build Ronin as a jar and deploy it into robocode/robots."
    dependsOn(deleteLegacyDeployedJar)
    from(tasks.named("jar"))
    into(robotsDir)
    doLast {
        delete(robotsDir.resolve("robot.database"))
        logger.lifecycle("Deployed $robotJarFileName to $robotsDir")
    }
}

tasks.register<Delete>("undeploy") {
    group = "robocode"
    description = "Remove Ronin's jar from robocode/robots."
    delete(robotsDir.resolve(robotJarFileName), robotsDir.resolve(legacyJarFileName))
}
