// Fencer — MechGarden's clean-sheet, layered 1v1 robot (formerly Duelist-04).
// Builds against the local engine and deploys a single robot jar. The selectable
// shell is zen.Fencer; the implementation and helpers live under zen.fencer.
// kotlin-stdlib is provided by the engine, so it is not bundled.

val engineDir = rootProject.projectDir.resolve("robocode")
val robotsDir = engineDir.resolve("robots")
val robocodeJar = engineDir.resolve("libs/robocode.jar")

dependencies {
    compileOnly(files(robocodeJar))
}

tasks.named<Jar>("jar") {
    archiveFileName.set("fencer.jar")
}

val deploy by tasks.registering(Copy::class) {
    group = "robocode"
    description = "Build Fencer as a jar and deploy it into robocode/robots."
    from(tasks.named("jar"))
    into(robotsDir)
    doLast {
        delete(robotsDir.resolve("robot.database"))
        logger.lifecycle("Deployed fencer.jar to $robotsDir")
    }
}

tasks.register<Delete>("undeploy") {
    group = "robocode"
    description = "Remove Fencer's jar from robocode/robots."
    delete(robotsDir.resolve("fencer.jar"))
}
