#!/bin/zsh
set -eu

# Usar siempre el artefacto actual y la carpeta de datos de este proyecto.
cd -- "$(dirname -- "$0")"
brilliant_jar="$PWD/target/brilliant-server.jar"
brilliant_java="${BRILLIANT_JAVA:-/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java}"

if [[ ! -x "$brilliant_java" ]]; then
  print -u2 'No se encontró Java 21 de Android Studio. Indica su ejecutable con BRILLIANT_JAVA.'
  exit 1
fi
if [[ ! -f "$brilliant_jar" ]]; then
  print -u2 'Falta target/brilliant-server.jar. Compila el servidor antes de iniciarlo.'
  exit 1
fi
print 'Iniciando Brilliant multijugador con target/brilliant-server.jar.'
exec "$brilliant_java" -jar "$brilliant_jar" --spring.profiles.active=lan "$@"
