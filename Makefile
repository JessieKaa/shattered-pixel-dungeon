# Shattered Pixel Dungeon (fork) — PC build/run helpers
# 用法: make help

GRADLE     := ./gradlew
ADB_SERIAL ?= 20210119085654
DESKTOP_APK := android/build/outputs/apk/debug/android-debug.apk
# desktop 全屏由 SPDSettings.fullscreen() 控制(默认 true → 启动后切全屏)。
# run 时强制写 false 让窗口化启动;保留存档与其他设置,只动这两个 key。
PREFS := $(HOME)/.local/share/.shatteredpixel/shattered-pixel-dungeon/settings.xml

.PHONY: help build run test test-force release android android-install clean

help: ## 显示本帮助
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'

build: ## 编译 desktop(不启动)
	$(GRADLE) :desktop:classes

run: ## 编译并启动 desktop 游戏窗口(强制窗口化非全屏)
	@mkdir -p "$$(dirname "$(PREFS)")"
	@if [ ! -f "$(PREFS)" ] || ! grep -q '</properties>' "$(PREFS)"; then \
		printf '<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE properties SYSTEM "http://java.sun.com/dtd/properties.dtd">\n<properties>\n</properties>\n' > "$(PREFS)"; \
	fi
	@for k in fullscreen window_maximized; do \
		if grep -q "key=\"$$k\"" "$(PREFS)"; then \
			sed -i "s#<entry key=\"$$k\">[^<]*</entry>#<entry key=\"$$k\">false</entry>#" "$(PREFS)"; \
		else \
			sed -i "s#</properties>#<entry key=\"$$k\">false</entry>\n</properties>#" "$(PREFS)"; \
		fi; \
	done
	$(GRADLE) :desktop:debug

test: ## 跑 core 单测(增量)
	$(GRADLE) :core:test

test-force: ## 跑 core 单测(强制重跑)
	$(GRADLE) :core:cleanTest :core:test

release: ## 打 desktop fat jar -> desktop/build/libs/
	$(GRADLE) :desktop:release

android: ## 构建 Android debug APK
	$(GRADLE) :android:assembleDebug

android-install: ## 装机到测试设备(ADB_SERIAL=$(ADB_SERIAL))
	$(GRADLE) :android:assembleDebug
	adb -s $(ADB_SERIAL) install -r $(DESKTOP_APK)

clean: ## 清理构建产物
	$(GRADLE) clean
