# MechGarden — task runner
#
# Recipes are thin wrappers. Engine/robot logic lives in scripts/ as Python
# standard-library tools plus a small shell launcher for the Robocode UI.

# List available recipes.
default:
    @just --list

# Manage the Robocode engine. Examples:
#   just robocode install
#   just robocode install --force
#   just robocode uninstall
[positional-arguments]
robocode *args:
    @scripts/robocode.py "$@"

# Launch the Robocode UI.
run:
    scripts/run.sh

# Build all robots.
build:
    ./gradlew build

# Remove build outputs produced by `just build`.
clean:
    ./gradlew clean

# Format all Kotlin sources and Gradle scripts with ktlint (via Spotless).
fmt:
    ./gradlew spotlessApply

# Check formatting without modifying files.
lint:
    ./gradlew spotlessCheck

# Deploy a specific bot's jar, e.g. `just deploy ronin` / `just deploy fencer`.
deploy bot:
    ./gradlew ":bots:{{bot}}:deploy"

# Run 1v1 headless duels. Examples:
#   just duel --robot Ronin --enemy shadow --rounds 100
#   just duel --robot zen.Ronin --catalog basic --rounds 100
#   just duel -r Ronin -e sample.Crazy -n 1
#   just duel -r Ronin -c basic -n 100
[positional-arguments]
duel *args:
    @scripts/duel.py "$@"

# Manage reference robot downloads. Examples:
#   just refs list --all --query shadow
#   just refs download --catalog basic
#   just refs deploy --catalog basic
#   just refs undeploy --catalog basic
#   just refs download --jar shadow
#   just refs index
[positional-arguments]
refs *args:
    @scripts/refs.py "$@"
