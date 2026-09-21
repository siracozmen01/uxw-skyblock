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

modules=$(grep -oE '":[a-z-]+"' settings.gradle.kts | tr -d '"')
offences=0

for module in $modules; do
    while IFS= read -r hit; do
        [ -z "$hit" ] && continue
        echo "A document calls $module absent, and the build ships it:"
        echo "  $hit"
        offences=$((offences + 1))
    done < <(grep -rn -- "$module" docs --include='*.md' 2>/dev/null \
        | grep -E 'NOT IMPLEMENTED|PLANNED —' || true)
done

if [ "$offences" -gt 0 ]; then
    echo
    echo "$offences line(s) describe a shipped module as planned or unimplemented."
    echo "A reader comparing the documents to the code can only conclude one of them is lying."
    exit 1
fi

echo "No document calls a shipped module absent."
