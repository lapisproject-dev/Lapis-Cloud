#!/usr/bin/env bash
# Fails when a Gradle test lane did not really run. A lane whose task was SKIPPED (for example because
# LAPIS_TEST_POSTGRES_URL was missing) writes no JUnit XML at all, yet the build is green; this makes that visible.
# Usage: ci-assert-lane-ran.sh <results-dir> <min-xml-files> [required-file-name]
set -euo pipefail
dir="$1"
min="$2"
required="${3:-}"
count=$( (find "$dir" -maxdepth 1 -name "TEST-*.xml" 2>/dev/null || true) | wc -l | tr -d " ")
echo "lane results in $dir: $count XML files (minimum $min)"
if [ "$count" -lt "$min" ]; then
  echo "::error::lane did not run completely: $count XML files in $dir, expected at least $min" >&2
  exit 1
fi
if [ -n "$required" ] && [ ! -f "$dir/$required" ]; then
  echo "::error::required result file $required is missing in $dir" >&2
  exit 1
fi
