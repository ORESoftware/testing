use serde_json::Value;
use std::collections::{BTreeMap, BTreeSet};
use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;

fn main() {
    if let Err(error) = run() {
        eprintln!("rust distribution validation failed: {error}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), String> {
    let manifest_path = env::args()
        .nth(1)
        .unwrap_or_else(|| "distribution/rust-packages.json".to_owned());
    let root =
        env::current_dir().map_err(|error| format!("cannot read current directory: {error}"))?;
    let metadata = cargo_metadata()?;
    let distribution_text = fs::read_to_string(&manifest_path)
        .map_err(|error| format!("cannot read {manifest_path}: {error}"))?;
    let distribution: Value = serde_json::from_str(&distribution_text)
        .map_err(|error| format!("invalid JSON in {manifest_path}: {error}"))?;

    let count = validate_inventory(&root, &metadata, &distribution)?;
    println!("validated {count} distributed Rust crates");
    return Ok(());
}

fn cargo_metadata() -> Result<Value, String> {
    let output = Command::new("cargo")
        .args(["metadata", "--locked", "--no-deps", "--format-version", "1"])
        .output()
        .map_err(|error| format!("cannot execute cargo metadata: {error}"))?;
    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("cargo metadata failed: {}", stderr.trim()));
    }
    let metadata = serde_json::from_slice(&output.stdout)
        .map_err(|error| format!("cargo metadata returned invalid JSON: {error}"))?;
    return Ok(metadata);
}

fn validate_inventory(
    root: &Path,
    metadata: &Value,
    distribution: &Value,
) -> Result<usize, String> {
    let workspace_ids = string_array(metadata, "workspace_members")?;
    let packages = metadata
        .get("packages")
        .and_then(Value::as_array)
        .ok_or_else(|| "cargo metadata is missing packages".to_owned())?;

    let mut package_by_id = BTreeMap::new();
    for package in packages {
        let id = required_string(package, "id", "cargo metadata package")?;
        let name = required_string(package, "name", "cargo metadata package")?;
        if name.trim().is_empty() {
            return Err(format!("cargo package {id} has an empty package.name"));
        }
        let manifest_path = required_string(package, "manifest_path", "cargo metadata package")?;
        package_by_id.insert(id.to_owned(), manifest_path.to_owned());
    }

    let canonical_root = root
        .canonicalize()
        .map_err(|error| format!("cannot canonicalize workspace root: {error}"))?;
    let mut workspace_paths = BTreeSet::new();
    for id in workspace_ids {
        let manifest = package_by_id.get(id).ok_or_else(|| {
            format!("workspace member {id} is missing from cargo metadata packages")
        })?;
        let manifest = PathBuf::from(manifest);
        let parent = manifest.parent().ok_or_else(|| {
            format!(
                "workspace member manifest has no parent: {}",
                manifest.display()
            )
        })?;
        let canonical_parent = parent
            .canonicalize()
            .map_err(|error| format!("cannot canonicalize workspace member {parent:?}: {error}"))?;
        let relative = canonical_parent
            .strip_prefix(&canonical_root)
            .map_err(|_| {
                format!("workspace member escapes repository root: {canonical_parent:?}")
            })?;
        workspace_paths.insert(normalize_path(relative));
    }

    let crates = distribution
        .get("crates")
        .and_then(Value::as_array)
        .ok_or_else(|| "distribution manifest is missing crates array".to_owned())?;
    let mut declared_paths = BTreeSet::new();
    for row in crates {
        let path = required_string(row, "path", "distribution crate")?;
        if path.is_empty()
            || Path::new(path).is_absolute()
            || path.split('/').any(|part| part == "..")
        {
            return Err(format!("invalid distribution crate path: {path}"));
        }
        if !declared_paths.insert(path.to_owned()) {
            return Err(format!("duplicate distribution crate path: {path}"));
        }
    }

    if workspace_paths != declared_paths {
        let missing = workspace_paths
            .difference(&declared_paths)
            .cloned()
            .collect::<Vec<_>>();
        let extra = declared_paths
            .difference(&workspace_paths)
            .cloned()
            .collect::<Vec<_>>();
        return Err(format!(
            "workspace/distribution drift: missing={missing:?} extra={extra:?}"
        ));
    }

    let private_git_allowed = distribution
        .get("consumer_policy")
        .and_then(|value| value.get("direct_private_git_dependency_allowed"))
        .and_then(Value::as_bool)
        .ok_or_else(|| {
            "consumer_policy.direct_private_git_dependency_allowed must be a boolean".to_owned()
        })?;
    if private_git_allowed {
        return Err("private git dependency policy must remain false".to_owned());
    }

    return Ok(declared_paths.len());
}

fn string_array<'a>(value: &'a Value, key: &str) -> Result<Vec<&'a str>, String> {
    let items = value
        .get(key)
        .and_then(Value::as_array)
        .ok_or_else(|| format!("missing {key} array"))?;
    let mut strings = Vec::with_capacity(items.len());
    for item in items {
        let text = item
            .as_str()
            .ok_or_else(|| format!("{key} contains a non-string value"))?;
        strings.push(text);
    }
    return Ok(strings);
}

fn required_string<'a>(value: &'a Value, key: &str, context: &str) -> Result<&'a str, String> {
    return value
        .get(key)
        .and_then(Value::as_str)
        .ok_or_else(|| format!("{context} is missing string field {key}"));
}

fn normalize_path(path: &Path) -> String {
    return path
        .components()
        .map(|component| component.as_os_str().to_string_lossy().into_owned())
        .collect::<Vec<_>>()
        .join("/");
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::fs;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn fixture_root() -> PathBuf {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|duration| duration.as_nanos())
            .unwrap_or(0);
        let root = env::temp_dir().join(format!("ores-rust-distribution-{nonce}"));
        fs::create_dir_all(root.join("infra/rust")).unwrap();
        fs::create_dir_all(root.join("cli/rust")).unwrap();
        fs::write(
            root.join("infra/rust/Cargo.toml"),
            "[package]\nname='infra'\nversion='0.1.0'\n",
        )
        .unwrap();
        fs::write(
            root.join("cli/rust/Cargo.toml"),
            "[package]\nname='cli'\nversion='0.1.0'\n",
        )
        .unwrap();
        return root;
    }

    fn metadata(root: &Path) -> Value {
        return json!({
            "workspace_members": ["infra 0.1.0", "cli 0.1.0"],
            "packages": [
                {
                    "id": "infra 0.1.0",
                    "name": "infra",
                    "manifest_path": root.join("infra/rust/Cargo.toml")
                },
                {
                    "id": "cli 0.1.0",
                    "name": "cli",
                    "manifest_path": root.join("cli/rust/Cargo.toml")
                }
            ]
        });
    }

    fn distribution(paths: &[&str], private_git_allowed: bool) -> Value {
        return json!({
            "crates": paths.iter().map(|path| json!({"path": path})).collect::<Vec<_>>(),
            "consumer_policy": {
                "direct_private_git_dependency_allowed": private_git_allowed
            }
        });
    }

    #[test]
    fn accepts_exact_inventory() {
        let root = fixture_root();
        let result = validate_inventory(
            &root,
            &metadata(&root),
            &distribution(&["infra/rust", "cli/rust"], false),
        );
        assert_eq!(result, Ok(2));
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn rejects_workspace_distribution_drift() {
        let root = fixture_root();
        let error = validate_inventory(
            &root,
            &metadata(&root),
            &distribution(&["infra/rust"], false),
        )
        .unwrap_err();
        assert!(error.contains("workspace/distribution drift"));
        assert!(error.contains("cli/rust"));
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn rejects_private_git_policy_regression() {
        let root = fixture_root();
        let error = validate_inventory(
            &root,
            &metadata(&root),
            &distribution(&["infra/rust", "cli/rust"], true),
        )
        .unwrap_err();
        assert_eq!(error, "private git dependency policy must remain false");
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn rejects_duplicate_declared_paths() {
        let root = fixture_root();
        let error = validate_inventory(
            &root,
            &metadata(&root),
            &distribution(&["infra/rust", "infra/rust"], false),
        )
        .unwrap_err();
        assert_eq!(error, "duplicate distribution crate path: infra/rust");
        fs::remove_dir_all(root).unwrap();
    }
}
