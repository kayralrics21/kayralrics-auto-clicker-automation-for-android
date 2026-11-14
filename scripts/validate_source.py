#!/usr/bin/env python3
"""Offline structure checks; NOT an Android runtime or APK build test."""
from pathlib import Path
from xml.etree import ElementTree as ET

root = Path(__file__).resolve().parent.parent
sources = root / "app/src/main/java/net/kayralrics/autoclicker"
expected = ["CaptureService", "MainActivity", "OverlayController",
            "TapAccessibilityService", "TargetStore", "VisualMatcher"]
for name in expected:
    src = sources / (name + ".java")
    assert src.is_file(), f"Missing: {src}"
    assert f"class {name}" in src.read_text(), f"Wrong class name: {name}"

manifest = root / "app/src/main/AndroidManifest.xml"
xml = ET.parse(manifest)
a = "{http://schemas.android.com/apk/res/android}"
permission_names = {p.get(a + "name") for p in xml.findall("uses-permission")}
assert "android.permission.INTERNET" not in permission_names
assert "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" in permission_names
assert "android.permission.SYSTEM_ALERT_WINDOW" in permission_names
assert xml.find("application/service[@" + a + "name='.TapAccessibilityService']") is not None
assert xml.find("application/service[@" + a + "name='.CaptureService']") is not None
for resource in (root / "app/src/main/res").rglob("*.xml"):
    ET.parse(resource)

for gradle_file in ["settings.gradle", "build.gradle", "app/build.gradle",
                    "gradle/wrapper/gradle-wrapper.properties"]:
    assert (root / gradle_file).is_file()
assert "org.opencv:opencv:4.13.0" in (root / "app/build.gradle").read_text()
print("PASS: source files, manifest services, permissions, XML and build configuration")
print("NOTICE: full Gradle compilation and on-device tests are still required")
