#!/usr/bin/env bash
# Builds, verifies and signs a Google Play release from one committed revision. See
# docs/RELEASING.md.
#
#   scripts/release.sh build <revision>
#       Exports exactly <revision> (a commit) into a fresh temporary directory, runs every release
#       gate there, and keeps the verified, still unsigned bundle with its R8 mapping, reports and
#       a build record in dist/<package>-<version>-vc<code>-<commit>/ (never overwritten).
#
#   scripts/release.sh sign <release-dir> <upload-keystore> <key-alias>
#       Re-checks that release directory against its build record and signs its bundle with the
#       upload key. jarsigner prompts for the passwords; none are ever passed on a command line.
#
# Provenance comes from the build itself, not from timestamps or the working tree: the bundle is
# produced from the exported commit, the gates run against that same export, and sign accepts only
# a directory written by build whose files still match the hashes recorded there. There is no way
# to hand either command a bundle from anywhere else.
#
# Every check reads a file or a captured variable, never `producer | grep -q`: under pipefail an
# early-exiting grep makes the producer fail with SIGPIPE, which would let a check fail open.
set -euo pipefail

GATES=(clean check lintRelease bundleRelease)
EMBEDDED_MAPPING=BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map

fail() {
  echo "error: $*" >&2
  exit 1
}
usage() {
  sed -n '5,12p' "$0" | sed 's/^# \{0,1\}//' >&2
  exit 2
}
sha256() { sha256sum "$1" | cut -d' ' -f1; }
# The value of "key: value" in a record file (first match), or nothing.
record_value() { sed -nE "s/^$2:[[:space:]]+(.*)$/\1/p" "$1" | sed -n 1p; }

require_tools() {
  local tool
  for tool in git tar unzip zip sha256sum "$@"; do
    command -v "$tool" >/dev/null || fail "$tool not found"
  done
  aapt2=$(ls -d "${ANDROID_HOME:?set ANDROID_HOME}"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1 || true)
  [ -n "$aapt2" ] && [ -x "$aapt2" ] || fail "aapt2 not found under \$ANDROID_HOME/build-tools"
}

# Sets package, version_code, version_name, min_sdk, target_sdk from the bundle's own manifest.
# aapt2 reads the base module's proto-format manifest once it is packaged like an APK.
read_bundle_manifest() {
  local bundle=$1 tmp=$2 badging
  mkdir -p "$tmp/manifest"
  unzip -q -j -DD "$bundle" base/manifest/AndroidManifest.xml base/resources.pb -d "$tmp/manifest"
  (cd "$tmp/manifest" && zip -q -X base-proto.apk AndroidManifest.xml resources.pb)
  badging=$("$aapt2" dump badging "$tmp/manifest/base-proto.apk")
  package=$(sed -nE "s/^package: name='([^']+)'.*/\1/p" <<<"$badging")
  version_code=$(sed -nE "s/^package: .* versionCode='([0-9]+)'.*/\1/p" <<<"$badging")
  version_name=$(sed -nE "s/^package: .* versionName='([^']*)'.*/\1/p" <<<"$badging")
  min_sdk=$(sed -nE "s/^minSdkVersion:'([0-9]+)'.*/\1/p" <<<"$badging")
  target_sdk=$(sed -nE "s/^targetSdkVersion:'([0-9]+)'.*/\1/p" <<<"$badging")
  [ -n "$package" ] && [ -n "$version_code" ] && [ -n "$version_name" ] ||
    fail "could not read the package and version from $bundle"
}

# Fails unless $1 embeds an R8 mapping identical, byte for byte, to the file $2.
check_embedded_mapping() {
  local bundle=$1 mapping=$2 tmp=$3 entries
  entries=$(unzip -Z1 "$bundle")
  grep -qxF "$EMBEDDED_MAPPING" <<<"$entries" ||
    fail "$bundle has no embedded R8 mapping ($EMBEDDED_MAPPING); this app is minified, so a release must carry one"
  unzip -p "$bundle" "$EMBEDDED_MAPPING" > "$tmp/embedded-mapping.txt"
  [ -s "$tmp/embedded-mapping.txt" ] || fail "the R8 mapping embedded in $bundle is empty"
  cmp -s "$tmp/embedded-mapping.txt" "$mapping" ||
    fail "the R8 mapping embedded in $bundle differs from $mapping; they come from different builds"
}

# Prints yes if the bundle's resources still contain owner placeholders, otherwise no.
placeholders_in() {
  local tmp=$2
  unzip -p "$1" base/resources.pb > "$tmp/resources.pb"
  if grep -aq '\[\[PLACEHOLDER' "$tmp/resources.pb"; then echo yes; else echo no; fi
}

# "N tests, F failures, E errors, S skipped" over a directory of JUnit XML results; fails if any
# test failed or none ran.
test_summary() {
  local dir=$1 tests=0 failures=0 errors=0 skipped=0 file line n f e s
  for file in "$dir"/TEST-*.xml; do
    [ -f "$file" ] || continue
    line=$(sed -n '/<testsuite /{p;q}' "$file")
    n=$(sed -nE 's/.* tests="([0-9]+)".*/\1/p' <<<"$line"); f=$(sed -nE 's/.* failures="([0-9]+)".*/\1/p' <<<"$line")
    e=$(sed -nE 's/.* errors="([0-9]+)".*/\1/p' <<<"$line"); s=$(sed -nE 's/.* skipped="([0-9]+)".*/\1/p' <<<"$line")
    tests=$((tests + ${n:-0})); failures=$((failures + ${f:-0})); errors=$((errors + ${e:-0})); skipped=$((skipped + ${s:-0}))
  done
  [ "$tests" -gt 0 ] || fail "no unit test results in $dir"
  [ "$failures" -eq 0 ] && [ "$errors" -eq 0 ] || fail "unit tests failed in $dir: $failures failures, $errors errors"
  echo "$tests tests, $failures failures, $errors errors, $skipped skipped"
}

lint_summary() {
  local report=$1 issues errors warnings
  [ -f "$report" ] || fail "lint report missing: $report"
  issues=$(grep -o 'severity="[A-Za-z]*"' "$report" || true)
  errors=$(grep -cE '"(Error|Fatal)"' <<<"$issues" || true)
  warnings=$(grep -c '"Warning"' <<<"$issues" || true)
  [ "$errors" -eq 0 ] || fail "lint reported $errors errors ($report)"
  echo "$errors errors, $warnings warnings"
}

cmd_build() {
  [ $# -eq 1 ] || usage
  local revision=$1 repo commit tree existing work src log status out_dir toolchain
  require_tools
  [ -n "${JAVA_HOME:-}" ] || fail "set JAVA_HOME (JDK 21)"
  repo=$(git rev-parse --show-toplevel)
  commit=$(git -C "$repo" rev-parse --verify --quiet "$revision^{commit}") || fail "not a commit: $revision"
  tree=$(git -C "$repo" rev-parse "$commit^{tree}")
  existing=$(ls -d "$repo"/dist/*-"${commit:0:12}" 2>/dev/null || true)
  [ -z "$existing" ] || fail "$existing already exists; release records are never overwritten"

  work=$(mktemp -d "${TMPDIR:-/tmp}/wavrecorder-release.XXXXXX")
  src=$work/src
  log=$work/build.log
  mkdir -p "$src"
  # Exactly the committed tree: nothing from the working tree, untracked files or earlier builds.
  git -C "$repo" archive --format=tar "$commit" | tar -xf - -C "$src"

  echo "Building $commit in $src"
  echo "Gates: ./gradlew --no-daemon --continue ${GATES[*]} (about 10 minutes; log: $log)"
  status=0
  (cd "$src" && ./gradlew --no-daemon --console=plain --continue "${GATES[@]}") > "$log" 2>&1 || status=$?
  if [ "$status" -ne 0 ]; then
    tail -n 30 "$log" >&2
    fail "the release gates failed (exit $status); nothing was kept or signed. Isolated checkout and log left in $work"
  fi

  local bundle=$src/app/build/outputs/bundle/release/app-release.aab
  local mapping=$src/app/build/outputs/mapping/release/mapping.txt
  [ -f "$bundle" ] || fail "the build produced no bundle"
  [ -f "$mapping" ] || fail "the build produced no R8 mapping; the release build must be minified"
  check_embedded_mapping "$bundle" "$mapping" "$work"
  read_bundle_manifest "$bundle" "$work"
  local tests_debug tests_release lint_debug lint_release placeholders
  tests_debug=$(test_summary "$src/app/build/test-results/testDebugUnitTest")
  tests_release=$(test_summary "$src/app/build/test-results/testReleaseUnitTest")
  lint_debug=$(lint_summary "$src/app/build/reports/lint-results-debug.xml")
  lint_release=$(lint_summary "$src/app/build/reports/lint-results-release.xml")
  placeholders=$(placeholders_in "$bundle" "$work")

  toolchain=$(cd "$src" && ./gradlew --no-daemon -q --console=plain buildEnvironment 2>/dev/null || true)
  local gradle_version agp_version kotlin_version java_version aapt2_version
  gradle_version=$(sed -nE 's#^distributionUrl=.*gradle-([0-9.]+)-(bin|all)\.zip#\1#p' "$src/gradle/wrapper/gradle-wrapper.properties")
  agp_version=$(grep -oE 'com\.android\.tools\.build:gradle:[0-9][^ ]*' <<<"$toolchain" | sed -n '1s/.*://p')
  kotlin_version=$(grep -oE 'org\.jetbrains\.kotlin:kotlin-gradle-plugin:[0-9][^ ]*' <<<"$toolchain" | sed -n '1s/.*://p')
  java_version=$("$JAVA_HOME/bin/java" -version 2>&1 | sed -n 1p)
  aapt2_version=$("$aapt2" version 2>&1 | sed -n 1p)

  out_dir=$repo/dist/$package-$version_name-vc$version_code-${commit:0:12}
  [ ! -e "$out_dir" ] || fail "$out_dir already exists; release records are never overwritten"
  mkdir -p "$out_dir"
  cp "$bundle" "$out_dir/app-release-unsigned.aab"
  cp "$work/embedded-mapping.txt" "$out_dir/mapping.txt"
  cp "$log" "$out_dir/build.log"
  cp "$src/app/build/reports/lint-results-debug.xml" "$src/app/build/reports/lint-results-release.xml" "$out_dir/"
  tar -czf "$out_dir/unit-test-results.tar.gz" -C "$src/app/build/test-results" testDebugUnitTest testReleaseUnitTest
  {
    echo "status:                  verified, unsigned"
    echo "source commit:           $commit"
    echo "source tree:             $tree"
    echo "requested revision:      $revision"
    echo "checkout:                git archive of the commit into a fresh directory"
    echo "built at (UTC):          $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "gates:                   ./gradlew --no-daemon --continue ${GATES[*]}: passed"
    echo "unit tests, debug:       $tests_debug"
    echo "unit tests, release:     $tests_release"
    echo "lint, debug:             $lint_debug"
    echo "lint, release:           $lint_release"
    echo "package:                 $package"
    echo "versionCode:             $version_code"
    echo "versionName:             $version_name"
    echo "minSdk / targetSdk:      $min_sdk / $target_sdk"
    echo "Gradle:                  $gradle_version"
    echo "Android Gradle plugin:   ${agp_version:-unknown}"
    echo "Kotlin Gradle plugin:    ${kotlin_version:-unknown}"
    echo "JDK:                     $java_version"
    echo "aapt2:                   $aapt2_version"
    echo "unsigned bundle SHA-256: $(sha256 "$out_dir/app-release-unsigned.aab")"
    echo "mapping.txt SHA-256:     $(sha256 "$out_dir/mapping.txt")"
    echo "mapping source:          embedded in the bundle, identical to the build's mapping.txt"
    echo "owner placeholders:      $placeholders"
  } > "$out_dir/build-record.txt"
  chmod a-w "$out_dir"/*
  rm -rf "$work"

  echo
  cat "$out_dir/build-record.txt"
  echo
  echo "Verified release candidate: $out_dir"
  [ "$placeholders" = no ] || echo "Note: the bundle still contains [[PLACEHOLDER: ...]] text; sign will refuse it."
}

cmd_sign() {
  [ $# -eq 3 ] || usage
  local dir=$1 keystore=$2 alias_name=$3 repo record keystore_abs
  require_tools jarsigner keytool cmp
  repo=$(git rev-parse --show-toplevel)
  [ -d "$dir" ] || fail "not a directory: $dir (sign takes a release directory written by '$0 build')"
  dir=$(realpath "$dir")
  record=$dir/build-record.txt
  [ -f "$record" ] && [ -f "$dir/app-release-unsigned.aab" ] && [ -f "$dir/mapping.txt" ] ||
    fail "$dir is not a release directory written by '$0 build'"
  [ "$(record_value "$record" status)" = "verified, unsigned" ] || fail "$record does not record a verified build"
  [ ! -e "$dir/sign-record.txt" ] || fail "$dir is already signed; never sign a release twice"

  # The files must be exactly the ones the verified build recorded.
  [ "$(sha256 "$dir/app-release-unsigned.aab")" = "$(record_value "$record" 'unsigned bundle SHA-256')" ] ||
    fail "app-release-unsigned.aab is not the bundle this build recorded"
  [ "$(sha256 "$dir/mapping.txt")" = "$(record_value "$record" 'mapping.txt SHA-256')" ] ||
    fail "mapping.txt is not the mapping this build recorded"
  local commit tree
  commit=$(record_value "$record" 'source commit')
  tree=$(record_value "$record" 'source tree')
  [ "$(git -C "$repo" rev-parse --verify --quiet "$commit^{tree}" || true)" = "$tree" ] ||
    fail "source commit $commit is not in this repository, or its tree doesn't match the record"

  # Global, not local: the EXIT trap runs after this function has returned.
  work=$(mktemp -d)
  trap 'rm -rf "${work:-}"' EXIT
  local bundle=$dir/app-release-unsigned.aab
  check_embedded_mapping "$bundle" "$dir/mapping.txt" "$work"
  if grep -qE '^META-INF/[^/]+\.(SF|RSA|DSA|EC)$' <<<"$(unzip -Z1 "$bundle")"; then
    fail "$bundle is already signed"
  fi
  [ "$(placeholders_in "$bundle" "$work")" = no ] ||
    fail "the bundle still contains [[PLACEHOLDER: ...]] text; resolve the owner details (docs/play/PUBLICATION-CHECKLIST.md), commit, and build again"
  read_bundle_manifest "$bundle" "$work"

  [ -f "$keystore" ] || fail "keystore not found: $keystore"
  keystore_abs=$(realpath "$keystore")
  case "$keystore_abs/" in
    "$repo"/*) fail "the keystore is inside the repository ($keystore_abs); keep it outside, see docs/RELEASING.md" ;;
  esac

  local signed=$dir/$package-$version_name-vc$version_code-upload-signed.aab
  echo "Signing $bundle (commit $commit)"
  echo "  with alias '$alias_name' from $keystore_abs"
  echo "jarsigner will now ask for the keystore password (and the key password if different)."
  jarsigner -keystore "$keystore_abs" -sigalg SHA256withRSA -digestalg SHA-256 \
    -signedjar "$signed" "$bundle" "$alias_name"

  # Not -strict: an upload certificate is self-signed by design (Play accepts it), and -strict turns
  # that warning into a failure. What matters is that the signature verifies and covers every entry.
  local verify_output cert_info cert_sha256
  verify_output=$(jarsigner -verify "$signed") || fail "signature verification failed for $signed"
  grep -q '^jar verified\.' <<<"$verify_output" || fail "signature verification failed for $signed"
  if grep -q 'unsigned entries' <<<"$verify_output"; then
    fail "$signed has unsigned entries"
  fi
  check_embedded_mapping "$signed" "$dir/mapping.txt" "$work"
  cert_info=$(keytool -printcert -jarfile "$signed")
  cert_sha256=$(sed -nE 's/^[[:space:]]*SHA256: (.*)$/\1/p' <<<"$cert_info" | sed -n 1p)
  [ -n "$cert_sha256" ] || fail "could not read the signing certificate from $signed"

  {
    echo "status:                  signed for upload"
    echo "source commit:           $commit"
    echo "signed at (UTC):         $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "key alias:               $alias_name"
    echo "upload cert SHA-256:     $cert_sha256"
    echo "unsigned bundle SHA-256: $(sha256 "$bundle")"
    echo "signed bundle SHA-256:   $(sha256 "$signed")"
    echo "signed bundle:           $(basename "$signed")"
    echo "mapping.txt SHA-256:     $(sha256 "$dir/mapping.txt") (embedded in the signed bundle too)"
  } > "$dir/sign-record.txt"
  chmod a-w "$signed" "$dir/sign-record.txt"

  echo
  cat "$record" "$dir/sign-record.txt"
  echo
  echo "Upload: $signed"
  echo "Copy $dir to durable storage outside the repository before uploading."
}

[ $# -ge 1 ] || usage
command=$1
shift
case "$command" in
  build) cmd_build "$@" ;;
  sign) cmd_sign "$@" ;;
  *) usage ;;
esac
