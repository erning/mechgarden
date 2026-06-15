// Ronin — MechGarden's flattener wave-surfer built to beat Fencer.
// Builds against the local engine and deploys a single robot jar. The selectable
// shell is zen.Ronin; the implementation and helpers live under zen.ronin.
// kotlin-stdlib is provided by the engine, so it is not bundled.

val engineDir = rootProject.projectDir.resolve("robocode")
val robotsDir = engineDir.resolve("robots")
val robocodeJar = engineDir.resolve("libs/robocode.jar")

dependencies {
    compileOnly(files(robocodeJar))
}

tasks.named<Jar>("jar") {
    archiveFileName.set("ronin.jar")
}

val deploy by tasks.registering(Copy::class) {
    group = "robocode"
    description = "Build Ronin as a jar and deploy it into robocode/robots."
    from(tasks.named("jar"))
    into(robotsDir)
    doLast {
        delete(robotsDir.resolve("robot.database"))
        logger.lifecycle("Deployed ronin.jar to $robotsDir")
    }
}

tasks.register<Delete>("undeploy") {
    group = "robocode"
    description = "Remove Ronin's jar from robocode/robots."
    delete(robotsDir.resolve("ronin.jar"))
}
