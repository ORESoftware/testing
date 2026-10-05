namespace Oreslang.Compiler;

model ProjectConfig {
  schema_version: "1";
  project?: ProjectMetadata;
  source?: SourceConfig;
  entrypoints?: Entrypoints;
}

model ProjectMetadata {
  name?: string;
  version?: string;
  root?: string;
}

model SourceConfig {
  roots?: string[];
  import_paths?: string[];
}

model Entrypoints {
  main?: string;
}
