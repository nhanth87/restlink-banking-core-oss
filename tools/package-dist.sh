#!/usr/bin/env bash
# Package restlink-app into dist/restlink-app/ (dir tree, never a WAR).
# Ship dist/ only; never overwrite host configs/ (write .new + diff). JDK 25 only.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# --- JDK 25 gate ---
JV="$(java -version 2>&1 | head -1)"
case "$JV" in
  *\"25* ) : ;;
  * ) echo "FAIL: JDK 25 required (mise zulu-25). Found: $JV" >&2; exit 2 ;;
esac

APP=restlink-app
DIST="$ROOT/dist/$APP"

echo "=== build (fast-jar) ==="
mvn -q -DskipTests -pl "$APP" -am package

echo "=== assemble $DIST ==="
mkdir -p "$DIST"/{lib,app/html,configs,logs}
# Quarkus fast-jar output lives under restlink-app/target/quarkus-app when quarkus build runs.
if [ -d "$APP/target/quarkus-app" ]; then
  rsync -a --delete "$APP/target/quarkus-app/" "$DIST/quarkus-app/"
fi
# UI files only (never jars under app/html)
if [ -d "$ROOT/app/html" ]; then
  rsync -a "$ROOT/app/html/" "$DIST/app/html/"
elif [ -d "$APP/app/html" ]; then
  rsync -a "$APP/app/html/" "$DIST/app/html/"
fi

# configs: write .new, never clobber an existing operator config
if [ -f "$APP/src/main/resources/application.properties" ]; then
  if [ -f "$DIST/configs/application.properties" ]; then
    cp "$APP/src/main/resources/application.properties" "$DIST/configs/application.properties.new"
    echo "configs/application.properties exists — wrote .new (diff before adopting)"
  else
    cp "$APP/src/main/resources/application.properties" "$DIST/configs/application.properties"
  fi
fi
[ -f "$APP/src/main/resources/log4j2.xml" ] && cp "$APP/src/main/resources/log4j2.xml" "$DIST/configs/"

cat > "$DIST/run.sh" <<'RUN'
#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
exec java -Dquarkus.config.locations="file:$HERE/configs/application.properties" \
  -Dlog4j.configurationFile="file:$HERE/configs/log4j2.xml" \
  -jar "$HERE/quarkus-app/quarkus-run.jar"
RUN
chmod +x "$DIST/run.sh"
echo "PASS: packaged $DIST (prove-the-artifact: restart + jar tf + hit /admin before claiming done)"
