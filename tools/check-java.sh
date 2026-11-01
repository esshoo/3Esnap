#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .test-output/java
javac --release 17 -encoding UTF-8 -d .test-output/java \
  app/src/main/java/com/snapclean/app/DownloadPolicy.java tools/PolicySelfTest.java tools/ParseJava.java
javac --release 17 -encoding UTF-8 -d .test-output/java \
  app/src/main/java/com/snapclean/app/EditPlan.java tools/EditPlanSelfTest.java
javac --release 17 -encoding UTF-8 -cp .test-output/java -d .test-output/java \
  app/src/main/java/com/snapclean/app/BackupRules.java tools/BackupRulesSelfTest.java
java -cp .test-output/java com.snapclean.app.BackupRulesSelfTest
java -cp .test-output/java com.snapclean.app.EditPlanSelfTest
java -cp .test-output/java com.snapclean.app.PolicySelfTest
java -cp .test-output/java ParseJava app/src/main/java
