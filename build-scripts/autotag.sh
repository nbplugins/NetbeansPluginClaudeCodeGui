#!/bin/bash
set -e

CHANGELOG=CHANGELOG.md
RELEASE_NOTES=RELNOTES.md

git fetch --prune --tags

# MAJOR.MINOR from pom.xml
POM_VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout | sed 's/-.*//')
MM=$(echo "$POM_VERSION" | cut -d. -f1-2)
MM_ESCAPED="${MM//./\\.}"

# Last base tag (2 components) for current MM
BASE_TAG=$(git tag | grep -E "^${MM_ESCAPED}$" | sort -V | tail -1)

# Last release tag (3 components) for current MM
RELEASE_TAG=$(git tag | grep -E "^${MM_ESCAPED}\.[0-9]+$" | sort -V | tail -1)

echo "POM MM=$MM  BASE_TAG=$BASE_TAG  RELEASE_TAG=$RELEASE_TAG"

# If release tag exists — check its status
if [[ -n "$RELEASE_TAG" ]]; then
  IS_DRAFT=$(gh release view "$RELEASE_TAG" --json isDraft -q .isDraft 2>/dev/null || echo "notfound")
  if [[ "$IS_DRAFT" == "true" ]]; then
    # Tag exists but release not published — delete tag and draft, recalculate
    echo "Removing unpublished release tag $RELEASE_TAG"
    git tag -d "$RELEASE_TAG" || true
    git push origin ":refs/tags/$RELEASE_TAG" || true
    gh release delete "$RELEASE_TAG" --yes 2>/dev/null || true
    RELEASE_TAG=
  fi
  # If published: continue — CHANGELOG no longer has the release signal (auto-edited after release)
fi

# Create base tag if missing
if [[ -z "$BASE_TAG" ]]; then
  echo "Creating base tag $MM"
  git tag "$MM"
  git push origin "$MM"
  BASE_TAG="$MM"
fi

# Version: count from last release tag (3-comp) if available, else from base tag (2-comp)
if [[ -n "$RELEASE_TAG" ]]; then
  START_PATCH=$(echo "$RELEASE_TAG" | cut -d. -f3)
  N=$(git rev-list "${RELEASE_TAG}..HEAD" --count)
  FULL_VER="${MM}.$((START_PATCH + N))"
else
  N=$(git rev-list "${BASE_TAG}..HEAD" --count)
  FULL_VER="${MM}.${N}"
fi
echo "FULL_VER=$FULL_VER"

# Release signal: CHANGELOG starts with "# MAJOR.MINOR (..."
CHANGELOG_MM=""
FIRST_LINE=$(head -n 1 "$CHANGELOG" 2>/dev/null || echo "")
if [[ "$(echo "$FIRST_LINE" | cut -d' ' -f1)" == "#" ]]; then
  CHANGELOG_MM=$(echo "$FIRST_LINE" | cut -d' ' -f2)
fi

if [[ "$CHANGELOG_MM" == "$MM" ]]; then
  echo "Release signal: creating tag $FULL_VER"
  sed '0,/^#/d;/^#/Q' "$CHANGELOG" > "$RELEASE_NOTES" || echo "Release $FULL_VER" > "$RELEASE_NOTES"
  git tag "$FULL_VER"
  git push origin "$FULL_VER"

  # Auto-edit CHANGELOG: replace "# MM (date)" with "# FULL_VER (date)" to clear the release signal
  sed -i "s|^# ${MM_ESCAPED} |# ${FULL_VER} |" "$CHANGELOG"
  git add "$CHANGELOG"
  git commit -m "Released ${FULL_VER}"
  git push origin HEAD
fi
