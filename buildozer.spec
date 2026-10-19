[app]
title = LUXE Music
package.name = luxemusic
package.domain = com.luxe
source.dir = .
source.include_exts = py,kv,png,jpg,jpeg,atlas,json,ttf,otf
icon.filename = %(source.dir)s/icon.png
version = 0.1.0
requirements = python3,kivy==2.3.1,pyjnius
orientation = portrait
fullscreen = 0

# Music folder access is granted by Android's Storage Access Framework picker.
android.permissions = READ_MEDIA_AUDIO,READ_EXTERNAL_STORAGE
android.api = 35
android.minapi = 23
android.archs = arm64-v8a, armeabi-v7a
android.accept_sdk_license = True

[buildozer]
log_level = 2
warn_on_root = 1
