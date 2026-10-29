# Research storage housekeeping

User preference: check storage and clean reproducible caches throughout research,
especially before acquiring more videos or expanding native frame exports.

Run between jobs while compilers are idle:

```sh
df -h .
du -hd 1 build
python3 scripts/cleanup-research-cache.py
python3 scripts/cleanup-research-cache.py --apply
```

The cleanup script has a fixed allowlist of repo-local Swift/Xcode compiler
caches and intermediate objects. Inspection is the default. It retains compiled
executables/apps and their dependency artifacts, recordings, downloaded dataset
archives, source frames, hand/torso crops, labels, model checkpoints, evaluation
results, provenance and the training environment. Rebuilds will recreate caches.
Do not run it during a build, inference/export job or phone installation.

Before each larger experiment, estimate export size from a small batch and
confirm it fits the available space with room for training and build outputs.
Process new videos in bounded batches, reuse existing native exports, and retain
only the checkpoints needed to reproduce decisions. Review duplicate full-frame
exports separately; existing evaluation fixtures bind some to exact paths and
hashes, so a blanket deletion of `build/` would destroy useful evidence.

For a completed bounded batch, `scripts/prune-research-frame-cache.py` can remove
unreferenced native `frame-*.png` exports after validating every retained frame
against its reviewed JSONL hash. Pass the native directory, all JSONL references
to preserve, and a new `--receipt` path; inspection is the default, `--apply`
performs eviction. It retains original videos, poses, clocks, selected frames,
patches, labels and model artifacts, and inventories every removed path/hash.
To revisit unsampled times later, regenerate native exports into a fresh
directory rather than treating the pruned directory as a complete RGB export.

The initial 2026-10-23 cleanup receipt is in
`build/research-cache-cleanup-2026-10-23.json`. Filesystem free-space change can
differ from estimated cache blocks because APFS clones, snapshots and other
processes share the volume.
