#![forbid(unsafe_code)]

use anyhow::{bail, Context, Result};
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::collections::BTreeSet;
use std::fs;
use std::path::{Component, Path, PathBuf};

#[derive(Debug, Deserialize)]
struct SchemaLock {
    generator: GeneratorIdentity,
    schemas: Vec<LockedSchema>,
}

#[derive(Debug, Deserialize)]
struct GeneratorIdentity {
    version: String,
}

#[derive(Debug, Deserialize)]
struct LockedSchema {
    path: PathBuf,
    sha256: String,
}

#[derive(Debug, Deserialize)]
struct GeneratorMap {
    format: String,
    schema_path: PathBuf,
    outputs: Vec<GeneratedOutput>,
}

#[derive(Debug, Deserialize)]
struct GeneratedOutput {
    template: PathBuf,
    output: PathBuf,
}

fn main() -> Result<()> {
    let command = std::env::args().nth(1).unwrap_or_else(|| "check".into());
    let root = find_root(std::env::current_dir()?)?;
    match command.as_str() {
        "check" => {
            let lock = load_and_lint(&root)?;
            render_registered_outputs(&root, &lock, false)
        }
        "generate" => {
            let lock = load_and_lint(&root)?;
            render_registered_outputs(&root, &lock, true)
        }
        "lint" => load_and_lint(&root).map(|_| ()),
        "lock-digest" => {
            let bytes = fs::read(root.join("contracts/schema-lock.json"))?;
            println!("{:x}", Sha256::digest(bytes));
            Ok(())
        }
        _ => bail!("usage: ore-mcp-codegen [check|generate|lint|lock-digest]"),
    }
}

fn find_root(mut current: PathBuf) -> Result<PathBuf> {
    loop {
        if current.join("contracts/schema-lock.json").is_file() {
            return Ok(current);
        }
        if !current.pop() {
            bail!("could not locate contracts/schema-lock.json");
        }
    }
}

fn load_and_lint(root: &Path) -> Result<SchemaLock> {
    let lock: SchemaLock = serde_json::from_slice(&fs::read(root.join("contracts/schema-lock.json"))?)?;
    if lock.schemas.is_empty() {
        bail!("schema lock contains no schemas");
    }
    for entry in &lock.schemas {
        let path = safe_join(root, &entry.path)?;
        let bytes = fs::read(&path).with_context(|| format!("read {}", path.display()))?;
        let actual = format!("{:x}", Sha256::digest(&bytes));
        if actual != entry.sha256 {
            bail!("schema digest mismatch for {}", entry.path.display());
        }
        let schema: serde_json::Value = serde_json::from_slice(&bytes)?;
        lint_portable_profile(&schema, "$")?;
    }
    Ok(lock)
}

fn render_registered_outputs(root: &Path, lock: &SchemaLock, write: bool) -> Result<()> {
    let plan: GeneratorMap = serde_json::from_slice(&fs::read(
        root.join("tooling/schema-codegen/generator-map.json"),
    )?)?;
    if plan.format != "ore-mcp-generator-map/v1" {
        bail!("unsupported generator-map format");
    }
    let schema_entry = lock
        .schemas
        .iter()
        .find(|entry| entry.path == plan.schema_path)
        .context("generator-map schema is not present in schema-lock.json")?;
    let schema_path = safe_join(root, &schema_entry.path)?;
    let schema: serde_json::Value = serde_json::from_slice(&fs::read(schema_path)?)?;
    let schema_id = schema
        .get("$id")
        .and_then(serde_json::Value::as_str)
        .context("registered schema is missing $id")?;

    let mut drift = Vec::new();
    for item in &plan.outputs {
        let template_path = safe_join(root, &item.template)?;
        let output_path = safe_join(root, &item.output)?;
        let rendered = fs::read_to_string(&template_path)?
            .replace("{{GENERATOR_VERSION}}", &lock.generator.version)
            .replace("{{SCHEMA_ID}}", schema_id)
            .replace("{{SCHEMA_SHA256}}", &schema_entry.sha256);
        if [
            "{{GENERATOR_VERSION}}",
            "{{SCHEMA_ID}}",
            "{{SCHEMA_SHA256}}",
        ]
        .iter()
        .any(|placeholder| rendered.contains(placeholder))
        {
            bail!("unresolved placeholder in {}", item.template.display());
        }
        if write {
            write_atomically(&output_path, rendered.as_bytes())?;
        } else if fs::read(&output_path).ok().as_deref() != Some(rendered.as_bytes()) {
            drift.push(item.output.display().to_string());
        }
    }
    if drift.is_empty() {
        Ok(())
    } else {
        bail!("generated output drift: {}", drift.join(", "))
    }
}

fn write_atomically(path: &Path, bytes: &[u8]) -> Result<()> {
    let parent = path.parent().context("generated output has no parent")?;
    fs::create_dir_all(parent)?;
    let file_name = path
        .file_name()
        .and_then(|value| value.to_str())
        .context("generated output name is not UTF-8")?;
    let temporary = parent.join(format!(".{file_name}.{}.tmp", std::process::id()));
    fs::write(&temporary, bytes)?;
    fs::rename(&temporary, path).or_else(|error| {
        let _ = fs::remove_file(&temporary);
        Err(error)
    })?;
    Ok(())
}

fn safe_join(root: &Path, relative: &Path) -> Result<PathBuf> {
    if relative.is_absolute()
        || relative.components().any(|component| {
            matches!(
                component,
                Component::ParentDir | Component::RootDir | Component::Prefix(_)
            )
        })
    {
        bail!("unsafe generator path: {}", relative.display());
    }
    Ok(root.join(relative))
}

fn lint_portable_profile(value: &serde_json::Value, path: &str) -> Result<()> {
    const FORBIDDEN: &[&str] = &[
        "$dynamicRef",
        "$dynamicAnchor",
        "dependentSchemas",
        "dependentRequired",
        "unevaluatedItems",
        "unevaluatedProperties",
        "not",
        "if",
        "then",
        "else",
    ];
    match value {
        serde_json::Value::Object(map) => {
            let keys = map.keys().map(String::as_str).collect::<BTreeSet<_>>();
            if let Some(keyword) = FORBIDDEN.iter().find(|keyword| keys.contains(**keyword)) {
                bail!("unsupported portable-profile keyword {keyword} at {path}");
            }
            if map.contains_key("anyOf") {
                bail!("anyOf is not portable-profile v1 at {path}");
            }
            if let Some(one_of) = map.get("oneOf") {
                let branches = one_of.as_array().context("oneOf must be an array")?;
                if branches.len() < 2 {
                    bail!("oneOf requires at least two branches at {path}");
                }
            }
            for (key, child) in map {
                lint_portable_profile(child, &format!("{path}/{key}"))?;
            }
        }
        serde_json::Value::Array(values) => {
            for (index, child) in values.iter().enumerate() {
                lint_portable_profile(child, &format!("{path}/{index}"))?;
            }
        }
        _ => {}
    }
    Ok(())
}
