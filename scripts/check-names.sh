#!/usr/bin/env bash
# Naming gate: the project is Honeycomb. It fails when a name the code carried before that appears anywhere in the
# tracked tree, inside identifiers and strings too. The old names are kept only as SHA-256 prefixes, so the gate itself
# never spells them out. Kotlin's `companion object` keyword is allowed.
set -euo pipefail
git ls-files -z -- . ':!scripts/check-names.sh' ':!**/baseline-prof.txt' | python3 -c '
import hashlib, re, sys
bad = {4: {"47612b3175fece07", "a95db5b0ac159e43"}, 5: {"dc3d50da8dac041f", "187c9bceeb919e1b"},
       9: {"c989edf18246e9c8", "9513ae0b56089c80"}}
keyword = re.compile(r"\s+object\b")
hits = 0
for path in sys.stdin.read().split("\0"):
    if not path: continue
    try: lines = open(path, encoding="utf-8").read().splitlines()
    except (UnicodeDecodeError, IsADirectoryError, FileNotFoundError): continue
    for no, line in enumerate(lines, 1):
        low = line.lower()
        for size, hashes in bad.items():
            for i in range(len(low) - size + 1):
                if hashlib.sha256(low[i:i + size].encode()).hexdigest()[:16] in hashes:
                    if size == 9 and keyword.match(low, i + size): continue
                    print(f"{path}:{no}: {line.strip()[:120]}"); hits += 1; break
if hits:
    print("check-names: a name from before Honeycomb is still in the tree (listed above)", file=sys.stderr); sys.exit(1)
'
