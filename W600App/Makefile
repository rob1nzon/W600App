JAVA_HOME := /opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home
ADB       := $(HOME)/Library/Android/sdk/platform-tools/adb
APK       := app/build/outputs/apk/debug/app-debug.apk
DEVICE    := 192.168.31.91:39479

export JAVA_HOME

.PHONY: build install deploy connect log clean

build:
	./gradlew assembleDebug

install: build
	$(ADB) -s $(DEVICE) install -r $(APK)

deploy: install
	@echo "Done."

connect:
	$(ADB) connect $(DEVICE)

log:
	$(ADB) -s $(DEVICE) logcat -s GlassesConn:D GlassesManager:D

clean:
	./gradlew clean
