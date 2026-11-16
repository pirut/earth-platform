#!/usr/bin/env bash
# Uploads one build's jars to CurseForge: the NeoForge jar, and the Fabric jar if the build has one.
# Used by release.yml for each new release and by curseforge-backfill.yml for older ones.
# Usage: curseforge-upload.sh <folder with earthplatform-$TAG.jar and earthplatform-fabric-$TAG.jar>
# Environment: TAG (combined-NN), VERSION (0.1.0-alpha.NN), CURSEFORGE_TOKEN, CURSEFORGE_PROJECT,
# GITHUB_REPOSITORY. Changelog: docs/release/notes/$TAG.md from the checkout.
# Skips with a notice when the token or project id is missing.
set -euo pipefail
if [ -z "${CURSEFORGE_TOKEN:-}" ] || [ -z "${CURSEFORGE_PROJECT:-}" ]; then
  echo "::notice::No CURSEFORGE_TOKEN secret or CURSEFORGE_PROJECT variable, so $TAG was not uploaded to CurseForge."
  exit 0
fi
api=https://minecraft.curseforge.com/api
agent="$GITHUB_REPOSITORY release workflow"
dist="${1:-dist}"
get() { curl -sS --fail-with-body -A "$agent" -H "X-Api-Token: $CURSEFORGE_TOKEN" "$api/$1"; }
get game/version-types > cf-types.json || { cat cf-types.json; echo "::error::CurseForge refused the token."; exit 1; }
get game/versions > cf-versions.json || { cat cf-versions.json; echo "::error::Could not read CurseForge game versions."; exit 1; }
id_of() { # name type-slug-pattern: CurseForge's id for a game version, loader, Java or side tag
  jq -r --arg n "$1" --arg t "$2" --slurpfile types cf-types.json \
    '($types[0] | map(select(.slug | test($t))) | map(.id)) as $ids
     | map(select(.name == $n and ((.gameVersionTypeID as $g | $ids | index($g)) != null))) | .[0].id // empty' cf-versions.json
}
need() { id=$(id_of "$1" "$2"); [ -n "$id" ] || { echo "::error::CurseForge has no '$1' tag." >&2; return 1; }; echo "$id"; }
minecraft=$(need 1.21.1 '^minecraft-1-21$') || exit 1
neoforge=$(need NeoForge '^modloader$') || exit 1
fabric=$(need Fabric '^modloader$') || exit 1
quilt=$(id_of Quilt '^modloader$')
# Java and side tags only help players filter; leave them off if CurseForge renames them.
extras=$(for tag in "Java 21:^java$" "Client:^environment$" "Server:^environment$"; do id_of "${tag%%:*}" "${tag#*:}"; done | jq -sc '.')
notes="docs/release/notes/$TAG.md"
if [ -f "$notes" ]; then changelog=$(cat "$notes"); else changelog="Earth Platform $TAG"; fi
case "$VERSION" in *-beta.*) type=beta ;; *-alpha.*|*prototype*) type=alpha ;; *) type=release ;; esac
upload() { # display-name jar game-version-ids-json relations-json
  jq -n --arg name "$1" --arg changelog "$changelog" --arg type "$type" \
    --argjson versions "$3" --argjson extras "$extras" --argjson relations "$4" \
    '{displayName: $name, changelog: $changelog, changelogType: "markdown", releaseType: $type,
      gameVersions: ($versions + $extras), relations: {projects: $relations}}' > curseforge-file.json
  curl -sS --fail-with-body -A "$agent" -H "X-Api-Token: $CURSEFORGE_TOKEN" \
    -F "metadata=<curseforge-file.json;type=application/json" \
    -F "file=@$dist/$2;type=application/java-archive" \
    "$api/projects/$CURSEFORGE_PROJECT/upload-file" > curseforge-response.json \
    || { cat curseforge-response.json; echo; echo "::error::CurseForge upload of $2 failed."; exit 1; }
  echo "Uploaded $2 to CurseForge as file $(jq -r .id curseforge-response.json). It goes live after CurseForge's review."
}
optional='[{"slug": "sodium", "type": "optionalDependency"}, {"slug": "irisshaders", "type": "optionalDependency"}]'
upload "Earth Platform $VERSION (NeoForge)" "earthplatform-$TAG.jar" "[$minecraft, $neoforge]" "$optional"
if [ ! -f "$dist/earthplatform-fabric-$TAG.jar" ]; then echo "$TAG has no Fabric jar (Fabric builds start at build 67)."; exit 0; fi
upload "Earth Platform $VERSION (Fabric)" "earthplatform-fabric-$TAG.jar" "$(jq -nc --argjson q "${quilt:-null}" "[$minecraft, $fabric, \$q] | map(select(. != null))")" \
  "$(jq -c '. + [{"slug": "fabric-api", "type": "requiredDependency"}]' <<<"$optional")"
