import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import {
  ContractValidationError,
  ResultEnvelopeSchema,
  parseResultEnvelope,
  validateResultEnvelope,
} from "../src/generated/result-envelope.js";

type FixtureExpectation = {
  accepted: boolean;
  error_code?: string;
  error_path?: string;
};

const readJson = (url: URL): unknown =>
  JSON.parse(readFileSync(fileURLToPath(url), "utf8")) as unknown;

const expectationDocument = readJson(
  new URL("../../../contracts/examples/expectations.json", import.meta.url),
) as { fixtures: Record<string, FixtureExpectation> };

describe("ResultEnvelopeSchema", () => {
  for (const [fixture, expected] of Object.entries(expectationDocument.fixtures)) {
    it(`matches the canonical fixture ${fixture}`, () => {
      const input = readJson(new URL(`../../../contracts/examples/${fixture}`, import.meta.url));
      const stableError = validateResultEnvelope(input);
      expect(stableError === null).toBe(expected.accepted);
      expect(ResultEnvelopeSchema.safeParse(input).success).toBe(expected.accepted);
      if (!expected.accepted) {
        expect(stableError).toMatchObject({
          code: expected.error_code,
          path: expected.error_path,
        });
      }
    });
  }

  it("normalizes parse failures to stable code and JSON pointer", () => {
    expect(() =>
      parseResultEnvelope({
        ok: false,
        error: { code: "Bad", message: "invalid", retryable: false },
      }),
    ).toThrowError(ContractValidationError);
    try {
      parseResultEnvelope({
        ok: false,
        error: { code: "Bad", message: "invalid", retryable: false },
      });
    } catch (error) {
      expect(error).toMatchObject({ code: "pattern", path: "$/error/code" });
    }
  });
});
