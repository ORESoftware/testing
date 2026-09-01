import 'dart:convert';
import 'dart:io';

import 'package:ore_mcp_contracts/ore_mcp_contracts.dart';
import 'package:test/test.dart';

void main() {
  final repositoryRoot = Directory.current.parent.parent;
  final examplesRoot = Directory('${repositoryRoot.path}/contracts/examples');
  final expectationDocument =
      jsonDecode(
            File('${examplesRoot.path}/expectations.json').readAsStringSync(),
          )
          as Map<String, Object?>;
  final fixtures = expectationDocument['fixtures']! as Map<String, Object?>;

  for (final entry in fixtures.entries) {
    final fixture = entry.key;
    final expected = entry.value! as Map<String, Object?>;
    test('matches the canonical fixture $fixture', () {
      final input = jsonDecode(
        File('${examplesRoot.path}/$fixture').readAsStringSync(),
      );
      ContractValidationException? failure;
      try {
        ResultEnvelope.fromJson(input);
      } on ContractValidationException catch (error) {
        failure = error;
      }
      expect(failure == null, expected['accepted']);
      if (expected['accepted'] == false) {
        expect(failure?.code, expected['error_code']);
        expect(failure?.path, expected['error_path']);
      }
    });
  }

  test('constructors enforce the same bounded invariants', () {
    expect(
      () => SafeError(
        code: 'bad.path',
        message: 'empty component',
        path: const [''],
        retryable: false,
      ),
      throwsA(
        isA<ContractValidationException>()
            .having((error) => error.code, 'code', 'min_length')
            .having((error) => error.path, 'path', r'$/error/path/0'),
      ),
    );
  });
}
