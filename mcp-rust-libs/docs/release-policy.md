
# Coordinated release policy

- one repository version and release-set record;
- per-target native package identities, all tied to the same schema digest;
- generated output committed and drift-checked;
- no publication from pull requests or moving branches;
- native package and Zed artifacts built twice and compared;
- all four native validators must pass the exact fixture corpus;
- release metadata records commit, schema lock digest, generator version, package file manifest, checksums, SBOM/provenance, and migration notes;
- rollback uses a new reviewed release; published artifacts are immutable.
