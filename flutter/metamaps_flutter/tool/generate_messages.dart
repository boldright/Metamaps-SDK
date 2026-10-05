import 'dart:io';

const _generatedFiles = <String>[
  'lib/src/messages.g.dart',
  'android/src/main/kotlin/jp/metamaps/flutter/Messages.g.kt',
  'ios/metamaps_flutter/Sources/metamaps_flutter/Messages.g.swift',
];

Future<void> main() async {
  if (!File('pigeons/messages.dart').existsSync()) {
    stderr.writeln(
      'Run this command from the metamaps_flutter package root.',
    );
    exitCode = 64;
    return;
  }

  final pigeonExitCode = await _run(<String>[
    'run',
    'pigeon',
    '--input',
    'pigeons/messages.dart',
  ]);
  if (pigeonExitCode != 0) {
    exitCode = pigeonExitCode;
    return;
  }

  final formatExitCode = await _run(<String>[
    'format',
    'lib/src/messages.g.dart',
  ]);
  if (formatExitCode != 0) {
    exitCode = formatExitCode;
    return;
  }

  for (final path in _generatedFiles) {
    final file = File(path);
    final normalized = file
        .readAsStringSync()
        .replaceAll(RegExp(r'[ \t]+(?=\r?\n|$)'), '')
        .trimRight();
    file.writeAsStringSync('$normalized\n');
  }
}

Future<int> _run(List<String> arguments) async {
  final process = await Process.start(
    Platform.resolvedExecutable,
    arguments,
    mode: ProcessStartMode.inheritStdio,
  );
  return process.exitCode;
}
