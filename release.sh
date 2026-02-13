#!/bin/bash
set -euo pipefail

# Release script for sbt-azure-devops-credentials
# Usage: ./release.sh <version>
#        ./release.sh --finish <version>
# Example: ./release.sh 0.0.6

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

error() {
    echo -e "${RED}Error: $1${NC}" >&2
    exit 1
}

warn() {
    echo -e "${YELLOW}Warning: $1${NC}" >&2
}

info() {
    echo -e "${GREEN}==> $1${NC}"
}

validate_version() {
    local version=$1
    if ! [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        error "Invalid version format: $version (expected X.Y.Z, e.g., 0.0.6)"
    fi
}

check_version_bump() {
    local current=$1
    local target=$2
    local expected="${current%-SNAPSHOT}"

    if [[ "$current" == *"-SNAPSHOT" && "$target" != "$expected" ]]; then
        echo ""
        warn "Unexpected version jump!"
        echo "  Current version: $current"
        echo "  Expected release: $expected"
        echo "  Requested release: $target"
        echo ""
        read -p "Are you sure you want to release $target instead of $expected? [y/N] " -n 1 -r
        echo ""
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            echo "Release cancelled."
            exit 0
        fi
    fi
}

get_previous_tag() {
    git describe --tags --abbrev=0 2>/dev/null || echo ""
}

generate_changelog() {
    local prev_tag=$1
    if [[ -n "$prev_tag" ]]; then
        git log --pretty=format:"  - %s" "$prev_tag"..HEAD
    else
        git log --pretty=format:"  - %s"
    fi
}

# Load Sonatype credentials from env vars, pass, or prompt and store in pass
load_sonatype_credentials() {
    if [[ -n "${SONATYPE_USERNAME:-}" && -n "${SONATYPE_PASSWORD:-}" ]]; then
        return
    fi

    if command -v pass &>/dev/null && pass show sonatype/username &>/dev/null 2>&1; then
        info "Loading Sonatype credentials from pass"
        SONATYPE_USERNAME=$(pass show sonatype/username)
        SONATYPE_PASSWORD=$(pass show sonatype/password)
        export SONATYPE_USERNAME SONATYPE_PASSWORD
        return
    fi

    echo ""
    warn "Sonatype credentials not found in environment or pass."
    echo "These are needed to upload to Maven Central."
    echo ""
    read -p "Sonatype username: " SONATYPE_USERNAME
    read -s -p "Sonatype password: " SONATYPE_PASSWORD
    echo ""

    if [[ -z "$SONATYPE_USERNAME" || -z "$SONATYPE_PASSWORD" ]]; then
        error "Sonatype credentials are required for upload."
    fi

    if command -v pass &>/dev/null; then
        read -p "Save credentials to pass for future releases? [Y/n] " -n 1 -r
        echo ""
        if [[ ! $REPLY =~ ^[Nn]$ ]]; then
            echo "$SONATYPE_USERNAME" | pass insert -e sonatype/username
            echo "$SONATYPE_PASSWORD" | pass insert -e sonatype/password
            info "Credentials saved to pass"
        fi
    fi

    export SONATYPE_USERNAME SONATYPE_PASSWORD
}

usage() {
    echo "Usage: $0 [--dry-run] <version>"
    echo "       $0 --finish <version>"
    echo ""
    echo "Options:"
    echo "  --dry-run    Show what would be done without making changes"
    echo "  --finish     Complete release after publishing on Maven Central"
    echo ""
    echo "Example: $0 0.0.6"
}

if [[ $# -lt 1 ]]; then
    usage
    exit 1
fi

# Handle --finish flag for post-upload steps
if [[ "$1" == "--finish" ]]; then
    if [[ $# -ne 2 ]]; then
        echo "Usage: $0 --finish <version>"
        exit 1
    fi
    VERSION=$2
    validate_version "$VERSION"
    NEXT_VERSION="${VERSION%.*}.$((${VERSION##*.} + 1))-SNAPSHOT"

    if ! git describe --tags --exact-match HEAD 2>/dev/null | grep -q "v$VERSION"; then
        error "HEAD is not tagged as v$VERSION. Did you run './release.sh $VERSION' first?"
    fi

    info "Bumping to next snapshot version: $NEXT_VERSION"
    echo "ThisBuild / version := \"$NEXT_VERSION\"" > version.sbt

    git add version.sbt
    git commit -m "chore: bump version to $NEXT_VERSION"

    info "Pushing to origin"
    git push origin master --tags

    echo ""
    echo -e "${GREEN}Release $VERSION complete!${NC}"
    exit 0
fi

DRY_RUN=false
while [[ $# -gt 0 ]]; do
    case $1 in
        --dry-run) DRY_RUN=true; shift ;;
        --help|-h) usage; exit 0 ;;
        *) VERSION=$1; shift ;;
    esac
done

if [[ -z "${VERSION:-}" ]]; then
    usage
    exit 1
fi

validate_version "$VERSION"

if git tag -l | grep -q "^v$VERSION$"; then
    error "Tag v$VERSION already exists. Did you mean a different version?"
fi

# Check for uncommitted changes
if ! git diff --quiet || ! git diff --cached --quiet; then
    error "You have uncommitted changes. Please commit or stash them first."
fi

CURRENT_VERSION=$(grep -oP 'version := "\K[^"]+' version.sbt || echo "unknown")
check_version_bump "$CURRENT_VERSION" "$VERSION"

NEXT_VERSION="${VERSION%.*}.$((${VERSION##*.} + 1))-SNAPSHOT"
PREV_TAG=$(get_previous_tag)

echo ""
echo -e "${GREEN}============================================${NC}"
echo -e "${GREEN}Release Summary${NC}"
echo -e "${GREEN}============================================${NC}"
echo ""
echo "  Current version:    $CURRENT_VERSION"
echo "  Version to release: $VERSION"
echo "  Previous release:   ${PREV_TAG:-"(none)"}"
echo "  Next dev version:   $NEXT_VERSION"
echo ""
echo -e "${YELLOW}Changes since ${PREV_TAG:-"beginning"}:${NC}"
echo ""
CHANGELOG=$(generate_changelog "$PREV_TAG")
if [[ -z "$CHANGELOG" ]]; then
    echo "  (no commits)"
else
    echo "$CHANGELOG"
fi
echo ""
echo -e "${GREEN}============================================${NC}"
echo ""

if $DRY_RUN; then
    echo -e "${YELLOW}=== DRY RUN COMPLETE (no changes made) ===${NC}"
    exit 0
fi

read -p "Do you want to proceed with the release? [y/N] " -n 1 -r
echo ""
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "Release cancelled."
    exit 0
fi

echo ""

load_sonatype_credentials

# Update version.sbt
info "Updating version.sbt"
echo "ThisBuild / version := \"$VERSION\"" > version.sbt

# Update README.md
info "Updating README.md"
sed -i "s/sbt-azure-devops-credentials\" % \"[^\"]*\"/sbt-azure-devops-credentials\" % \"$VERSION\"/" README.md

# Commit and tag
info "Committing and tagging"
git add version.sbt README.md
git commit -m "chore: release v$VERSION"
git tag "v$VERSION"

# Build signed bundle
info "Building signed bundle"
./make_tarball_signed.sh

BUNDLE="sbt-azure-devops-credentials_2.12_1.0-$VERSION.zip"

# Upload to Sonatype Central Portal
info "Uploading to Sonatype Central Portal"
AUTH_TOKEN=$(printf "%s:%s" "$SONATYPE_USERNAME" "$SONATYPE_PASSWORD" | base64)

DEPLOYMENT_ID=$(curl --silent --fail --request POST \
    --header "Authorization: Bearer $AUTH_TOKEN" \
    --form "bundle=@$BUNDLE" \
    "https://central.sonatype.com/api/v1/publisher/upload?name=sbt-azure-devops-credentials-$VERSION&publishingType=USER_MANAGED")

if [[ -n "$DEPLOYMENT_ID" ]]; then
    echo ""
    echo -e "${GREEN}============================================${NC}"
    echo -e "${GREEN}Bundle uploaded successfully!${NC}"
    echo ""
    echo "  Deployment ID: $DEPLOYMENT_ID"
    echo ""
    echo "Next steps:"
    echo "1. Go to https://central.sonatype.com/publishing/deployments"
    echo "2. Verify the deployment and click 'Publish'"
    echo "3. Run: ./release.sh --finish $VERSION"
    echo -e "${GREEN}============================================${NC}"
else
    error "Upload failed. Check your Sonatype credentials."
fi
