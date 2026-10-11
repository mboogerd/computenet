#!/usr/bin/env bash
set -euo pipefail

FORMAL_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TOOLS_DIR="$FORMAL_DIR/tools"
MODELS_DIR="$FORMAL_DIR/models"
CONTROLS_DIR="$FORMAL_DIR/controls"
SPECS_DIR="$FORMAL_DIR/specs"
JAR="$TOOLS_DIR/tla2tools.jar"
VERSION_FILE="$TOOLS_DIR/tla2tools.version"
CHECKSUM_FILE="$TOOLS_DIR/tla2tools.sha256"
JAVA_BIN="${JAVA:-java}"

usage() {
    printf 'usage: %s [<name>|--controls|--deep]\n' "${0##*/}" >&2
}

harness_error() {
    printf 'formal check error: %s\n' "$1" >&2
    exit 2
}

shopt -s nullglob
configs=()

if (( $# == 0 )); then
    configs=("$MODELS_DIR"/*.cfg "$CONTROLS_DIR"/*.cfg)
elif (( $# == 1 )); then
    case "$1" in
        --controls)
            configs=("$CONTROLS_DIR"/*.cfg)
            ;;
        --deep)
            configs=("$MODELS_DIR"/deep/*.cfg)
            ;;
        --*)
            usage
            exit 2
            ;;
        *)
            for candidate in \
                "$MODELS_DIR/$1.cfg" \
                "$CONTROLS_DIR/$1.cfg" \
                "$MODELS_DIR/deep/$1.cfg"; do
                if [[ -f "$candidate" ]]; then
                    configs=("$candidate")
                    break
                fi
            done
            if (( ${#configs[@]} == 0 )); then
                usage
                harness_error "no configuration named $1"
            fi
            ;;
    esac
else
    usage
    exit 2
fi

[[ -f "$VERSION_FILE" ]] || harness_error "missing $VERSION_FILE"
[[ -f "$CHECKSUM_FILE" ]] || harness_error "missing $CHECKSUM_FILE"

download_url="$(sed -n 's/^url=//p' "$VERSION_FILE")"
[[ -n "$download_url" ]] || harness_error "no url entry in $VERSION_FILE"

if [[ ! -f "$JAR" ]]; then
    command -v curl >/dev/null 2>&1 || harness_error "curl is required to download tla2tools.jar"
    printf 'Downloading %s\n' "$download_url" >&2
    if ! curl -fsSL --retry 3 -o "$JAR" "$download_url"; then
        harness_error "download failed: $download_url"
    fi
fi

verify_output=''
if command -v shasum >/dev/null 2>&1; then
    if ! verify_output="$(cd "$TOOLS_DIR" && shasum -a 256 -c "${CHECKSUM_FILE##*/}" 2>&1)"; then
        expected="$(awk 'NR == 1 { print $1 }' "$CHECKSUM_FILE")"
        actual="$(shasum -a 256 "$JAR" | awk '{ print $1 }')"
        printf 'formal check error: tla2tools.jar checksum mismatch\n' >&2
        printf '  expected: %s\n  actual:   %s\n' "$expected" "$actual" >&2
        printf '%s\n' "$verify_output" >&2
        exit 2
    fi
elif command -v sha256sum >/dev/null 2>&1; then
    if ! verify_output="$(cd "$TOOLS_DIR" && sha256sum -c "${CHECKSUM_FILE##*/}" 2>&1)"; then
        expected="$(awk 'NR == 1 { print $1 }' "$CHECKSUM_FILE")"
        actual="$(sha256sum "$JAR" | awk '{ print $1 }')"
        printf 'formal check error: tla2tools.jar checksum mismatch\n' >&2
        printf '  expected: %s\n  actual:   %s\n' "$expected" "$actual" >&2
        printf '%s\n' "$verify_output" >&2
        exit 2
    fi
else
    harness_error "neither shasum nor sha256sum is available"
fi

command -v "$JAVA_BIN" >/dev/null 2>&1 || harness_error "java is not available: $JAVA_BIN"

if (( ${#configs[@]} == 0 )); then
    exit 0
fi

meta_root="$(mktemp -d "${TMPDIR:-/tmp}/computenet-tlc.XXXXXX")" || \
    harness_error "could not create TLC metadata directory"
trap 'rm -rf "$meta_root"' EXIT

overall=0
for cfg in "${configs[@]}"; do
    cfg_file="${cfg##*/}"
    cfg_name="${cfg_file%.cfg}"
    header="$(sed -n '1p' "$cfg")"
    module="${header#\\* MODULE }"
    if [[ "$module" == "$header" || "$header" != "\\* MODULE $module" || \
          ! "$module" =~ ^[A-Za-z][A-Za-z0-9_]*$ ]]; then
        harness_error "$cfg must begin with: \\* MODULE <Name>"
    fi

    module_file="$MODELS_DIR/$module.tla"
    [[ -f "$module_file" ]] || harness_error "$cfg names missing module $module_file"

    kind=model
    if [[ "$cfg" == "$CONTROLS_DIR"/* ]]; then
        kind=control
    fi

    log="$meta_root/$cfg_name.log"
    start="$(date +%s)"
    if "$JAVA_BIN" \
        -XX:+UseParallelGC \
        "-DTLA-Library=$MODELS_DIR:$SPECS_DIR" \
        -cp "$JAR" \
        tlc2.TLC \
        -workers auto \
        -cleanup \
        -config "$cfg" \
        -metadir "$meta_root/$cfg_name" \
        "$module" >"$log" 2>&1; then
        status=0
    else
        status=$?
    fi
    elapsed=$(( $(date +%s) - start ))

    states="$(sed -nE 's/^.*states generated, ([0-9]+) distinct states found.*$/\1/p' "$log" | tail -n 1)"
    depth="$(sed -nE 's/^The depth of the complete state graph search is ([0-9]+)\.$/\1/p' "$log" | tail -n 1)"
    states="${states:-?}"
    depth="${depth:-?}"

    verdict=FAIL
    reason=''
    if [[ "$kind" == model ]]; then
        if (( status == 0 )); then
            verdict=PASS
        elif (( status == 11 || status == 12 || status == 13 )); then
            reason="violation exit=$status"
        else
            reason="crash exit=$status"
        fi
    else
        if (( status == 11 || status == 12 || status == 13 )); then
            verdict=PASS
        elif (( status == 0 )); then
            reason=exit-0-control
        else
            reason="crash exit=$status"
        fi
    fi

    printf '%s %s %s states=%s depth=%s %ss\n' \
        "$verdict" "$kind" "$cfg_name" "$states" "$depth" "$elapsed"
    if [[ "$verdict" == FAIL ]]; then
        printf '  reason: %s\n' "$reason"
        cat "$log"
        overall=1
    fi
done

exit "$overall"
