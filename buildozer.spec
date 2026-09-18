[app]
title = 网格交易收益计算器
package.name = gridcalc
package.domain = cn.gridcalc
source.dir = .
source.include_exts = py
version = 2.0.0
requirements = python3,kivy==2.3.0
android.archs = arm64-v8a, armeabi-v7a, x86_64
orientation = portrait
fullscreen = 0
android.api = 33
android.minapi = 24
android.ndk = 25b
android.accept_sdk_license = True
p4a.branch = v2024.01.21

[buildozer]
log_level = 2
warn_on_root = 1
