#!/usr/bin/env python3
"""Build the Android app from a clean checkout with locally installed Android tools.

No device, Git, or network commands are used. By default this creates an unsigned
APK under dist/. Signing is opt-in through --keystore/--alias and environment
passwords; the signed APK is emitted as a separate file.
"""
import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app"
BUILD_TEMP = ROOT / "build" / "temp"
DIST = ROOT / "dist"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
TEST_SOURCE_NAMES = (
    "ConnectionGate.java",
    "MonitorPolicy.java",
    "PopupConfig.java",
    "DeviceModel.java",
    "BatterySnapshot.java",
    "BatteryProtocol.java",
    "EarbudStateProtocol.java",
)
STORE_PASSWORD_ENV = "EARBUDS_STORE_PASSWORD"
KEY_PASSWORD_ENV = "EARBUDS_KEY_PASSWORD"


class BuildError(Exception):
    """A user-correctable build configuration or tool error."""


def _expand_path(value):
    return Path(os.path.expandvars(os.path.expanduser(str(value))))


def _find_jdk_tool(name):
    suffixes = (".exe", ".bat", ".cmd", "") if os.name == "nt" else ("",)
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        bin_dir = _expand_path(java_home) / "bin"
        for suffix in suffixes:
            candidate = bin_dir / (name + suffix)
            if candidate.is_file():
                return candidate
    for candidate_name in (name + suffix for suffix in suffixes):
        found = shutil.which(candidate_name)
        if found:
            return Path(found)
    return None


def find_jdk():
    java = _find_jdk_tool("java")
    javac = _find_jdk_tool("javac")
    missing = []
    if java is None:
        missing.append("java")
    if javac is None:
        missing.append("javac")
    if missing:
        raise BuildError(
            "JDK tools not found (" + ", ".join(missing) +
            "). Set JAVA_HOME to a JDK or add its bin directory to PATH."
        )
    return java, javac


def run(command, label, cwd=ROOT):
    argv = [str(part) for part in command]
    print("RUN " + label, flush=True)
    try:
        subprocess.run(argv, cwd=str(cwd), check=True)
    except FileNotFoundError as exc:
        raise BuildError("Could not launch " + label + ": " + str(exc)) from exc
    except subprocess.CalledProcessError as exc:
        raise BuildError(label + " failed with exit code " + str(exc.returncode)) from exc


def _test_files():
    tests_dir = APP / "tests" / "dev" / "earbuds" / "popup"
    test_files = sorted(tests_dir.glob("*Test.java"))
    if not test_files:
        raise BuildError("No Java test suites found under " + str(tests_dir))
    missing = [path for path in test_files if not path.is_file()]
    if missing:
        raise BuildError("Missing Java test source: " + str(missing[0]))
    return test_files


def run_tests(java, javac, temp_root):
    sources = [APP / "src" / "dev" / "earbuds" / "popup" / name
               for name in TEST_SOURCE_NAMES]
    for source in sources:
        if not source.is_file():
            raise BuildError("Missing Java source required by tests: " + str(source))
    tests = _test_files()
    test_classes = temp_root / "tests"
    test_classes.mkdir(parents=True, exist_ok=True)
    run([javac, "-encoding", "UTF-8", "-d", test_classes, *sources, *tests],
        "javac test compilation")
    for test_file in tests:
        class_name = "dev.earbuds.popup." + test_file.stem
        run([java, "-cp", test_classes, class_name], "Java test " + test_file.stem)


def _version_key(path):
    numbers = re.findall(r"\d+", path.name)
    return tuple(int(value) for value in numbers) if numbers else (0,)


def _tool_file(build_tools_dir, name):
    suffixes = (".exe", "") if os.name == "nt" else ("", ".exe")
    for suffix in suffixes:
        candidate = build_tools_dir / (name + suffix)
        if candidate.is_file():
            return candidate
    return None


def resolve_android_tools(args):
    sdk_value = args.sdk or os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    sdk = _expand_path(sdk_value) if sdk_value else None
    if sdk is not None and not sdk.is_dir():
        raise BuildError("Android SDK path does not exist: " + str(sdk))

    android_jar = _expand_path(args.android_jar) if args.android_jar else None
    if android_jar is None and sdk is not None:
        platform = args.platform
        if platform.startswith("android-"):
            platform = platform[len("android-"):]
        android_jar = sdk / "platforms" / ("android-" + platform) / "android.jar"
    if android_jar is None or not android_jar.is_file():
        if args.android_jar:
            detail = "Android API jar not found: " + str(android_jar)
        else:
            detail = "Android SDK platform jar not found."
        detail += " Pass --sdk and --platform, or pass --android-jar explicitly."
        raise BuildError(detail)

    build_tools_dir = _expand_path(args.build_tools_dir) if args.build_tools_dir else None
    if build_tools_dir is None and sdk is not None:
        build_tools_root = sdk / "build-tools"
        if args.build_tools:
            build_tools_dir = build_tools_root / args.build_tools
        elif build_tools_root.is_dir():
            versions = [path for path in build_tools_root.iterdir() if path.is_dir()]
            if versions:
                build_tools_dir = max(versions, key=_version_key)
        if build_tools_dir is None:
            raise BuildError(
                "No Android build-tools version found. Pass --build-tools, "
                "or use --build-tools-dir with a local tool directory."
            )
    if build_tools_dir is None or not build_tools_dir.is_dir():
        wanted = str(build_tools_dir) if build_tools_dir else "an Android SDK root"
        raise BuildError(
            "Android build-tools directory not found (" + wanted +
            "). Pass --sdk/--build-tools or --build-tools-dir."
        )

    aapt2 = _tool_file(build_tools_dir, "aapt2")
    zipalign = _tool_file(build_tools_dir, "zipalign")
    d8_jar = build_tools_dir / "lib" / "d8.jar"
    apksigner_jar = build_tools_dir / "lib" / "apksigner.jar"
    missing = []
    if aapt2 is None:
        missing.append("aapt2")
    if zipalign is None:
        missing.append("zipalign")
    if not d8_jar.is_file():
        missing.append("lib/d8.jar")
    if missing:
        raise BuildError(
            "Build-tools directory is missing " + ", ".join(missing) +
            ": " + str(build_tools_dir)
        )
    return {
        "sdk": sdk,
        "android_jar": android_jar,
        "build_tools_dir": build_tools_dir,
        "aapt2": aapt2,
        "zipalign": zipalign,
        "d8_jar": d8_jar,
        "apksigner_jar": apksigner_jar if apksigner_jar.is_file() else None,
    }


def manifest_version():
    manifest = APP / "AndroidManifest.xml"
    try:
        root = ET.parse(str(manifest)).getroot()
    except (OSError, ET.ParseError) as exc:
        raise BuildError("Could not read Android manifest " + str(manifest) + ": " + str(exc)) from exc
    version = root.get(ANDROID_NS + "versionName")
    if not version or not re.fullmatch(r"[A-Za-z0-9._-]+", version):
        raise BuildError("Android manifest has no safe android:versionName.")
    return version


def validate_signing(args):
    if args.unsigned and (args.keystore or args.alias):
        raise BuildError("--unsigned cannot be combined with --keystore or --alias.")
    if bool(args.keystore) != bool(args.alias):
        raise BuildError("Signing needs both --keystore and --alias.")
    if not args.keystore:
        return
    keystore = _expand_path(args.keystore)
    if not keystore.is_file():
        raise BuildError("Keystore file not found: " + str(keystore))
    if not os.environ.get(STORE_PASSWORD_ENV):
        raise BuildError("Set " + STORE_PASSWORD_ENV + " before signing.")
    if not os.environ.get(KEY_PASSWORD_ENV):
        raise BuildError("Set " + KEY_PASSWORD_ENV + " before signing.")
    args.keystore = keystore


def _atomic_copy(source, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary_name = tempfile.mkstemp(
        prefix="." + destination.name + ".", suffix=".tmp", dir=str(destination.parent)
    )
    os.close(fd)
    temporary = Path(temporary_name)
    try:
        shutil.copyfile(str(source), str(temporary))
        os.replace(str(temporary), str(destination))
    finally:
        if temporary.exists():
            temporary.unlink()


def _sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _package_apk(resources_apk, dex_dir, raw_apk):
    dex_files = sorted(dex_dir.glob("classes*.dex"))
    if not dex_files:
        raise BuildError("D8 produced no classes*.dex output.")
    with zipfile.ZipFile(resources_apk, "r") as resources:
        with zipfile.ZipFile(raw_apk, "w") as archive:
            for item in resources.infolist():
                archive.writestr(
                    item.filename,
                    resources.read(item.filename),
                    compress_type=item.compress_type,
                )
            for dex_file in dex_files:
                archive.write(dex_file, dex_file.name, compress_type=zipfile.ZIP_STORED)
    with zipfile.ZipFile(raw_apk, "r") as archive:
        bad_member = archive.testzip()
        if bad_member:
            raise BuildError("Packaged APK has a corrupt ZIP member: " + bad_member)


def build_unsigned(java, javac, tools, temp_root, output):
    resources_zip = temp_root / "resources.zip"
    resources_apk = temp_root / "resources.apk"
    classes_dir = temp_root / "classes"
    dex_dir = temp_root / "dex"
    classes_dir.mkdir(parents=True, exist_ok=True)
    dex_dir.mkdir(parents=True, exist_ok=True)

    run([tools["aapt2"], "compile", "--dir", APP / "res", "-o", resources_zip],
        "aapt2 resource compilation")
    run([
        tools["aapt2"], "link", "-o", resources_apk, "-I", tools["android_jar"],
        "--manifest", APP / "AndroidManifest.xml", "--min-sdk-version", "26",
        "--target-sdk-version", "35", "-A", APP / "assets", resources_zip,
    ], "aapt2 resource linking")

    java_sources = sorted((APP / "src").rglob("*.java"))
    if not java_sources:
        raise BuildError("No application Java sources found under " + str(APP / "src"))
    run([
        javac, "-encoding", "UTF-8", "-source", "8", "-target", "8",
        "-Xlint:-options", "-classpath", tools["android_jar"], "-d", classes_dir,
        *java_sources,
    ], "javac application compilation")
    class_files = sorted(classes_dir.rglob("*.class"))
    if not class_files:
        raise BuildError("javac produced no application classes.")
    run([
        java, "-cp", tools["d8_jar"], "com.android.tools.r8.D8",
        "--lib", tools["android_jar"], "--min-api", "26", "--output", dex_dir,
        *class_files,
    ], "D8 dex compilation")

    raw_apk = temp_root / "raw.apk"
    aligned_apk = temp_root / "aligned.apk"
    _package_apk(resources_apk, dex_dir, raw_apk)
    run([tools["zipalign"], "-f", "-p", "4", raw_apk, aligned_apk], "zipalign")
    run([tools["zipalign"], "-c", "-p", "4", aligned_apk], "zipalign verification")
    _atomic_copy(aligned_apk, output)
    return output


def sign_apk(java, tools, unsigned_apk, signed_apk, args):
    apksigner_jar = tools["apksigner_jar"]
    if apksigner_jar is None:
        raise BuildError(
            "Signing requested, but lib/apksigner.jar is missing from "
            + str(tools["build_tools_dir"])
        )
    fd, temporary_name = tempfile.mkstemp(
        prefix="." + signed_apk.name + ".", suffix=".apk", dir=str(signed_apk.parent)
    )
    os.close(fd)
    temporary = Path(temporary_name)
    temporary.unlink()
    try:
        run([
            java, "-jar", apksigner_jar, "sign", "--ks", args.keystore,
            "--ks-key-alias", args.alias, "--ks-pass", "env:" + STORE_PASSWORD_ENV,
            "--key-pass", "env:" + KEY_PASSWORD_ENV, "--v4-signing-enabled", "false",
            "--out", temporary, unsigned_apk,
        ], "apksigner signing")
        run([java, "-jar", apksigner_jar, "verify", "--verbose", "--print-certs", temporary],
            "apksigner verification")
        _atomic_copy(temporary, signed_apk)
    finally:
        if temporary.exists():
            temporary.unlink()
        idsig = Path(str(temporary) + ".idsig")
        if idsig.exists():
            idsig.unlink()
    return signed_apk


def build_parser():
    parser = argparse.ArgumentParser(
        description="Run the Java suites and build an Android APK using locally installed tools."
    )
    parser.add_argument("--test", action="store_true",
                        help="run all plain-Java suites only; requires a JDK, not an Android SDK")
    parser.add_argument("--sdk", help="Android SDK root (defaults to ANDROID_SDK_ROOT or ANDROID_HOME)")
    parser.add_argument("--build-tools", help="installed build-tools version, for example 37.0.0")
    parser.add_argument("--platform", default="36",
                        help="Android platform API folder to compile against (default: 36)")
    parser.add_argument("--android-jar", help="explicit android.jar path for a nonstandard SDK layout")
    parser.add_argument("--build-tools-dir",
                        help="explicit aapt2/zipalign/lib/d8.jar directory for a nonstandard SDK layout")
    parser.add_argument("--unsigned", action="store_true",
                        help="build only the default unsigned APK (this is already the default)")
    parser.add_argument("--keystore", help="local signing keystore; never copied into the APK")
    parser.add_argument("--alias", help="signing key alias (requires --keystore)")
    return parser


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        java, javac = find_jdk()
        if args.test:
            BUILD_TEMP.mkdir(parents=True, exist_ok=True)
            with tempfile.TemporaryDirectory(prefix="earbuds-test-", dir=str(BUILD_TEMP)) as temp:
                run_tests(java, javac, Path(temp))
            print("All Java test suites passed.", flush=True)
            return 0

        validate_signing(args)
        tools = resolve_android_tools(args)
        version = manifest_version()
        unsigned_name = "Earbuds-Popup-" + version + "-unsigned.apk"
        signed_name = "Earbuds-Popup-" + version + "-signed.apk"
        unsigned_output = DIST / unsigned_name
        signed_output = DIST / signed_name
        protected_apk = APP / "Earbuds-Popup-1.0.3.apk"
        if unsigned_output.resolve() == protected_apk.resolve() or signed_output.resolve() == protected_apk.resolve():
            raise BuildError("Refusing to write over the verified app/Earbuds-Popup-1.0.3.apk.")

        BUILD_TEMP.mkdir(parents=True, exist_ok=True)
        DIST.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="earbuds-build-", dir=str(BUILD_TEMP)) as temp:
            temp_root = Path(temp)
            run_tests(java, javac, temp_root)
            build_unsigned(java, javac, tools, temp_root, unsigned_output)

        print("Unsigned APK: " + str(unsigned_output), flush=True)
        print("SHA-256: " + _sha256(unsigned_output), flush=True)
        if args.keystore:
            sign_apk(java, tools, unsigned_output, signed_output, args)
            print("Signed APK: " + str(signed_output), flush=True)
            print("SHA-256: " + _sha256(signed_output), flush=True)
        return 0
    except BuildError as exc:
        parser.error(str(exc))
    except OSError as exc:
        parser.error("File operation failed: " + str(exc))
    return 2


if __name__ == "__main__":
    sys.exit(main())
