"""Exercise cookie isolation with a fake downloader; never contact YouTube."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


def bash_path():
    git_bash = Path("C:/Program Files/Git/bin/bash.exe")
    return str(git_bash) if git_bash.is_file() else shutil.which("bash")


@unittest.skipUnless(bash_path(), "bash is required for cookie-wrapper checks")
class CookieWrapperTests(unittest.TestCase):
    def test_copy_is_private_source_unchanged_and_copy_cleaned_on_success_or_failure(self):
        for exit_code in (0, 7):
            with self.subTest(exit_code=exit_code), tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                source = root / "shared cookies.txt"
                source.write_bytes(b"test fixture; not real credentials\n")
                original = source.read_bytes()
                fake = root / "fake-ytdlp"
                fake.write_text(
                    '#!/usr/bin/env bash\nset -eu\n'
                    'test "$1" = --cookies\n'
                    'test "$2" != "$TEST_SOURCE"\n'
                    'test "$(stat -c %a "$2")" = 600\n'
                    'printf "%s" "$2" > "$TEST_PRIVATE_PATH"\n'
                    'printf "updated\\n" >> "$2"\n'
                    'exit "$TEST_EXIT"\n', encoding="utf-8", newline="\n")
                fake.chmod(0o700)
                private_path = root / "private-path.txt"
                env = os.environ | {
                    "NOQUI_YT_DLP_REAL": fake.as_posix(),
                    "TMPDIR": root.as_posix(), "TEST_SOURCE": source.as_posix(),
                    "TEST_PRIVATE_PATH": private_path.as_posix(), "TEST_EXIT": str(exit_code),
                }
                result = subprocess.run(
                    [bash_path(), str(Path(__file__).with_name("yt-dlp-private-cookies.sh")),
                     "--cookies", source.as_posix(), "--", "https://example.invalid/fixture"],
                    env=env, capture_output=True, text=True)
                self.assertEqual(result.returncode, exit_code, result.stderr)
                self.assertEqual(source.read_bytes(), original)
                self.assertTrue(private_path.exists())
                self.assertFalse(list(root.glob("noqui-cookies.*")))

    def test_missing_cookie_value_is_refused(self):
        result = subprocess.run(
            [bash_path(), str(Path(__file__).with_name("yt-dlp-private-cookies.sh")), "--cookies"],
            capture_output=True, text=True)
        self.assertEqual(result.returncode, 64)


if __name__ == "__main__":
    unittest.main()
