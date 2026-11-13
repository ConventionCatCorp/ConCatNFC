# AGENTS.md

Guidance for coding agents working in this repo. Human-oriented docs live in the
`README.md` files; read the root `README.md` first for the tag format (NTAG21x,
password scheme, TLV layout), which all components must agree on.

## Layout

| Path | What | Toolchain |
| --- | --- | --- |
| `ConcatNFCRegProxy/go/` | Localhost HTTP proxy (`:7070`, gin) that programs tags via a PC/SC USB reader (ACR122U). Windows/macOS/Linux. | Go (version from `go.mod`), cgo + `libpcsclite-dev` on Linux |
| `ConcatNFCRegProxy/esp32/` | Same role on an ESP32-S3 + PN532 over HSU; serial protocol, LEDs. | ESP-IDF 5.5, target `esp32s3` |
| `ConcatNFCRegProxy/chromeos/` | Chrome App using the Smart Card Connector. Plain JS, no build. | none |
| `ConcatNFCValidator/android/` | Kotlin/Compose app that authenticates attendee tags against ConCat. | Gradle wrapper, JDK 17, compileSdk 35 |
| `ConcatNFCValidator/ios/` | Placeholder, no code. | — |

API contract for the proxy: `ConcatNFCRegProxy/go/doc/swagger.yaml`. Datasheets are in
`ConcatNFCRegProxy/go/doc/`.

## Commands

Run these from the component directory before declaring work done. CI runs the same.

Go (`ConcatNFCRegProxy/go`):

```sh
gofmt -l .            # must print nothing
go vet ./...
go test -race ./...
go mod tidy           # must not change go.mod/go.sum
```

Android (`ConcatNFCValidator/android`):

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

ESP32 (`ConcatNFCRegProxy/esp32`, inside the devcontainer or `espressif/idf:v5.5`):

```sh
idf.py build
```

Do not run `idf.py set-target`: it regenerates `sdkconfig` from defaults and drops
project settings (e.g. `CONFIG_LOG_MASTER_LEVEL`, which `main.cpp` needs to link).
The committed `sdkconfig` already targets `esp32s3`.

## CI

`.github/workflows/` has one workflow per component, path-filtered so only the touched
component builds:

- `regproxy-go.yml` — gofmt, tidy, vet, race tests on Linux; build on Linux/macOS/Windows.
- `validator-android.yml` — assemble, unit tests, lint; uploads APK and reports.
- `regproxy-esp32.yml` — `idf.py build` in `espressif/idf:v5.5`; fails if the build
  rewrites `sdkconfig`; uploads firmware binaries.

If you change the ESP-IDF version or chip target, update `sdkconfig`,
`dependencies.lock` and `regproxy-esp32.yml` together.

## Conventions and gotchas

- Commits: Angular convention, `type(scope): subject`. Useful scopes: `regproxy`,
  `esp32`, `chromeos`, `android`, `ci`.
- Go: `cmd/card_test.go` drives the HTTP handlers through `MockNFC`. When
  `NFCInterface` in `cmd/main.go` gains a method, add it to the mock or the test
  package won't compile.
- Go: `CardDefinitionRequest.IssuanceTimestamp` is a decimal string in JSON (64-bit
  values aren't safe as JS numbers). Prefer strings for new 64-bit fields in
  `types/schema.go`; changing an existing field's JSON type breaks ConCat's client.
- Go: format strings to `fmt.Errorf`/`Printf` must be constants (`go vet` enforces it);
  use `errors.New` / `fmt.Print` for dynamic messages.
- Behaviour changes to the proxy API must be mirrored in every implementation
  (Go, ESP32, ChromeOS) and in `swagger.yaml`; ConCat's browser code talks to all of them.
- ESP32: `sdkconfig` and `managed_components/` are committed deliberately. Don't edit
  `managed_components/` by hand; change `main/idf_component.yml` and let the component
  manager regenerate it.
- Hardware isn't available in CI. Logic that can be tested without a reader (TLV
  encoding in `internal/tags`, handler behaviour) should get unit tests; anything that
  touches `internal/nfc` or the PN532 driver needs a note on how it was tested on device.
- Never commit secrets, signing keys, `local.properties`, or build output.
