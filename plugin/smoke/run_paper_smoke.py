"""Build already-verified fixture code and boot an isolated Paper instance. Never touches live files."""
from pathlib import Path
import argparse
import json
import os
import secrets
import shutil
import subprocess
import tempfile
import zipfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[3])
    args = parser.parse_args()
    root = args.root.resolve()
    fixture = Path(tempfile.mkdtemp(prefix="noqui-plugin-smoke-", dir=root / ".runtime"))
    scaffold = root / ".runtime/ci-server-smoke"
    for name in ("libraries", "versions", "cache"):
        if (scaffold / name).is_dir():
            shutil.copytree(scaffold / name, fixture / name)
    shutil.copy2(scaffold / "eula.txt", fixture / "eula.txt")
    if "eula=true" not in (fixture / "eula.txt").read_text():
        raise RuntimeError("An existing accepted fixture EULA is required")
    shutil.copy2(root / ".runtime/ci-server/paper-paperclip-26.3.local-SNAPSHOT.jar", fixture / "paper.jar")
    shutil.copytree(root / ".runtime/noqui-reference/world/noqui", fixture / "noqui_cabin")
    plugins = fixture / "plugins"
    plugins.mkdir()
    shutil.copy2(root / "noqui/plugin/target/noqui-plugin-1.0.11.jar", plugins / "Noqui.jar")
    shutil.copy2(root / "target/mc-luck-defense-1.0.34.jar", plugins / "MCLuckDefense.jar")
    data = plugins / "Noqui"
    data.mkdir()
    shutil.copy2(root / "noqui/plugin/src/main/resources/config.yml", data / "config.yml")
    (data / "bridge.key").write_bytes(secrets.token_bytes(32))
    (fixture / "server.properties").write_text(
        "server-ip=127.0.0.1\nserver-port=25587\nonline-mode=true\n"
        "level-type=minecraft:flat\ngenerate-structures=false\nview-distance=2\n"
        "simulation-distance=2\nspawn-protection=0\nmax-players=4\n"
        "enable-rcon=false\nenable-query=false\n", encoding="utf-8"
    )
    classpath = (root / "noqui/plugin/target/smoke-classpath.txt").read_text().strip()
    classes = fixture / "smoke-classes"
    classes.mkdir()
    argfile = fixture / "javac.args"
    args = ["-encoding", "UTF-8", "--release", "25", "-cp",
            str(root / "noqui/plugin/target/classes") + os.pathsep + classpath,
            "-d", str(classes), str(root / "noqui/plugin/smoke/NoquiPaperSmoke.java")]
    argfile.write_text("\n".join(json.dumps(a) for a in args), encoding="utf-8")
    subprocess.run(["javac", "@" + str(argfile)], check=True, cwd=root)
    with zipfile.ZipFile(plugins / "NoquiPaperSmoke.jar", "w", zipfile.ZIP_DEFLATED) as jar:
        for path in classes.rglob("*.class"):
            jar.write(path, path.relative_to(classes).as_posix())
        jar.writestr("plugin.yml", "name: NoquiPaperSmoke\nversion: 1\nmain: dev.noqui.smoke.NoquiPaperSmoke\napi-version: '26.3'\ndepend: [Noqui, MCLuckDefense]\n")
    print(f"Fixture: {fixture}", flush=True)
    for boot, flag in [(1, "-Dnoqui.smoke.keep-active=true"), (2, "-Dnoqui.smoke.expect-active=true")]:
        log = fixture / f"console-boot{boot}.log"
        with log.open("w", encoding="utf-8") as output:
            process = subprocess.Popen(["java", "-Xms512M", "-Xmx1536M", "--enable-native-access=ALL-UNNAMED", flag, "-jar", "paper.jar", "--nogui"], cwd=fixture,
                                       stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT, text=True)
            try:
                process.wait(timeout=240)
            except subprocess.TimeoutExpired:
                process.stdin.write("stop\n")
                process.stdin.flush()
                try:
                    process.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
                raise RuntimeError(f"Fixture timed out; inspect {log}")
        text = log.read_text(encoding="utf-8", errors="replace")
        if "NOQUI_SMOKE_OK" not in text or "NOQUI_SMOKE_FAIL" in text:
            print(text[-16000:])
            raise RuntimeError(f"Paper fixture failed; inspect {log}")
        print(next(line for line in text.splitlines() if "NOQUI_SMOKE_OK" in line), flush=True)
        print(f"Log: {log}", flush=True)


if __name__ == "__main__":
    main()
