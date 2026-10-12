#!/usr/bin/env bash
# Builds phonetools.apk (debug-signed, see build.sh) and attaches it to the GitHub release v<versionName> from
# AndroidManifest.xml, creating that release at the current commit if there is none yet. Running it again for the
# same version replaces the attached APK; bump versionName for a new release. Run from Git Bash.
# Signs in with GITHUB_TOKEN if set, otherwise with the github.com login git already uses to push.
set -e
cd "$(dirname "$0")"

REPO=$(git remote get-url origin | sed -E 's#\.git$##; s#^.*github\.com[:/]##')
VERSION=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml)
TAG="v$VERSION"
ASSET="phonetools-$VERSION-debug.apk"
COMMIT=$(git rev-parse HEAD)

if [ -n "$(git status --porcelain)" ]; then
    echo "Commit or stash your changes first: the APK must match the commit the release points at." >&2
    exit 1
fi
if [ -z "$(git branch -r --contains "$COMMIT")" ]; then
    echo "Push $(git rev-parse --abbrev-ref HEAD) first: GitHub cannot tag a commit it does not have." >&2
    exit 1
fi

TOKEN=${GITHUB_TOKEN:-$(printf 'protocol=https\nhost=github.com\n\n' | git credential fill | sed -n 's/^password=//p')}
if [ -z "$TOKEN" ]; then
    echo "No GitHub login found: set GITHUB_TOKEN (a token with repo access)." >&2
    exit 1
fi
# GitHub pretty-prints its JSON; with the whitespace gone, the fields below can be picked out with grep.
api() {
    curl -sS -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" \
        -H "X-GitHub-Api-Version: 2022-11-28" "$@" | tr -d ' \n\r'
}

./build.sh

# The release's own id is the first "id" in its JSON; its author and assets come later.
release=$(api "https://api.github.com/repos/$REPO/releases/tags/$TAG")
id=$(printf '%s' "$release" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
if [ -z "$id" ]; then
    echo "Creating release $TAG at ${COMMIT:0:7}"
    release=$(api -X POST "https://api.github.com/repos/$REPO/releases" -d "{\"tag_name\":\"$TAG\",
        \"target_commitish\":\"$COMMIT\",\"name\":\"Phone tools $VERSION\",
        \"body\":\"Debug-signed build of ${COMMIT:0:7}. Install with: adb install -r $ASSET\"}")
    id=$(printf '%s' "$release" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
    if [ -z "$id" ]; then
        echo "GitHub refused to create the release: $release" >&2
        exit 1
    fi
fi

# An asset name can only be used once per release, so remove an earlier upload of this version.
old=$(printf '%s' "$release" | grep -o "\"id\":[0-9]*,\"node_id\":\"[^\"]*\",\"name\":\"$ASSET\"" | cut -d, -f1 | cut -d: -f2)
if [ -n "$old" ]; then
    echo "Replacing the $ASSET attached earlier"
    api -X DELETE "https://api.github.com/repos/$REPO/releases/assets/$old" >/dev/null
fi

uploaded=$(api -X POST -H "Content-Type: application/vnd.android.package-archive" --data-binary @phonetools.apk \
    "https://uploads.github.com/repos/$REPO/releases/$id/assets?name=$ASSET")
url=$(printf '%s' "$uploaded" | grep -o '"browser_download_url":"[^"]*"' | cut -d'"' -f4)
if [ -z "$url" ]; then
    echo "Upload failed: $uploaded" >&2
    exit 1
fi
echo "Attached $ASSET to $TAG: $url"
