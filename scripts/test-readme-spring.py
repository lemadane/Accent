#!/usr/bin/env python3
"""Build the README's Spring example as an independent Gradle consumer (JDK 21+)."""
from pathlib import Path
import argparse
import re
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--offline", action="store_true", help="Use only cached Gradle dependencies")
options = parser.parse_args()

root = Path(__file__).resolve().parents[1]
readme = (root / "README.md").read_text()
setup = readme.split("## Using Accent in Your Project", 1)[1].split("### 4.", 1)[0]
example = readme.split("### 4. Create and Use HTML Components in Spring Boot", 1)[1]
blocks = re.findall(r"```groovy\n(.*?)```", setup, re.S)
assert len(blocks) == 3, "Expected settings, dependencies, and compile task snippets"
assert "includeBuild" in blocks[0], "The documented setup must resolve the real local modules"
with tempfile.TemporaryDirectory(prefix="accent-readme-") as directory:
    project = Path(directory)
    settings = blocks[0].replace("../accent", root.as_posix())
    (project / "settings.gradle").write_text(settings)
    (project / "build.gradle").write_text(blocks[1] + "\n" + blocks[2])
    accent = project / "src/main/accent"
    java = project / "src/main/java/com/example/demo"
    accent.mkdir(parents=True)
    java.mkdir(parents=True)
    (accent / "UserCard.accent").write_text(re.search(r"```accent\n(.*?)```", example, re.S)[1])
    (java / "UserController.java").write_text(re.search(r"```java\n(.*?)```", example, re.S)[1])
    subprocess.run([str(root / "gradlew"), "-p", directory, "classes",
                    "--no-configuration-cache"] + (["--offline"] if options.offline else []), check=True)
    for name in ("UserCard", "UserController"):
        assert (project / f"build/classes/java/main/com/example/demo/{name}.class").exists(), name
print("README Spring consumer compiled successfully")
