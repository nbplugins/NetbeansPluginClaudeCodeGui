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
# Extract date from heading (e.g. "# 0.21 (2026-04-16)"), or default to today
HEADING_DATE=$(echo "$FIRST_LINE" | sed -n 's/^# [^ ]* (\(.*\))$/\1/p')
RELEASE_DATE="${HEADING_DATE:-$(date +%Y-%m-%d)}"

if [[ "$CHANGELOG_MM" == "$MM" ]]; then
  if [[ -n "$RELEASE_TAG" ]] && ! grep -qE "^# ${MM_ESCAPED}\.[0-9]+" "$CHANGELOG"; then
    # Stale release signal: published release exists but CHANGELOG was never auto-edited
    # (e.g. branch created from old release commit). Rename heading, don't create new release.
    echo "Stale release signal: renaming to $RELEASE_TAG"
    sed -i "1s|^.*$|# ${RELEASE_TAG} (${RELEASE_DATE})|" "$CHANGELOG"
    git add "$CHANGELOG"
    git commit -m "Released ${RELEASE_TAG}"
    git push origin HEAD
  else
    # Fresh release signal: create new release tag.
    # +1 because we are about to add the CHANGELOG commit on top of HEAD,
    # and the tag must land on that commit so its name matches the commit count.
    if [[ -n "$RELEASE_TAG" ]]; then
      START_PATCH=$(echo "$RELEASE_TAG" | cut -d. -f3)
      N=$(git rev-list "${RELEASE_TAG}..HEAD" --count)
      FULL_VER="${MM}.$((START_PATCH + N + 1))"
    else
      N=$(git rev-list "${BASE_TAG}..HEAD" --count)
      FULL_VER="${MM}.$((N + 1))"
    fi
    echo "Release signal: creating tag $FULL_VER"
    sed '0,/^#/d;/^#/Q' "$CHANGELOG" > "$RELEASE_NOTES" || echo "Release $FULL_VER" > "$RELEASE_NOTES"

    # Auto-edit CHANGELOG: replace "# MM (date)" with "# FULL_VER (date)" to clear the release signal
    sed -i "1s|^.*$|# ${FULL_VER} (${RELEASE_DATE})|" "$CHANGELOG"
    git add "$CHANGELOG"
    git commit -m "Released ${FULL_VER}"
    # Tag the CHANGELOG commit so "git tag --points-at HEAD" finds it in Calculate version
    git tag "$FULL_VER"
    git push origin HEAD
    git push origin "$FULL_VER"
  fi
fi
