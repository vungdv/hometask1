#!/bin/sh
# Local schema "CI": the gate every Avro payload schema passes before it can be produced with.
#   register-schemas.sh register          set the compatibility mode, check, then register every subject below
#   register-schemas.sh check SUBJECT FILE  check FILE against SUBJECT's registered versions, register nothing
# Runs in the schema-init service (make up) or on demand (make schema-register, make schema-check).
# A schema that breaks the subject's compatibility rule exits 1, exactly as a CI job would fail the build.
set -eu

REGISTRY="${SCHEMA_REGISTRY_URL:-http://schema-registry:8081}"
SCHEMAS_DIR="${SCHEMAS_DIR:-/schemas}"
MODE="${COMPATIBILITY:-BACKWARD_TRANSITIVE}"
JSON='Content-Type: application/vnd.schemaregistry.v1+json'

# subject = <topic>-value; the file is the payload schema (the CloudEvents data) of that topic
SUBJECTS="
polaris.order.lifecycle-value=OrderLifecycleEvent.avsc
polaris.fulfilment.shipments-value=ShipmentEvent.avsc
"

# The registry takes the schema as one JSON string
payload() {
  body=$(tr -d '\n' < "$1" | sed 's/\\/\\\\/g; s/"/\\"/g')
  printf '{"schema":"%s"}' "$body"
}

subject_exists() {
  [ "$(curl -s -o /dev/null -w '%{http_code}' "$REGISTRY/subjects/$1/versions/latest")" = 200 ]
}

# Fails (exit 1) when the schema is incompatible with any registered version of the subject.
check() {
  subject=$1 file=$2
  if ! subject_exists "$subject"; then
    echo "  new subject, nothing to be compatible with"
    return 0
  fi
  answer=$(curl -s -X POST -H "$JSON" --data "$(payload "$file")" \
    "$REGISTRY/compatibility/subjects/$subject/versions?verbose=true")
  case "$answer" in
    *'"is_compatible":true'*) echo "  compatible with every registered version ($MODE)" ;;
    *) echo "  REJECTED: $answer" >&2; return 1 ;;
  esac
}

register() {
  subject=$1 file=$2
  curl -sf -X PUT -H "$JSON" --data "{\"compatibility\":\"$MODE\"}" "$REGISTRY/config/$subject" > /dev/null
  echo "$subject <- $file"
  check "$subject" "$file"
  answer=$(curl -sf -X POST -H "$JSON" --data "$(payload "$file")" "$REGISTRY/subjects/$subject/versions")
  echo "  registered: ${answer%%,\"guid\"*}}"
}

case "${1:-register}" in
  register)
    failed=0
    for entry in $SUBJECTS; do
      register "${entry%%=*}" "$SCHEMAS_DIR/${entry#*=}" || failed=1
    done
    exit $failed
    ;;
  check)
    [ $# -eq 3 ] || { echo "usage: $0 check SUBJECT FILE" >&2; exit 2; }
    echo "$2 <- $3"
    check "$2" "$3"
    ;;
  *) echo "usage: $0 register | check SUBJECT FILE" >&2; exit 2 ;;
esac
