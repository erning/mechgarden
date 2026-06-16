// Mirage — MechGarden's defensive movement-focused 1v1 robot.
// Builds against the local engine and deploys a single robot jar. The selectable
// shell is zen.Mirage; the implementation and helpers live under zen.mirage.
// kotlin-stdlib is provided by the engine, so it is not bundled.

val engineDir = rootProject.projectDir.resolve("robocode")
val robotsDir = engineDir.resolve("robots")
val robocodeJar = engineDir.resolve("libs/robocode.jar")

dependencies {
    compileOnly(files(robocodeJar))
}

tasks.named<Jar>("jar") {
    archiveFileName.set("mirage.jar")
}

val deploy by tasks.registering(Copy::class) {
    group = "robocode"
    description = "Build Mirage as a jar and deploy it into robocode/robots."
    from(tasks.named("jar"))
    into(robotsDir)
    doLast {
        delete(robotsDir.resolve("robot.database"))
        logger.lifecycle("Deployed mirage.jar to $robotsDir")
    }
}

tasks.register<Delete>("undeploy") {
    group = "robocode"
    description = "Remove Mirage's jar from robocode/robots."
    delete(robotsDir.resolve("mirage.jar"))
}
