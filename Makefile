PACKAGE = $(notdir $(CURDIR))

MAIN_PATH = cmd/main.go
BUILD_PATH = build/package/

INSTALL_PATH = /usr/bin/
CGO_ENABLED=0

FULL_PATH = $(BUILD_PATH)$(PACKAGE)

VERSION=$(shell git describe --tags --always --abbrev=0 --match='v[0-9]*.[0-9]*.[0-9]*' 2>/dev/null | sed 's/^.//')
COMMIT_HASH=$(shell git rev-parse --short HEAD)
BUILD_TIMESTAMP=$(shell date '+%Y-%m-%dT%H:%M:%S')

FULL_PACKAGE=$(shell go list -m)
LDFLAGS=-ldflags="-X '${FULL_PACKAGE}/internal.Version=${VERSION}' \
                  -X '${FULL_PACKAGE}/internal.CommitHash=${COMMIT_HASH}' \
                  -X '${FULL_PACKAGE}/internal.BuildTime=${BUILD_TIMESTAMP}' \
                  -s -w \
                  -extldflags '-static'"

.phony: run

run:
	go run $(MAIN_PATH)

build: clean
	go build $(LDFLAGS) -v -o $(BUILD_PATH)$(PACKAGE) $(MAIN_PATH)

clean:
	rm -rf $(FULL_PATH)

tests:
	go test ./...

lint:
	golangci-lint run

gen-proto:install-proto
	protoc --proto_path=proto --go_out=. proto/*

install-proto:
	@go install google.golang.org/protobuf/cmd/protoc-gen-go@latest
