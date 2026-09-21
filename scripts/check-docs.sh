#!/usr/bin/env bash
# Checks the documents against the build.
#
# docs/ is deliberately outside version control, so this cannot be a test: CI checks out a tree with
# no documents in it, and a guard that fails where it cannot see its subject only teaches the next
# person to ignore it. A tracked script that a developer runs where the documents live is the honest
# home for a check over untracked files.
#
# Run from the repository root:  scripts/check-docs.sh
set -euo pipefail

cd "$(dirname "$0")/.."

if [ ! -d docs ]; then
    echo "docs/ is not here, so there is nothing to check. It is outside version control on purpose."
    exit 0
fi

offences=0

# 1. A module the build ships must not be described as absent.
modules=$(grep -oE '":[a-z-]+"' settings.gradle.kts | tr -d '"')
for module in $modules; do
    while IFS= read -r hit; do
        [ -z "$hit" ] && continue
        echo "A document calls $module absent, and the build ships it:"
        echo "  $hit"
        offences=$((offences + 1))
    done < <(grep -rn -- "$module" docs --include='*.md' 2>/dev/null \
        | grep -E 'NOT IMPLEMENTED|PLANNED —' || true)
done

# 2. A command a document tells an operator to type must be one the tree declares.
#
# Four documents published /is admin rollback and one published /is recalc while neither existed.
# An operator reading a document and typing what it says is the whole point of writing one.
#
# The list of verbs comes from the command tree itself, written down by
# CommandVerbsAreWrittenDownTest. This used to grep the sources for literal("x"), which stopped
# being true the moment a branch was built by a helper that takes the word as an argument: /is vault
# still existed and the grep said it did not.
verbs_file=bukkit-adapter/build/command-verbs.txt
if [ ! -f "$verbs_file" ]; then
    echo "The list of command verbs is not here yet. Run ./gradlew :bukkit-adapter:test first,"
    echo "which writes $verbs_file out of the tree the plugin registers."
    exit 1
fi

commands=$(grep -rohE '`/(is|island) [a-z]+' docs --include='*.md' 2>/dev/null \
    | sed -E 's/`\/(is|island) //' | sort -u || true)
for command in $commands; do
    if ! grep -qxF "$command" "$verbs_file"; then
        echo "A document tells an operator to type /is $command, and the command tree has no such verb."
        offences=$((offences + 1))
    fi
done

if [ "$offences" -gt 0 ]; then
    echo
    echo "$offences difference(s) between the documents and the code."
    echo "A reader comparing the two can only conclude one of them is lying."
    exit 1
fi

echo "The documents and the code agree."
