#!/bin/bash
# Campana completa E1-E5 (500 corridas). Reanudable: si se interrumpe, vuelva a ejecutarlo y sigue donde quedo.
# Uso (desde la raiz del proyecto iFogSim):  bash run_campaign.sh
set -e
cd "$(dirname "$0")"
JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || echo "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home")
export PATH="$JAVA_HOME/bin:$PATH"
echo "== Compilando..."
rm -rf build && mkdir -p build
find src -name "*.java" > build/sources.txt
javac -nowarn -encoding UTF-8 -cp "jars/*:jars/commons-math3-3.5/*" -d build @build/sources.txt 2>&1 | grep -v "^Note:" || true
JOBS=$(( $(sysctl -n hw.perflevel0.physicalcpu 2>/dev/null || sysctl -n hw.ncpu) - 1 )); [ "$JOBS" -lt 1 ] && JOBS=1
echo "== Ejecutando con $JOBS corridas en paralelo (no cierre la tapa ni deje dormir el Mac)..."
caffeinate -i python3 src/org/fog/test/perfeval/ehealth/scripts/run_experiments.py --cp "build:jars/*:jars/commons-math3-3.5/*" --jobs $JOBS --xmx 1500m
echo "== Campana terminada. Resultados en results/"
