import robocode.BattleResults;

import robocode.control.BattleSpecification;
import robocode.control.BattlefieldSpecification;
import robocode.control.RobocodeEngine;
import robocode.control.RobotSpecification;
import robocode.control.events.BattleAdaptor;
import robocode.control.events.BattleCompletedEvent;
import robocode.control.events.RoundStartedEvent;
import robocode.control.events.TurnEndedEvent;
import robocode.control.snapshot.IRobotSnapshot;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * In-process battle runner for duels that need robot console output.
 *
 * Mirrors the headless CLI battle (800x600, gun cooling 0.1, inactivity 450) but
 * runs through the RobocodeEngine control API, so the watched robot's out.println
 * output -- which the headless CLI otherwise swallows into a UI-only buffer -- can
 * be streamed to stdout. Battle results are written to a file in the same TSV
 * format the CLI's -results flag produces, so the duel harness parses them
 * unchanged.
 *
 * Round-init markers go to stderr (mirroring the CLI) for progress; the watched
 * robot's console text goes to stdout.
 */
public class BattleRunner {
    private static final int WIDTH = 800;
    private static final int HEIGHT = 600;
    private static final double COOLING_RATE = 0.1;
    private static final long INACTIVITY_TIME = 450;

    private static String watchPrefix;
    private static BattleResults[] sortedResults;
    private static int rounds;

    public static void main(String[] args) throws IOException {
        String robots = null;
        Path resultsPath = null;
        rounds = 0;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--robots":
                    robots = args[++i];
                    break;
                case "--rounds":
                    rounds = Integer.parseInt(args[++i]);
                    break;
                case "--results":
                    resultsPath = Paths.get(args[++i]);
                    break;
                case "--watch":
                    watchPrefix = args[++i];
                    break;
                default:
                    break;
            }
        }
        if (robots == null || resultsPath == null || rounds <= 0) {
            System.err.println("usage: BattleRunner --robots A,B --rounds N --results FILE [--watch CLASS]");
            System.exit(2);
        }

        RobocodeEngine engine = new RobocodeEngine();
        engine.addBattleListener(new BattleAdaptor() {
            @Override
            public void onRoundStarted(RoundStartedEvent event) {
                System.err.println("Round " + (event.getRound() + 1) + " initializing..");
            }

            @Override
            public void onTurnEnded(TurnEndedEvent event) {
                if (watchPrefix == null || event.getTurnSnapshot() == null) {
                    return;
                }
                for (IRobotSnapshot robot : event.getTurnSnapshot().getRobots()) {
                    if (robot == null || !robot.getName().startsWith(watchPrefix)) {
                        continue;
                    }
                    String out = robot.getOutputStreamSnapshot();
                    if (out != null && !out.isEmpty()) {
                        System.out.print(out);
                        System.out.flush();
                    }
                }
            }

            @Override
            public void onBattleCompleted(BattleCompletedEvent event) {
                sortedResults = event.getSortedResults();
            }
        });

        RobotSpecification[] specs = engine.getLocalRepository(robots);
        if (specs == null || specs.length < 1) {
            System.err.println("Could not load robots: " + robots);
            engine.close();
            System.exit(1);
        }
        BattleSpecification battle = new BattleSpecification(
                rounds, INACTIVITY_TIME, COOLING_RATE,
                new BattlefieldSpecification(WIDTH, HEIGHT), specs);
        engine.runBattle(battle, null, true, false);
        engine.close();

        writeResults(resultsPath);
    }

    /** Write results in the CLI's -results TSV layout (see scripts/duel.py parse_results). */
    private static void writeResults(Path path) throws IOException {
        int n = sortedResults == null ? 0 : sortedResults.length;
        long total = 0;
        for (int i = 0; i < n; i++) {
            total += sortedResults[i].getScore();
        }
        try (BufferedWriter w = Files.newBufferedWriter(path)) {
            w.write("Results for " + rounds + " rounds");
            w.newLine();
            w.write(
                    "Robot Name\t    Total Score    \tSurvival\tSurv Bonus\tBullet Dmg\t"
                            + "Bullet Bonus\tRam Dmg * 2\tRam Bonus\t 1sts \t 2nds \t 3rds \t");
            w.newLine();
            for (int i = 0; i < n; i++) {
                BattleResults r = sortedResults[i];
                long pct = total > 0 ? Math.round(r.getScore() * 100.0 / total) : 0;
                w.write(String.format(
                        Locale.US,
                        "%d: %s\t%d (%d%%)\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t",
                        i + 1,
                        r.getTeamLeaderName(),
                        r.getScore(),
                        pct,
                        r.getSurvival(),
                        r.getLastSurvivorBonus(),
                        r.getBulletDamage(),
                        r.getBulletDamageBonus(),
                        r.getRamDamage() * 2,
                        r.getRamDamageBonus(),
                        r.getFirsts(),
                        r.getSeconds(),
                        r.getThirds()));
                w.newLine();
            }
        }
    }
}
