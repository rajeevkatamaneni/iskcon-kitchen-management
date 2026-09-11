#!/usr/bin/env bash
#
# control-T-064 — does collapsing the nested stub configurations actually collapse contexts?
#
# T-064's whole claim is a number, and a number is exactly the kind of claim that can be reported
# without being true. This does the collapse for real, on one package, measures the context count
# before and after with the suite's own census, and then puts the tree back.
#
# It also guards against the failure this project keeps meeting: a patch that matches nothing and a
# control that passes because of it. Every anchor is counted before it is used, the count is
# asserted, and `set -e` means a miss stops the script instead of producing a tidy green log.
#
# The restore is from a cp snapshot, never `git checkout --`: other builders are working in this
# checkout and a checkout would take their uncommitted work with it.
#
# `--rerun` on both measurements, because the first attempt at this control produced no "before"
# file at all: Gradle had already run those tests with the same inputs and reported the task
# UP-TO-DATE, which is a silent way for a control to measure nothing and say nothing about it.

set -euo pipefail

ROOT=/Users/Rajeev/Workspace/kitchen-management-system
PKG="$ROOT/backend/src/test/java/org/iskcon/kms/staff"
SHARED="$ROOT/backend/src/test/java/org/iskcon/kms"
SNAP="${TMPDIR:-/tmp}/control-T-064-snapshot"
COLLAPSE="$1"   # path to collapse.py

echo "== 1. the anchors, counted before anything is touched =="
files=$(ls "$PKG"/*.java | wc -l | tr -d ' ')
imports=$(grep -l '@Import([A-Za-z]*\.StubVerifierConfiguration\.class)' "$PKG"/*.java | wc -l | tr -d ' ')
nested=$(grep -l 'class StubVerifierConfiguration' "$PKG"/*.java | wc -l | tr -d ' ')
echo "   java files in staff/:                    $files"
echo "   with an @Import of their own stub config: $imports"
echo "   with a nested StubVerifierConfiguration:  $nested"
[ "$files" -eq 9 ]   || { echo "FAIL: expected 9 files"; exit 1; }
[ "$imports" -eq 9 ] || { echo "FAIL: expected 9 imports"; exit 1; }
[ "$nested" -eq 9 ]  || { echo "FAIL: expected 9 nested configurations"; exit 1; }

echo
echo "== 2. before: how many contexts does this package build today? =="
rm -f "$ROOT/backend/build/reports/control-before.txt"
"$ROOT/tools/work-lock.sh" run verify "cd $ROOT/backend && ./gradlew test --no-daemon --rerun -q -PcontextCensus \
  -PtestJvmArgs='-Dkms.context.census.out=build/reports/control-before.txt -Dkms.context.census.every=100' \
  --tests 'org.iskcon.kms.staff.*'" > /dev/null
grep -E "contexts built|distinct cache keys|ignoring" "$ROOT/backend/build/reports/control-before.txt"

echo
echo "== 3. snapshot, then collapse =="
rm -rf "$SNAP"; mkdir -p "$SNAP"
cp -R "$PKG" "$SNAP/staff"
cat > "$SHARED/SharedStubs.java" <<'JAVA'
package org.iskcon.kms;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Temporary, for control-T-064 only. Deleted by the same script that writes it. */
@TestConfiguration
public class SharedStubs {

	@Bean
	@Primary
	StubTokenVerifier stubTokenVerifier() {
		return new StubTokenVerifier();
	}
}
JAVA
cat > "$SHARED/StubTokenVerifier.java" <<'JAVA'
package org.iskcon.kms;

import java.util.HashMap;
import java.util.Map;
import org.iskcon.kms.auth.TokenVerifier;

/** Temporary, for control-T-064 only. Deleted by the same script that writes it. */
public class StubTokenVerifier implements TokenVerifier {

	private final Map<String, VerifiedSubject> accepted = new HashMap<>();

	public void accept(String uid) {
		accepted.put("valid-token", new VerifiedSubject(uid, uid + "@example.com", "+919000000000"));
	}

	public void reset() {
		accepted.clear();
	}

	@Override
	public VerifiedSubject verify(String idToken) throws InvalidTokenException {
		VerifiedSubject subject = accepted.get(idToken);
		if (subject == null) {
			throw new InvalidTokenException("Unrecognised token");
		}
		return subject;
	}
}
JAVA
python3 "$COLLAPSE" "$PKG"/*.java

echo "   proof the tree actually changed:"
git -C "$ROOT" diff --stat -- backend/src/test/java/org/iskcon/kms/staff
changed=$(git -C "$ROOT" diff --name-only -- backend/src/test/java/org/iskcon/kms/staff | wc -l | tr -d ' ')
[ "$changed" -eq 9 ] || { echo "FAIL: expected 9 changed files, got $changed"; exit 1; }

echo
echo "== 4. after: the same package, collapsed =="
set +e
rm -f "$ROOT/backend/build/reports/control-after.txt"
"$ROOT/tools/work-lock.sh" run verify "cd $ROOT/backend && ./gradlew test --no-daemon --rerun -q -PcontextCensus \
  -PtestJvmArgs='-Dkms.context.census.out=build/reports/control-after.txt -Dkms.context.census.every=100' \
  --tests 'org.iskcon.kms.staff.*'" > "${TMPDIR:-/tmp}/control-T-064-after.log" 2>&1
after_status=$?
set -e
tail -20 "${TMPDIR:-/tmp}/control-T-064-after.log" | grep -E "Total:|Passed:|Failed:|BUILD" || true
grep -E "contexts built|distinct cache keys|ignoring" "$ROOT/backend/build/reports/control-after.txt" || true
echo "   gradle exit status: $after_status"

echo
echo "== 5. restore from the snapshot, and prove the tree is back =="
rm -f "$SHARED/SharedStubs.java" "$SHARED/StubTokenVerifier.java"
rm -rf "$PKG"
cp -R "$SNAP/staff" "$PKG"
echo "   git diff --stat for staff/ after restore (must be empty):"
git -C "$ROOT" diff --stat -- backend/src/test/java/org/iskcon/kms/staff
echo "   git status --porcelain for the touched paths (must be empty):"
git -C "$ROOT" status --porcelain -- backend/src/test/java/org/iskcon/kms/staff \
  backend/src/test/java/org/iskcon/kms/SharedStubs.java \
  backend/src/test/java/org/iskcon/kms/StubTokenVerifier.java
echo "   done."
