#!/usr/bin/env bash
# Regression test for scripts/release.sh, run against a disposable repository and a disposable key.
# Nothing in this repository is committed, built into dist/ or signed, and no real key is used.
#
#   scripts/test-release-workflow.sh
#
# Takes about 30 minutes: it runs three real release builds (one is expected to fail its gates).
# Needs JAVA_HOME (JDK 21) and ANDROID_HOME. Leaves its working directory behind on failure.
set -euo pipefail

source_repo=$(git rev-parse --show-toplevel)
: "${JAVA_HOME:?set JAVA_HOME (JDK 21)}" "${ANDROID_HOME:?set ANDROID_HOME}"
export PATH="$JAVA_HOME/bin:$PATH"

base=$(mktemp -d "${TMPDIR:-/tmp}/wavrecorder-release-test.XXXXXX")
repo=$base/repo
# release.sh's isolated checkouts (kept after a failed build) stay inside this directory.
export TMPDIR=$base/tmp
mkdir -p "$TMPDIR"
passed=0
failed=0
check() { # check <description> <command...>
  local description=$1
  shift
  if "$@" >>"$base/checks.log" 2>&1; then
    echo "PASS  $description"
    passed=$((passed + 1))
  else
    echo "FAIL  $description"
    failed=$((failed + 1))
  fi
}
refuses() { # refuses <expected message> <command...>: the command fails and prints the message
  local expected=$1 output
  shift
  if output=$("$@" 2>&1 </dev/null); then
    echo "unexpectedly succeeded: $*" >&2
    return 1
  fi
  grep -qF "$expected" <<<"$output" || { echo "wrong error for $*: $output" >&2; return 1; }
}
commit() { git -C "$repo" -c user.name=release-test -c user.email=release-test@invalid commit -q -am "$1"; git -C "$repo" rev-parse HEAD; }
release() { (cd "$repo" && scripts/release.sh "$@"); }
release_dir_for() { ls -d "$repo"/dist/*-"${1:0:12}" 2>/dev/null || true; }
record() { sed -nE "s/^$2:[[:space:]]+(.*)$/\1/p" "$1" | sed -n 1p; }
set_record() { # set_record <dir> <key> <value>: rewrites one line of a (copied) build record
  chmod u+w "$1/build-record.txt"
  sed -i -E "s|^($2:[[:space:]]+).*$|\1$3|" "$1/build-record.txt"
}
copy_release() { cp -r "$1" "$2" && chmod -R u+w "$2"; }

echo "Working in $base"

# --- A disposable copy of this working tree, committed in its own repository ---------------------
mkdir -p "$repo"
(cd "$source_repo" && git ls-files -z --cached --others --exclude-standard | tar --null -cf - -T -) | tar -xf - -C "$repo"
git -C "$repo" init -q
# Seed the refusal fixture explicitly: production owner details are now complete.
# This edits only the disposable repository, never the source checkout.
strings=$repo/app/src/main/res/values/strings.xml
sed -i '/<b>Privacy policy<\/b><br>/a [[PLACEHOLDER: SUPPORT EMAIL]]<br>' "$strings"
grep -qF '[[PLACEHOLDER: SUPPORT EMAIL]]<br>' "$strings" || {
  echo "could not seed disposable placeholder fixture" >&2
  exit 1
}
git -C "$repo" add -A
c1=$(commit "release test: snapshot of the working tree")

# --- A disposable key; its random password never appears on a command line ----------------------
key=$base/disposable-upload.jks
head -c 32 /dev/urandom | base64 > "$base/password"
keytool -genkeypair -keystore "$key" -storepass:file "$base/password" -keypass:file "$base/password" \
  -alias upload -keyalg RSA -keysize 2048 -validity 2 -dname "CN=Disposable release test key" >/dev/null 2>&1
sign() { release sign "$@" <"$base/password"; }

# --- 1. The build uses only the selected commit ------------------------------------------------
# An uncommitted edit and a stray bundle in the working tree must not reach the release.
strings=$repo/app/src/main/res/values/strings.xml
sed -i 's/\[\[PLACEHOLDER: SUPPORT EMAIL\]\]/WORKTREE-ONLY-EDIT/' "$strings"
mkdir -p "$repo/app/build/outputs/bundle/release"
foreign=$repo/app/build/outputs/bundle/release/app-release.aab
printf 'not the release' > "$foreign"
echo "Building commit $c1 (with an uncommitted edit in the working tree)..."
check "build: the selected commit builds and passes its gates" release build "$c1"
dir1=$(release_dir_for "$c1")
check "build: release directory created for the selected commit" test -d "$dir1"
check "build: record names the full selected commit" test "$(record "$dir1/build-record.txt" 'source commit')" = "$c1"
check "build: record's tree matches the commit" test "$(record "$dir1/build-record.txt" 'source tree')" = "$(git -C "$repo" rev-parse "$c1^{tree}")"
check "build: uncommitted edit is not in the bundle" bash -c "! unzip -p '$dir1/app-release-unsigned.aab' base/resources.pb | grep -aq WORKTREE-ONLY-EDIT"
check "build: stray working-tree bundle was not used" bash -c "! cmp -s '$foreign' '$dir1/app-release-unsigned.aab'"
check "build: recorded hashes match the archived files" bash -c "
  test \"\$(sha256sum '$dir1/app-release-unsigned.aab' | cut -d' ' -f1)\" = '$(record "$dir1/build-record.txt" 'unsigned bundle SHA-256')' &&
  test \"\$(sha256sum '$dir1/mapping.txt' | cut -d' ' -f1)\" = '$(record "$dir1/build-record.txt" 'mapping.txt SHA-256')'"
check "build: archived mapping equals the mapping embedded in the bundle" bash -c "
  unzip -p '$dir1/app-release-unsigned.aab' BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map | cmp -s - '$dir1/mapping.txt'"
check "build: archived files are read-only" bash -c "! test -w '$dir1/app-release-unsigned.aab' && ! test -w '$dir1/build-record.txt'"
check "build: record notes the owner placeholders" test "$(record "$dir1/build-record.txt" 'owner placeholders')" = yes
check "build: same commit is never rebuilt over its record" refuses "already exists" release build "$c1"
git -C "$repo" checkout -q -- "$strings"

# --- 2. Signing accepts only an intact, verified release directory -----------------------------
check "sign: a loose bundle file is refused" refuses "not a directory" sign "$dir1/app-release-unsigned.aab" "$key" upload
check "sign: owner placeholders are refused" refuses "PLACEHOLDER" sign "$dir1" "$key" upload
check "sign: nothing was signed after the refusal" bash -c "! ls '$dir1'/*-upload-signed.aab 2>/dev/null"

swapped=$base/swapped
copy_release "$dir1" "$swapped"
cp "$repo/app/build/outputs/bundle/release/app-release.aab" "$swapped/app-release-unsigned.aab"
check "sign: a foreign bundle in a release directory is refused" refuses "not the bundle this build recorded" sign "$swapped" "$key" upload

# --- 3. The archived mapping must be the one embedded in this exact bundle ---------------------
mismatch=$base/mapping-mismatch
copy_release "$dir1" "$mismatch"
echo "# a different build" >>"$mismatch/mapping.txt"
set_record "$mismatch" "mapping.txt SHA-256" "$(sha256sum "$mismatch/mapping.txt" | cut -d' ' -f1)"
check "sign: a mapping that differs from the embedded one is refused" refuses "differs from" sign "$mismatch" "$key" upload

missing=$base/mapping-missing
copy_release "$dir1" "$missing"
zip -q -d "$missing/app-release-unsigned.aab" BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map
set_record "$missing" "unsigned bundle SHA-256" "$(sha256sum "$missing/app-release-unsigned.aab" | cut -d' ' -f1)"
check "sign: a bundle without an embedded mapping is refused" refuses "no embedded R8 mapping" sign "$missing" "$key" upload

# --- 4. A failing gate stops the release before anything is kept or signed ----------------------
canary=$repo/app/src/test/java/bukin/wavrecorder/ReleaseGateCanaryTest.kt
cat >"$canary" <<'EOF'
package bukin.wavrecorder

import org.junit.Assert.fail
import org.junit.Test

class ReleaseGateCanaryTest {
    @Test
    fun `a failing test must stop the release`() = fail("deliberately failing release gate")
}
EOF
git -C "$repo" add "$canary"
c2=$(commit "release test: a failing unit test")
echo "Building commit $c2 (expected to fail its gates)..."
check "build: failing gates stop the release" refuses "the release gates failed" release build "$c2"
check "build: nothing was kept for the failed commit" test -z "$(release_dir_for "$c2")"
# A record relabelled with another real commit no longer matches that commit's tree.
other_commit=$base/other-commit
copy_release "$dir1" "$other_commit"
set_record "$other_commit" "source commit" "$c2"
check "sign: a record relabelled with another commit is refused" refuses "doesn't match the record" sign "$other_commit" "$key" upload
git -C "$repo" rm -q "$canary"
commit "release test: remove the failing test" >/dev/null

# --- 5. A successful release archives the exact bundle and its mapping --------------------------
# Test-only stand-ins for the owner details, in this disposable repository only.
sed -i 's/\[\[PLACEHOLDER: \([^]]*\)\]\]/TEST-ONLY \1/g' "$strings"
c3=$(commit "release test: test-only owner details")
echo "Building commit $c3 (expected to pass and be signed)..."
check "build: the test-only commit builds and passes its gates" release build "$c3"
dir3=$(release_dir_for "$c3")
check "build: release directory created for the test-only commit" test -d "$dir3"
check "build: no placeholders left in the test-only commit" test "$(record "$dir3/build-record.txt" 'owner placeholders')" = no
check "sign: the verified release is signed with the disposable key" sign "$dir3" "$key" upload
signed=$(ls "$dir3"/*-upload-signed.aab 2>/dev/null || true)
check "sign: signed bundle and sign record written" test -f "$signed" -a -f "$dir3/sign-record.txt"
check "sign: signature verifies" bash -c "jarsigner -verify '$signed' | grep -q '^jar verified\.'"
check "sign: recorded certificate is the disposable key's" bash -c "
  keytool -list -v -keystore '$key' -storepass:file '$base/password' -alias upload | grep -qF \"\$(sed -nE 's/^upload cert SHA-256:[[:space:]]+//p' '$dir3/sign-record.txt')\""
check "sign: signed bundle holds exactly the verified bundle's entries and bytes" python3 -I -c "
import sys, zipfile
signature = lambda n: n.startswith('META-INF/') and n.rsplit('.', 1)[-1] in ('SF', 'RSA', 'DSA', 'EC', 'MF')
unsigned = {i.filename: i.CRC for i in zipfile.ZipFile('$dir3/app-release-unsigned.aab').infolist()}
signed = {i.filename: i.CRC for i in zipfile.ZipFile('$signed').infolist() if not signature(i.filename)}
sys.exit(0 if unsigned == signed else 1)"
check "sign: archived mapping equals the mapping embedded in the signed bundle" bash -c "
  unzip -p '$signed' BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map | cmp -s - '$dir3/mapping.txt'"
check "sign: recorded signed hash matches the file" test "$(sha256sum "$signed" | cut -d' ' -f1)" = "$(record "$dir3/sign-record.txt" 'signed bundle SHA-256')"
check "sign: record names the built commit" test "$(record "$dir3/sign-record.txt" 'source commit')" = "$c3"
check "sign: a release is never signed twice" refuses "already signed" sign "$dir3" "$key" upload

echo
echo "$passed passed, $failed failed"
if [ "$failed" -eq 0 ]; then
  rm -rf "$base"
else
  echo "Details: $base/checks.log (working directory kept)"
  exit 1
fi
