EXAMPLE_DIR := $(abspath .)
PROJECT_DIR := $(abspath ../..)
ABLAC_DIR := $(abspath ../../../ablac)
COMPILER := $(ABLAC_DIR)/build/ablac
BUILD_DIR := $(EXAMPLE_DIR)/build

.PHONY: all server android clean

all: server

server:
	mkdir -p $(BUILD_DIR)
	cd $(PROJECT_DIR) && $(COMPILER) build \
		$(EXAMPLE_DIR)/server_build.ab \
		-o $(BUILD_DIR)/server-build-driver --fast --no-cache
	cd $(PROJECT_DIR) && $(COMPILER) build \
		$(BUILD_DIR)/server.ab \
		-o $(BUILD_DIR)/server --fast --no-debug --no-cache
	# Icy's abla-prebuilt service links this object on its musl image: the build leaves
	# server.o beside the executable, and the runtime is already in it.
	cp $(BUILD_DIR)/server.o $(BUILD_DIR)/server-prebuilt.o

android:
	cd $(PROJECT_DIR) && $(COMPILER) build \
		$(EXAMPLE_DIR)/android_build.ab \
		-o $(BUILD_DIR)/android-build-driver --fast --no-cache
	nix-shell $(ABLAC_DIR)/examples/android/shell.nix --run \
		"android-gradle -p '$(EXAMPLE_DIR)' :android-app:assembleDebug -PablaMobileConfiguration=debug --console=plain"

clean:
	@if [ -d "$(BUILD_DIR)" ]; then gio trash "$(BUILD_DIR)"; fi
