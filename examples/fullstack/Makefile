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
		-o $(BUILD_DIR)/server --fast --no-cache
	$(CC) -std=c11 -O2 -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=0 \
		-fno-stack-protector -ffunction-sections -fdata-sections \
		-I$(ABLAC_DIR)/runtime -c $(ABLAC_DIR)/runtime/abla_runtime.c \
		-o $(BUILD_DIR)/server.deploy-value-runtime.o
	$(CC) -std=c11 -O2 -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=0 \
		-fno-stack-protector -ffunction-sections -fdata-sections \
		-I$(ABLAC_DIR)/runtime -c $(ABLAC_DIR)/runtime/abla_runtime_host.c \
		-o $(BUILD_DIR)/server.deploy-host.o
	$(CC) -r \
		$(BUILD_DIR)/server.o \
		$(BUILD_DIR)/server.deploy-host.o \
		$(BUILD_DIR)/server.deploy-value-runtime.o \
		-o $(BUILD_DIR)/server-prebuilt.o

android:
	cd $(PROJECT_DIR) && $(COMPILER) build \
		$(EXAMPLE_DIR)/android_build.ab \
		-o $(BUILD_DIR)/android-build-driver --fast --no-cache
	nix-shell $(ABLAC_DIR)/examples/android/shell.nix --run \
		"android-gradle -p '$(EXAMPLE_DIR)' :android-app:assembleDebug -PablaMobileConfiguration=debug --console=plain"

clean:
	@if [ -d "$(BUILD_DIR)" ]; then gio trash "$(BUILD_DIR)"; fi
